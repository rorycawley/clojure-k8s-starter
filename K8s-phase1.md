# Phase 1: Infrastructure

Set up a GCP project, provision all cloud infrastructure with OpenTofu, and verify you can connect to the GKE cluster and push Docker images.

**Prerequisite:** Nothing — this is the starting point.

**End state:** GKE cluster running, Cloud SQL database created, Artifact Registry accepting images, IAM + Workload Identity configured. No app deployed yet.

```
                         Phase 1 — End State Architecture
  ┌──────────────────────────────────────────────────────────────────────┐
  │                          GCP Project                                │
  │                                                                     │
  │  ┌─────────────────────────────────────────────────────────────┐    │
  │  │                     VPC (staging-vpc)                       │    │
  │  │                                                             │    │
  │  │  ┌───────────────────────┐   ┌──────────────────────────┐  │    │
  │  │  │   GKE Autopilot       │   │    Cloud SQL (Postgres)  │  │    │
  │  │  │   Cluster "staging"   │   │    Instance "staging"    │  │    │
  │  │  │                       │   │                          │  │    │
  │  │  │  (no app pods yet     │   │  DB: registry            │  │    │
  │  │  │   — that's Phase 2)   │◄──┤  User: registry-api     │  │    │
  │  │  │                       │   │  (private IP only)       │  │    │
  │  │  └───────────────────────┘   └──────────────────────────┘  │    │
  │  │          VPC peering for private SQL access                 │    │
  │  └─────────────────────────────────────────────────────────────┘    │
  │                                                                     │
  │  ┌───────────────────┐   ┌──────────────────────────────────────┐  │
  │  │ Artifact Registry │   │  IAM + Workload Identity             │  │
  │  │ registry-api      │   │  Pod SA ──► Cloud SQL Client         │  │
  │  │ (Docker images)   │   │  GitHub SA ──► GKE Deploy + AR Push  │  │
  │  └───────────────────┘   │  WIF Pool ──► GitHub Actions OIDC    │  │
  │                          └──────────────────────────────────────┘  │
  │                                                                     │
  │  ┌───────────────────┐   ┌───────────────┐                        │
  │  │ Static IP         │   │ GCS Bucket    │                        │
  │  │ (for Ingress LB)  │   │ (tofu state)  │                        │
  │  └───────────────────┘   └───────────────┘                        │
  └──────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents

1. [Prerequisites — tools and accounts](#1-prerequisites)
2. [Step 1 — GCP project setup](#2-step-1--gcp-project-setup)
3. [Step 2 — Create the OpenTofu files](#3-step-2--create-the-opentofu-files)
4. [Step 3 — Bootstrap (state bucket + Artifact Registry)](#4-step-3--bootstrap)
5. [Step 4 — Provision staging infrastructure](#5-step-4--provision-staging)
6. [Step 5 — Connect kubectl to the cluster](#6-step-5--connect-kubectl)
7. [Step 6 — Verify everything works](#7-step-6--verify-everything)
8. [Step 7 — Push a test image](#8-step-7--push-a-test-image)
9. [Troubleshooting](#9-troubleshooting)
10. [Tear down (if needed)](#10-tear-down)
11. [Checklist — Phase 1 complete](#11-checklist--phase-1-complete)

---

## 1. Prerequisites

### Tools to install

```bash
# macOS — install all at once
brew install opentofu helm jq
brew install --cask google-cloud-sdk
```

You also need **Docker** and **kubectl** — if you've been using Rancher Desktop for local K8s, both are already installed.

Verify versions:

```bash
tofu --version       # >= 1.6.0
helm version         # >= 3.x
gcloud version       # any recent version
kubectl version --client  # any recent version
docker --version     # any recent version
jq --version         # any version
```

### Accounts needed

- **GCP account** with billing enabled — https://console.cloud.google.com
- **GitHub account** with your repo (needed later for Workload Identity, but note the repo path now)

### Information to gather before starting

Write these down — you'll need them in `terraform.tfvars`:

| Value | Example | Where to find it |
|-------|---------|-----------------|
| GCP project ID | `my-k8s-project-123` | GCP Console → project selector → ID column |
| GitHub repo path | `your-org/clojure-k8s-starter` | Your GitHub repo URL minus `https://github.com/` |

---

## 2. Step 1 — GCP project setup

### Option A: Use an existing project

```bash
# Authenticate the gcloud CLI (your personal identity)
gcloud auth login

# Create Application Default Credentials (ADC)
# This is a SEPARATE credential used by tools other than gcloud — including
# OpenTofu's Google provider. Without this, `tofu plan/apply` will fail with
# "could not find default credentials."
gcloud auth application-default login

# Set your project
gcloud config set project YOUR_PROJECT_ID

# Verify billing is enabled
gcloud billing projects describe YOUR_PROJECT_ID --format="value(billingAccountName)"
# Should print a billing account ID. If blank, billing is not enabled.
```

### Option B: Create a new project

```bash
gcloud auth login
gcloud auth application-default login   # see Option A for why both are needed

# Create a project (ID must be globally unique)
gcloud projects create my-k8s-project-123 --name="K8s Starter"

# Link billing (find your billing account ID first)
gcloud billing accounts list
gcloud billing projects link my-k8s-project-123 \
  --billing-account=XXXXXX-YYYYYY-ZZZZZZ

# Set as default
gcloud config set project my-k8s-project-123
```

### Verify

```bash
gcloud config get-value project
# Should print your project ID
```

---

## 3. Step 2 — Create the OpenTofu files

Create the full directory structure. The resource code comes from K8s.md section 5.

> **Important — how to copy code from K8s.md:** K8s.md shows each **module** as a single file (variables + resources + outputs together) for readability. Here, we split them into `main.tf`, `variables.tf`, and `outputs.tf` as is standard practice. For the **four modules** (sections 3.2–3.5 below), **only copy the `resource` blocks into `main.tf`** — the variable and output declarations go in the separate files provided below. For **bootstrap** (section 3.1) and the **staging environment** (section 3.6), copy the **entire file** as-is — those instructions call this out explicitly.

### Directory structure

```bash
mkdir -p infra/bootstrap
mkdir -p infra/modules/network
mkdir -p infra/modules/gke
mkdir -p infra/modules/cloudsql
mkdir -p infra/modules/iam
mkdir -p infra/environments/staging
mkdir -p infra/environments/production   # empty for now — populated when you do production later
```

### Update `.gitignore`

Before creating any OpenTofu files, add these entries to your `.gitignore`:

```gitignore
# OpenTofu / Terraform
**/.terraform/          # provider binaries (hundreds of MB — never commit)
**/.terraform.lock.hcl  # lock file (optional to commit — regenerated by tofu init)
*.tfstate               # local state files (shouldn't exist with remote backend, but safety net)
*.tfstate.backup

# Vault
vault-init.json         # contains root token + unseal key — NEVER commit
```

The `.terraform/` directory is created by `tofu init` inside each directory you run it in (bootstrap and staging). It downloads provider plugins (Google, Random) and can be 100+ MB. It's always safe to delete — `tofu init` recreates it.

### How the modules compose

```
  infra/environments/staging/main.tf
  (root configuration — composes all modules)
  │
  ├──► module.network     VPC, subnet, peering, static IP
  │         │
  │         ├── vpc_name, subnet_id ──────────► module.gke
  │         ├── vpc_name, private_vpc_conn ───► module.cloudsql
  │         └── ingress_static_ip ────────────► (output for Phase 2)
  │
  ├──► module.gke         GKE Autopilot cluster
  │         └── cluster_name, endpoint ───────► (output for kubectl)
  │
  ├──► module.cloudsql    Postgres instance + DB + user
  │         └── connection_name, password ────► (output for Phase 2)
  │
  └──► module.iam         Service accounts + WIF
            └── pod_sa, deployer_sa, wif ─────► (output for Phase 2-3)
```

### 3.1 Bootstrap

Create `infra/bootstrap/main.tf` — state bucket, API enablement, and shared Artifact Registry.

The full code is in [K8s.md section 5, `infra/bootstrap/main.tf`](K8s.md#infrabootstrapmaintf). Copy the **entire file** as-is (variables, terraform block, provider, resources, and outputs all in one file).

```
  Bootstrap — the chicken-and-egg step

  ┌─────────────────────┐          ┌──────────────────────┐
  │ infra/bootstrap/    │  creates │   GCS Bucket         │
  │                     │─────────►│   (tofu state)       │
  │ State: LOCAL file   │          │                      │
  │ (terraform.tfstate) │          │   Artifact Registry  │
  │                     │─────────►│   (Docker images)    │
  │ + enables 7 APIs    │          └──────────┬───────────┘
  └─────────────────────┘                     │
                                              │ used by
                                              ▼
                               ┌──────────────────────────┐
                               │ infra/environments/      │
                               │   staging/               │
                               │   production/            │
                               │                          │
                               │ State: REMOTE in bucket  │
                               │ (backend "gcs" { ... })  │
                               └──────────────────────────┘
```

> **Why bootstrap is a single file:** Bootstrap is the chicken-and-egg step — it creates the remote state bucket that all other OpenTofu configurations store their state in. Since the bucket doesn't exist yet, bootstrap uses **local state** (a `terraform.tfstate` file on disk, in `infra/bootstrap/`). It has no `backend.tf`. This is the only directory that stores state locally — staging and production use the GCS bucket that bootstrap creates.

**Key things to note:**
- `disable_on_destroy = false` on APIs — so `tofu destroy` doesn't disable APIs you might use elsewhere
- Artifact Registry lives here because both staging and production share it
- The `cleanup_policies` block auto-deletes old images, keeping only the 10 most recent

### 3.2 Network module

Create these three files in `infra/modules/network/`:

**`main.tf`** — copy only the `resource` blocks from [K8s.md section 5, `infra/modules/network/main.tf`](K8s.md#inframodulesnetworkmaintf) (skip the `variable` and `output` lines — those go in the files below)

**`variables.tf`:**

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
```

**`outputs.tf`:**

```hcl
output "vpc_id" { value = google_compute_network.vpc.id }
output "vpc_name" { value = google_compute_network.vpc.name }
output "subnet_id" { value = google_compute_subnetwork.subnet.id }
output "ingress_static_ip" { value = google_compute_global_address.ingress_ip.address }
output "private_vpc_connection" { value = google_service_networking_connection.private_vpc.id }
```

**What this creates:**

```
                        Network Module
  ┌──────────────────────────────────────────────────┐
  │              VPC: staging-vpc                     │
  │                                                  │
  │  ┌──────────────────────────────────────────┐    │
  │  │  Subnet: staging-subnet (10.0.0.0/20)   │    │
  │  │                                          │    │
  │  │  Secondary ranges:                       │    │
  │  │    pods:     10.1.0.0/16  ──► GKE pods   │    │
  │  │    services: 10.2.0.0/20  ──► GKE svcs   │    │
  │  └──────────────────────────────────────────┘    │
  │                                                  │
  │  ┌──────────────────┐  ┌─────────────────────┐  │
  │  │ Private IP range │  │ Static IP           │  │
  │  │ (for Cloud SQL   │  │ (for Ingress LB)    │  │
  │  │  VPC peering)    │  │  34.x.x.x           │  │
  │  └────────┬─────────┘  └─────────────────────┘  │
  │           │                                      │
  └───────────┼──────────────────────────────────────┘
              │ peering
              ▼
     Google-managed network
     (Cloud SQL private access)
```

### 3.3 GKE module

Create three files in `infra/modules/gke/`:

**`main.tf`** — copy only the `resource` blocks from [K8s.md section 5, `infra/modules/gke/main.tf`](K8s.md#inframodulesgkemaintf) (skip `variable` and `output` lines)

**`variables.tf`:**

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
variable "network" { type = string }
variable "subnetwork" { type = string }
```

**`outputs.tf`:**

```hcl
output "cluster_name" { value = google_container_cluster.autopilot.name }
output "cluster_endpoint" { value = google_container_cluster.autopilot.endpoint }
output "cluster_ca_certificate" {
  value     = google_container_cluster.autopilot.master_auth[0].cluster_ca_certificate
  sensitive = true
}
```

**What this creates:**

```
         GKE Module

  Your laptop                     GKE Autopilot "staging"
  ┌──────────┐    public API    ┌──────────────────────────┐
  │ kubectl  │──────────────────│  Control plane           │
  └──────────┘    endpoint      │  (Google-managed)        │
                                ├──────────────────────────┤
                                │  Worker nodes            │
                                │  (private IPs only)      │
                                │  (auto-scaled on demand) │
                                │                          │
                                │  Workload Identity: ON   │
                                └──────────────────────────┘
```

- Private nodes (no public IPs on workers)
- Public API endpoint (so you can `kubectl` from your laptop)
- Workload Identity enabled (for Cloud SQL proxy auth)

### 3.4 Cloud SQL module

Create three files in `infra/modules/cloudsql/`:

**`main.tf`** — copy only the `resource` blocks from [K8s.md section 5, `infra/modules/cloudsql/main.tf`](K8s.md#inframodulescloudsqlmaintf) (skip `variable` and `output` lines)

**`variables.tf`:**

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
variable "tier" { type = string }
variable "availability" { type = string }
variable "disk_size_gb" { type = number }
variable "vpc_name" { type = string }
variable "private_vpc_connection" {
  type        = string
  description = "ID of the google_service_networking_connection (creates implicit dependency)"
}
```

**`outputs.tf`:**

```hcl
output "instance_connection_name" {
  value = google_sql_database_instance.main.connection_name
}
output "db_password" {
  value     = random_password.db_password.result
  sensitive = true
}
output "db_user" {
  value = google_sql_user.app.name
}
```

**What this creates:**

```
         Cloud SQL Module

  ┌─────────────────────────────────────┐
  │  Cloud SQL Instance "staging"       │
  │  PostgreSQL 15 · private IP only    │
  │                                     │
  │  ┌───────────────────────────────┐  │
  │  │  DB: registry                │  │
  │  │  User: registry-api          │  │
  │  │  Pass: (auto-gen 32 chars)   │  │
  │  └───────────────────────────────┘  │
  │                                     │
  │  Backups: daily @ 03:00 UTC         │
  │  Tier: db-f1-micro (staging)        │
  └─────────────────────────────────────┘
```

### 3.5 IAM module

Create three files in `infra/modules/iam/`:

**`main.tf`** — copy only the `resource` blocks from [K8s.md section 5, `infra/modules/iam/main.tf`](K8s.md#inframodulesiammaintf) (skip `variable` and `output` lines)

**`variables.tf`:**

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "github_repo" { type = string }
variable "environment" { type = string }
variable "k8s_namespace" {
  type    = string
  default = "registry-api"
}
variable "k8s_service_account" {
  type    = string
  default = "registry-api"
}
```

**`outputs.tf`:**

```hcl
output "pod_service_account_email" {
  value = google_service_account.pod_sa.email
}
output "github_deployer_email" {
  value = google_service_account.github_deployer.email
}
output "workload_identity_provider" {
  value = google_iam_workload_identity_pool_provider.github.name
}
```

**What this creates:**

```
         IAM Module — two identity chains

  1. Pod Identity (app talks to Cloud SQL)
  ┌──────────────┐  Workload   ┌────────────────────────┐  IAM    ┌───────────┐
  │ K8s Service  │  Identity   │ GCP Service Account    │ binding │ Cloud SQL │
  │ Account      │────────────►│ registry-api-staging   │────────►│ Client    │
  │ registry-api │  binding    │                        │  role   │ role      │
  └──────────────┘             └────────────────────────┘         └───────────┘

  2. CI/CD Identity (GitHub Actions deploys to GKE)
  ┌──────────────┐  WIF/OIDC  ┌────────────────────────┐  IAM    ┌───────────┐
  │ GitHub       │  Federation │ GCP Service Account    │ binding │ GKE Dev   │
  │ Actions      │────────────►│ github-deployer-stg    │────────►│ AR Writer │
  │ (your repo)  │  pool       │                        │  roles  │           │
  └──────────────┘             └────────────────────────┘         └───────────┘
```

### 3.6 Staging environment

Create four files in `infra/environments/staging/`:

**`main.tf`** — copy the **entire file** from [K8s.md section 5, `infra/environments/staging/main.tf`](K8s.md#infraenvironmentsstagingmaintf) (this is the root configuration that composes all modules — it includes the `terraform` block, `provider` block, `module` blocks, and `output` blocks, all of which are needed)

**`variables.tf`:**

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
variable "github_repo" { type = string }
variable "db_tier" { type = string }
variable "db_availability" { type = string }
variable "db_disk_size_gb" { type = number }
```

**`terraform.tfvars`** — edit with YOUR values:

```hcl
project_id  = "YOUR_PROJECT_ID"              # ← replace
region      = "europe-west2"                  # London (closest to Dublin)
environment = "staging"
github_repo = "YOUR_ORG/clojure-k8s-starter" # ← replace

# Cloud SQL — small for staging (~$10/month)
db_tier         = "db-f1-micro"    # shared CPU, 614MB RAM
                                   # If unavailable, try "db-g1-small" or run:
                                   # gcloud sql tiers list --project=YOUR_PROJECT_ID
db_availability = "ZONAL"          # single zone, no failover (fine for staging)
db_disk_size_gb = 10               # minimum SSD size
```

**`backend.tf`:**

```hcl
terraform {
  backend "gcs" {
    bucket = "YOUR_PROJECT_ID-tofu-state"   # ← must match bootstrap output
    prefix = "staging"
  }
}
```

### File count check

You should now have **17 files** across the infra directory:

```
infra/
├── bootstrap/
│   └── main.tf                                    (1)
├── modules/
│   ├── network/main.tf, variables.tf, outputs.tf  (3)
│   ├── gke/main.tf, variables.tf, outputs.tf      (3)
│   ├── cloudsql/main.tf, variables.tf, outputs.tf  (3)
│   └── iam/main.tf, variables.tf, outputs.tf       (3)
└── environments/
    └── staging/main.tf, variables.tf, terraform.tfvars, backend.tf  (4)
                                                   ──
                                              Total: 17 files
```

Verify:

```bash
find infra -name '*.tf' -o -name '*.tfvars' | wc -l
# Should print 17
```

---

## 4. Step 3 — Bootstrap

This creates the GCS state bucket and Artifact Registry. Must be done before everything else.

> **Navigation note:** All `cd` commands in this guide are relative to the **project root** (`clojure-k8s-starter/`). If you've `cd`'d into a subdirectory in a previous step, run `cd` back to the project root first.

```bash
cd infra/bootstrap

# Initialise (downloads the Google provider)
tofu init

# Preview
tofu plan -var="project_id=YOUR_PROJECT_ID" -var="region=europe-west2"

# You should see:
#   Plan: ~9 resources to add (7 API enablements + 1 bucket + 1 Artifact Registry)

# Apply
tofu apply -var="project_id=YOUR_PROJECT_ID" -var="region=europe-west2"

# Type "yes" when prompted. Takes ~1-2 minutes.
```

**Save the output:**

```bash
tofu output
# state_bucket          = "YOUR_PROJECT_ID-tofu-state"
# artifact_registry_url = "europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api"
```

**Verify the bucket exists:**

```bash
gcloud storage ls gs://YOUR_PROJECT_ID-tofu-state/
```

### If it fails

| Error | Fix |
|-------|-----|
| `API not enabled` | Wait 2 minutes and re-run `tofu apply` — API enablement propagates slowly |
| `Bucket already exists` | Someone already ran bootstrap, or the name collides. Check GCS in the console |
| `Permission denied` | Your `gcloud auth` credentials don't have Owner/Editor on the project |

---

## 5. Step 4 — Provision staging

This creates the GKE cluster, Cloud SQL, VPC, IAM — everything the app needs.

### Before you start

Edit `infra/environments/staging/terraform.tfvars` — replace the placeholder values.

Edit `infra/environments/staging/backend.tf` — replace `YOUR_PROJECT_ID-tofu-state` with the actual bucket name from bootstrap output.

### Run

```bash
cd infra/environments/staging

# Initialise — this does three things:
#   1. Downloads provider plugins (Google, Random) into .terraform/
#   2. Configures the GCS backend (connects to the state bucket from Step 3)
#   3. Initialises the state file so OpenTofu can track what it creates
tofu init

# Preview — READ THIS CAREFULLY
# This shows exactly what OpenTofu will create, change, or destroy.
# Nothing is created yet — this is a dry run.
tofu plan
```

The plan should show **~19 resources** to create:

```
+ module.network.google_compute_network.vpc
+ module.network.google_compute_subnetwork.subnet
+ module.network.google_compute_global_address.private_ip
+ module.network.google_service_networking_connection.private_vpc
+ module.network.google_compute_global_address.ingress_ip
+ module.gke.google_container_cluster.autopilot
+ module.cloudsql.random_password.db_password
+ module.cloudsql.google_sql_database_instance.main
+ module.cloudsql.google_sql_database.registry
+ module.cloudsql.google_sql_user.app
+ module.iam.google_service_account.pod_sa
+ module.iam.google_service_account.github_deployer
+ module.iam.google_project_iam_member.pod_cloudsql
+ module.iam.google_service_account_iam_member.pod_workload_identity
+ module.iam.google_artifact_registry_repository_iam_member.deployer_push
+ module.iam.google_project_iam_member.deployer_gke
+ module.iam.google_iam_workload_identity_pool.github
+ module.iam.google_iam_workload_identity_pool_provider.github
+ module.iam.google_service_account_iam_member.github_wif
```

If the plan looks right:

```bash
tofu apply
# Type "yes"
```

### Timing

This takes **10-15 minutes**. The slow parts:

| Resource | Time |
|----------|------|
| GKE Autopilot cluster | ~8-10 min |
| Cloud SQL instance | ~5-8 min |
| VPC peering | ~1-2 min |
| Everything else | seconds |

GKE and Cloud SQL create in parallel, so total is ~10-15 minutes, not additive.

### Save the outputs

```bash
tofu output
```

Write these down — you'll need them in later phases. Here's what each output is and where it goes:

```
cluster_name               = "staging"
db_instance_connection     = "YOUR_PROJECT:europe-west2:staging"
db_user                    = "registry-api"
db_password                = <sensitive>
ingress_static_ip          = "34.x.x.x"
workload_identity_provider = "projects/123456789/locations/global/workloadIdentityPools/..."
github_deployer_sa         = "github-deployer-staging@YOUR_PROJECT.iam.gserviceaccount.com"
pod_sa_email               = "registry-api-staging@YOUR_PROJECT.iam.gserviceaccount.com"
```

| Output | Used in | What for |
|--------|---------|----------|
| `db_instance_connection` | Phase 2 — `values-staging.yaml` | Cloud SQL proxy sidecar `instanceConnectionName` |
| `db_user` | Phase 2 — K8s Secret | DB credentials for the app |
| `db_password` | Phase 2 — K8s Secret, Phase 4 — Vault | DB credentials for the app |
| `ingress_static_ip` | Phase 2 — verify Ingress | Confirm the load balancer gets the right IP |
| `workload_identity_provider` | Phase 3 — GitHub environment secret | WIF auth for GitHub Actions → GCP |
| `github_deployer_sa` | Phase 3 — GitHub environment secret | Service account for CI/CD |
| `pod_sa_email` | Phase 2 — `values-staging.yaml` | ServiceAccount annotation for Workload Identity |

To see the DB password (it's hidden by default because it's marked `sensitive`):

```bash
tofu output -raw db_password
```

> **Tip:** You don't need to copy these to a file — you can always retrieve them by running `tofu output` again from `infra/environments/staging/`. The values are stored in the remote state bucket.

### If it fails

| Error | Fix |
|-------|-----|
| `Error waiting for GKE cluster` | Re-run `tofu apply` — Autopilot creation is flaky on first try |
| `VPC peering timeout` | Re-run `tofu apply` — the peering has timing dependencies |
| `Quota exceeded` | New GCP projects have low quotas. Request an increase: GCP Console → IAM → Quotas |
| `API not enabled` | Bootstrap API enablement may not have propagated yet. Wait 3 min, re-run |
| `Instance name already in use` | Cloud SQL instance names are reserved for ~1 week after deletion. Change the environment name in tfvars |

**General rule:** If `tofu apply` fails partway through, just re-run it. OpenTofu tracks what was already created and only creates what's missing.

---

## 6. Step 5 — Connect kubectl

`kubectl` needs to know how to reach your GKE cluster. The command below fetches the cluster's API server address and authentication details from GCP, and writes them as a new "context" in your `~/.kube/config` file. After this, `kubectl` can talk to GKE the same way it talks to your local Rancher Desktop cluster — you just switch contexts.

```bash
# Fetch cluster credentials from GCP and add them to ~/.kube/config
gcloud container clusters get-credentials staging \
  --region europe-west2 \
  --project YOUR_PROJECT_ID

# gcloud created a context with a long auto-generated name.
# Rename it to something short and memorable.
kubectl config rename-context \
  gke_YOUR_PROJECT_ID_europe-west2_staging \
  gke-staging

# Switch to the new context
kubectl config use-context gke-staging
```

### Verify

```bash
# Should list system namespaces (default, kube-system, kube-public, gke-managed-system, etc.)
kubectl get namespaces

# Check nodes — on Autopilot, this may show "No resources found"
# That's NORMAL. Autopilot doesn't create nodes until you schedule pods.
# Nodes will appear when you deploy the app in Phase 2.
kubectl get nodes
```

### Your contexts now

```bash
kubectl config get-contexts
```

You should see both — and can switch between them anytime:

```
  ~/.kube/config
  ┌──────────────────────────────────────────────────────────┐
  │                                                          │
  │  Context: rancher-desktop ──► Local K8s (Rancher)        │
  │                                                          │
  │  Context: gke-staging ──────► GKE Autopilot (GCP)   *   │
  │                                          (current)       │
  └──────────────────────────────────────────────────────────┘

  kubectl config use-context rancher-desktop    # switch to local
  kubectl config use-context gke-staging        # switch to GKE
```

---

## 7. Step 6 — Verify everything

Run these checks to confirm all infrastructure is working.

### GKE cluster

```bash
# Should print the K8s API server URL (something like https://34.x.x.x)
# and the CoreDNS URL. If this errors, your kubectl context isn't set correctly.
kubectl cluster-info

# System pods running — you should see pods like kube-dns, metrics-server,
# gke-metadata-server, etc. all in Running state. These confirm the cluster
# is operational.
kubectl get pods -n kube-system
```

### Cloud SQL

```bash
# Instance exists and is RUNNABLE
gcloud sql instances describe staging --format="value(state)"
# Expected: RUNNABLE

# Database exists
gcloud sql databases list --instance=staging --format="table(name)"
# Expected: registry (and postgres, the default)

# User exists
gcloud sql users list --instance=staging --format="table(name)"
# Expected: registry-api (and postgres)
```

### Artifact Registry

```bash
# Repository exists
gcloud artifacts repositories describe registry-api \
  --location=europe-west2 \
  --format="value(name)"
# Expected: projects/YOUR_PROJECT/locations/europe-west2/repositories/registry-api
```

### IAM

```bash
# Pod service account exists
gcloud iam service-accounts describe \
  registry-api-staging@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --format="value(email)"

# GitHub deployer service account exists
gcloud iam service-accounts describe \
  github-deployer-staging@YOUR_PROJECT_ID.iam.gserviceaccount.com \
  --format="value(email)"
```

### Static IP

```bash
# Ingress IP reserved
gcloud compute addresses describe staging-ingress-ip --global --format="value(address)"
# Expected: 34.x.x.x (your static IP)
```

---

## 8. Step 7 — Push a test image

Verify you can build and push a Docker image to Artifact Registry.

```
  Docker image push flow

  ┌────────────┐  docker   ┌────────────┐  docker   ┌─────────────────────┐
  │ Dockerfile │  build    │ Local      │  push     │ Artifact Registry   │
  │ (project   │─────────► │ image      │──────────►│ europe-west2-docker │
  │  root)     │           │ :phase1-   │  (gcloud  │ .pkg.dev/PROJECT/   │
  └────────────┘           │  test      │   creds)  │ registry-api/       │
                           └────────────┘           │ registry-api        │
                                                    └─────────────────────┘
```

Run these commands from the **project root** (where your `Dockerfile` lives):

```bash
# Configure Docker to push to Artifact Registry (one-time)
# This adds a credential helper to ~/.docker/config.json so that `docker push`
# to europe-west2-docker.pkg.dev automatically uses your gcloud credentials.
gcloud auth configure-docker europe-west2-docker.pkg.dev

# Build your app image
docker build -t europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api/registry-api:phase1-test .

# Push it
docker push europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api/registry-api:phase1-test
```

### Verify the image landed

```bash
gcloud artifacts docker images list \
  europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api/registry-api \
  --format="table(package,version)"
```

You should see your `phase1-test` tag.

### Clean up the test image (optional)

```bash
gcloud artifacts docker images delete \
  europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api/registry-api:phase1-test \
  --quiet
```

---

## 9. Troubleshooting

### `tofu init` fails with "backend not found"

You haven't run bootstrap yet. Go back to Step 3.

### `tofu apply` hangs for 20+ minutes

GKE Autopilot creation can be slow on first try. Check the GCP Console → Kubernetes Engine to see if it's still creating. If it's stuck, `Ctrl+C` and re-run `tofu apply`.

### `kubectl get nodes` shows no nodes

Autopilot doesn't create nodes until you schedule pods. This is normal. Nodes appear when you deploy in Phase 2.

### "Error 403: Request had insufficient authentication scopes"

Re-run `gcloud auth application-default login` — your Application Default Credentials may have expired or been created without the necessary scopes. This is the most common auth issue with OpenTofu + GCP.

### Cloud SQL instance name conflict

If you previously created and deleted a `staging` instance, the name is reserved for ~1 week. Either wait, or change `environment = "staging"` to `environment = "stg"` in tfvars.

### VPC peering fails

The `google_service_networking_connection` resource sometimes races with the private IP allocation. Re-run `tofu apply` — the second run succeeds because the IP is already allocated.

### "Quota CPUS_ALL_REGIONS exceeded"

New GCP projects have a default quota of 12 CPUs. GKE Autopilot reserves some. Go to GCP Console → IAM & Admin → Quotas, search for `CPUS`, and request an increase to 24.

---

## 10. Tear down

If you need to tear down everything (e.g., after testing):

```
  Tear-down order (reverse of creation)

  Step 1                    Step 2 (optional)        Step 3
  ┌────────────────────┐    ┌──────────────────┐     ┌──────────────┐
  │ Staging infra      │    │ Bootstrap        │     │ kubectl      │
  │ tofu destroy       │───►│ tofu destroy     │────►│ delete       │
  │ (GKE, SQL, VPC,   │    │ (bucket +        │     │ context      │
  │  IAM — 10 min)     │    │  Artifact Reg)   │     │ gke-staging  │
  └────────────────────┘    └──────────────────┘     └──────────────┘
```

```bash
# All cd commands below are relative to the project root.

# 1. Destroy staging infrastructure
cd infra/environments/staging
tofu destroy
# Type "yes". Takes ~5-10 minutes.

# 2. (Optional) Also destroy bootstrap (state bucket + Artifact Registry)
cd infra/bootstrap   # ← relative to project root, not to staging directory
tofu destroy -var="project_id=YOUR_PROJECT_ID" -var="region=europe-west2"

# 3. Clean up kubectl context
kubectl config delete-context gke-staging
```

**Cost if you forget to tear down:** ~$5-8/day. Set a budget alert: GCP Console → Billing → Budgets.

---

## 11. Checklist — Phase 1 complete

Before moving to Phase 2, confirm all of these:

- [ ] `gcloud config get-value project` returns your project ID
- [ ] `tofu output` in `infra/environments/staging/` shows all outputs without errors (`db_password` showing `<sensitive>` is normal — use `tofu output -raw db_password` to see the actual value)
- [ ] `kubectl config use-context gke-staging` works
- [ ] `kubectl get namespaces` returns system namespaces (e.g., `default`, `kube-system`)
- [ ] `gcloud sql instances describe staging` shows `RUNNABLE`
- [ ] `gcloud artifacts repositories describe registry-api --location=europe-west2` succeeds
- [ ] You successfully pushed and see a Docker image in Artifact Registry
- [ ] You know how to retrieve `tofu output` values (run `tofu output` from `infra/environments/staging/` — you'll need several outputs in Phase 2 and 3)

**Next:** [Phase 2 — Helm chart + deploy to staging](K8s-phase2.md)
