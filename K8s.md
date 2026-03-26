# GKE Deployment Plan — Staging & Production

Deploy registry-api to GKE Autopilot with staging/production environments, OpenTofu for infrastructure, Helm for workloads, and HashiCorp Vault for secrets.

## Table of Contents

1. [Big Picture](#1-big-picture)
2. [Git Flow](#2-git-flow)
3. [Full Example Walkthrough](#3-full-example-walkthrough)
4. [kubectl Context Switching](#4-kubectl-context-switching)
5. [OpenTofu — Infrastructure as Code](#5-opentofu--infrastructure-as-code)
6. [Helm Chart — Application Packaging](#6-helm-chart--application-packaging)
7. [GitHub Actions — CI/CD Pipeline](#7-github-actions--cicd-pipeline)
8. [HashiCorp Vault — Secrets Management](#8-hashicorp-vault--secrets-management)
9. [Cloud SQL & Database](#9-cloud-sql--database)
10. [DNS & Ingress](#10-dns--ingress)
11. [Day-2 Operations](#11-day-2-operations)
12. [Future Infrastructure](#12-future-infrastructure)
13. [Cost: Spin Up, Test, Tear Down](#13-cost-spin-up-test-tear-down)
14. [How Hard Is This?](#14-how-hard-is-this)
15. [Files to Create](#15-files-to-create)
16. [Implementation Phases](#16-implementation-phases)
17. [Open Questions](#17-open-questions)

---

## 1. Big Picture

### How code flows from your laptop to production

```
 You (laptop)
  │
  │  git push
  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        GitHub Actions                               │
│                                                                     │
│  ┌─────────┐    ┌─────────┐    ┌──────────────┐    ┌────────────┐ │
│  │ bb check │───▶│  Docker  │───▶│   Artifact   │───▶│   Helm     │ │
│  │ lint+fmt │    │  build   │    │   Registry   │    │  upgrade   │ │
│  │ +smoke   │    │          │    │  (push image)│    │  --install │ │
│  └─────────┘    └─────────┘    └──────────────┘    └─────┬──────┘ │
│                                                           │        │
└───────────────────────────────────────────────────────────┼────────┘
                                                            │
                        ┌───────────────────────────────────┤
                        │                                   │
                        ▼                                   ▼
              ┌──────────────────┐                ┌──────────────────┐
              │   GKE Autopilot  │                │   GKE Autopilot  │
              │     STAGING      │                │   PRODUCTION     │
              │                  │                │                  │
              │  ┌────────────┐  │                │  ┌────────────┐  │
              │  │registry-api│  │                │  │registry-api│  │
              │  │  (2 pods)  │  │                │  │  (2 pods)  │  │
              │  │  + sql     │  │                │  │  + sql     │  │
              │  │    proxy   │  │                │  │    proxy   │  │
              │  └─────┬──────┘  │                │  └─────┬──────┘  │
              │        │         │                │        │         │
              │  ┌─────┴──────┐  │                │  ┌─────┴──────┐  │
              │  │   Vault    │  │                │  │   Vault    │  │
              │  │  (secrets) │  │                │  │  (secrets) │  │
              │  └────────────┘  │                │  └────────────┘  │
              │        │         │                │        │         │
              │     Ingress      │                │     Ingress      │
              │     (GCE LB)     │                │     (GCE LB)     │
              └────────┼─────────┘                └────────┼─────────┘
                       │                                   │
                       ▼                                   ▼
              ┌──────────────────┐                ┌──────────────────┐
              │    Cloud SQL     │                │    Cloud SQL     │
              │    (staging)     │                │   (production)   │
              └──────────────────┘                └──────────────────┘
```

### What manages what

```
┌────────────────────────────────────────────────────────┐
│                    OpenTofu manages                     │
│              (infrastructure layer)                     │
│                                                        │
│  GKE Autopilot clusters (staging + prod)               │
│  Cloud SQL instances (staging + prod)                  │
│  Artifact Registry (shared, in bootstrap)              │
│  VPC network & subnets                                 │
│  IAM service accounts & bindings                       │
│  Workload Identity Federation (GitHub OIDC)            │
│  Static IP addresses                                   │
└────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────┐
│                     Helm manages                       │
│               (application layer)                      │
│                                                        │
│  Deployments (registry-api pods)                       │
│  Services (ClusterIP)                                  │
│  Ingress (load balancer config)                        │
│  ConfigMaps (app configuration)                        │
│  ServiceAccounts (Workload Identity binding)           │
│  HashiCorp Vault (cluster, injector, secrets engines)  │
│  Grafana LGTM stack (Loki, Grafana, Tempo, Mimir)     │
│  OpenTelemetry Collector + Alloy (log collection)      │
│  RabbitMQ (bitnami/rabbitmq, message broker)             │
│  Redis (bitnami/redis, caching + idempotency)              │
└────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────┐
│                GitHub Actions manages                   │
│                  (deployment layer)                     │
│                                                        │
│  Build & test (bb check)                               │
│  Docker build + push                                   │
│  Helm upgrade to staging (auto on push to main)        │
│  Helm upgrade to production (manual approval)          │
└────────────────────────────────────────────────────────┘
```

### Key components

| Component | What | Why |
|-----------|------|-----|
| **GKE Autopilot** | Managed K8s, Google handles nodes | No node management, pay per pod |
| **Cloud SQL** | Managed PostgreSQL | Automated backups, patching, HA |
| **Cloud SQL Auth Proxy** | Sidecar in each pod | Secure DB connection via IAM |
| **Artifact Registry** | Private Docker images | Fast pulls from GKE, stays in GCP |
| **OpenTofu** | Open-source Terraform fork | Declarative infra, version-controlled |
| **Helm** | K8s package manager | Template manifests per env, rollback |
| **Vault** | Secret management | Encrypted secrets, audit log, dynamic creds |
| **Workload Identity** | OIDC auth for GitHub Actions | No GCP keys in GitHub |

---

## 2. Git Flow

### Branch strategy

```
main (protected)
  │
  ├── feature/add-transfer-endpoint     ← your work happens here
  │     │
  │     │  Pull Request (triggers bb check)
  │     │
  │     ├──▶ merged to main
  │              │
  │              ├──▶ auto-deploy to STAGING
  │              │
  │              └──▶ manual approval ──▶ deploy to PRODUCTION
  │
  ├── feature/fix-projection-bug
  │     │
  │     └──▶ ... same flow
  │
  └── infra/add-rabbitmq                ← infrastructure changes
        │
        └──▶ ... same flow (but OpenTofu changes need tofu plan/apply)
```

### Rules

- **`main` is always deployable** — every commit on main auto-deploys to staging
- **Feature branches** for all work — never push directly to main
- **Pull Requests required** — `bb check` runs on every PR
- **Production deploys require manual approval** in GitHub Actions
- **Infrastructure changes** (OpenTofu) go through the same PR flow but need a separate `tofu apply` step (see section 5)

### Exact git commands for a feature

```bash
# 1. Start from main
git checkout main
git pull origin main

# 2. Create feature branch
git checkout -b feature/add-transfer-endpoint

# 3. Do your work
#    ... edit files ...

# 4. Commit
git add src/modules/bank/use_cases/transfer.clj
git commit -m "Add transfer endpoint with idempotency"

# 5. Push branch
git push -u origin feature/add-transfer-endpoint

# 6. Create PR (GitHub CLI)
gh pr create \
  --title "Add transfer endpoint" \
  --body "Adds POST /api/transfers with idempotency key support"

# 7. Wait for bb check to pass on the PR
gh pr checks feature/add-transfer-endpoint --watch

# 8. Merge (after review / checks pass)
gh pr merge feature/add-transfer-endpoint --squash --delete-branch

# 9. This merge to main triggers GitHub Actions:
#    → auto-deploy to staging
#    → wait for manual approval → deploy to production
```

---

## 3. Full Example Walkthrough

The complete flow from zero infrastructure to a running service, then tearing it all down.

### Prerequisites

```bash
# Install tools (macOS)
brew install opentofu        # Infrastructure as code
brew install helm            # K8s package manager
brew install jq              # JSON parsing (used by Vault scripts)
brew tap hashicorp/tap && brew install hashicorp/tap/vault  # Vault CLI (Phase 4)
brew install --cask google-cloud-sdk  # GCP CLI

# Authenticate to GCP
gcloud auth login
gcloud auth application-default login

# Set your project (replace with your project ID)
gcloud config set project my-project-id
```

### Step 1: Bootstrap OpenTofu state bucket

```bash
# This creates the GCS bucket that stores OpenTofu state.
# It must exist before any other infrastructure.

cd infra/bootstrap
tofu init
tofu apply -var="project_id=my-project-id" -var="region=europe-west2"

# Output:
#   state_bucket           = "my-project-id-tofu-state"
#   artifact_registry_url  = "europe-west2-docker.pkg.dev/my-project-id/registry-api"
```

### Step 2: Provision staging infrastructure

```bash
cd infra/environments/staging

# Edit terraform.tfvars first — set your project_id and github_repo

# Initialise (downloads providers, configures state backend)
tofu init

# Preview what will be created (always do this first)
tofu plan

# You'll see ~19 resources to create:
#   + google_container_cluster.autopilot    (GKE Autopilot)
#   + google_sql_database_instance.main     (Cloud SQL)
#   + google_sql_database.registry          (database)
#   + google_sql_user.app                   (DB user)
#   + google_compute_network.vpc            (VPC)
#   + google_compute_subnetwork.subnet      (subnet)
#   + google_compute_global_address (x2)    (static IPs)
#   + google_service_account (x2)           (pod SA + CI SA)
#   + google_iam_* (x4+)                   (IAM bindings + WIF)

# Create everything (~10-15 minutes — GKE and Cloud SQL are the slow parts)
tofu apply

# Save the outputs — you'll need them for GitHub secrets and Helm values
tofu output
#   cluster_name               = "staging"
#   db_instance_connection     = "my-project:europe-west2:staging"
#   db_password                = <sensitive>   (use: tofu output -raw db_password)
#   ingress_static_ip          = "34.x.x.x"
#   workload_identity_provider = "projects/123456/locations/global/..."
#   github_deployer_sa         = "github-deployer-staging@my-project.iam.gserviceaccount.com"
#   pod_sa_email               = "registry-api-staging@my-project.iam.gserviceaccount.com"
# (artifact_registry_url comes from bootstrap: tofu output -state=infra/bootstrap)
```

### Step 3: Connect kubectl to the new cluster

```bash
# Get credentials (adds a new context to ~/.kube/config)
gcloud container clusters get-credentials staging \
  --region europe-west2 \
  --project my-project-id

# Rename the context to something short
kubectl config rename-context \
  gke_my-project-id_europe-west2_staging \
  gke-staging

# Verify you're connected
kubectl config use-context gke-staging
kubectl get namespaces
```

### Step 4: Install Vault

```bash
# Add HashiCorp Helm repo
helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo update

# Install Vault
helm install vault hashicorp/vault \
  -n vault --create-namespace \
  -f vault-values.yaml

# Wait for Vault pod to be running (not ready — it starts sealed)
kubectl -n vault wait --for=jsonpath='{.status.phase}'=Running pod/vault-0 --timeout=120s

# ──────────────────────────────────────────────────────────────────
# IMPORTANT: The next command outputs unseal keys and a root token.
# These are the ONLY way to access Vault. If you lose them, you lose
# access to all secrets. Save the output file somewhere secure
# (e.g., a password manager), then delete it from disk.
# ──────────────────────────────────────────────────────────────────

# Initialise Vault (first time only)
kubectl -n vault exec vault-0 -- vault operator init \
  -key-shares=1 -key-threshold=1 -format=json > vault-init.json

# Unseal Vault (required after every pod restart)
UNSEAL_KEY=$(jq -r '.unseal_keys_b64[0]' vault-init.json)
kubectl -n vault exec vault-0 -- vault operator unseal "$UNSEAL_KEY"

# Configure Vault (K8s auth, policies, seed DB credentials from OpenTofu)
VAULT_TOKEN=$(jq -r '.root_token' vault-init.json)
./scripts/vault-configure.sh staging "$VAULT_TOKEN"

# Move the keys file somewhere secure, then delete from disk
# cp vault-init.json ~/SECURE_LOCATION/
# rm vault-init.json
```

### Step 5: Deploy the application

```bash
# Configure Docker to push to Artifact Registry (one-time)
gcloud auth configure-docker europe-west2-docker.pkg.dev

# Build and push the Docker image
docker build -t europe-west2-docker.pkg.dev/my-project-id/registry-api/registry-api:test1 .
docker push europe-west2-docker.pkg.dev/my-project-id/registry-api/registry-api:test1

# Deploy with Helm
helm upgrade --install registry-api helm/registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag=test1 \
  -n registry-api --create-namespace \
  --wait --timeout 5m

# Watch pods come up (Ctrl+C when both are Running)
kubectl get pods -n registry-api -w

# Check the Ingress (takes 2-5 minutes for the load balancer to provision)
kubectl get ingress -n registry-api

# Test it once the Ingress has an IP
STAGING_IP=$(kubectl get ingress registry-api -n registry-api \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')
curl http://$STAGING_IP/api/ping
```

### Step 6: Play around

```bash
# Tail logs from all pods
kubectl logs -l app=registry-api -n registry-api -f --tail=20

# Hit endpoints
curl http://$STAGING_IP/health
curl http://$STAGING_IP/ready
curl http://$STAGING_IP/openapi.json

# Check Vault is serving secrets
kubectl -n vault exec vault-0 -- vault kv get secret/staging/registry-api/db

# Scale up temporarily
kubectl scale deployment registry-api -n registry-api --replicas=3
kubectl get pods -n registry-api

# Check resource usage (may take a minute to show data)
kubectl top pods -n registry-api
```

### Step 7: Tear everything down

```bash
# 1. Remove Helm releases (order doesn't matter)
helm uninstall registry-api -n registry-api
helm uninstall vault -n vault

# 2. Destroy all GCP infrastructure
cd infra/environments/staging
tofu destroy
# Type "yes" when prompted. Takes ~5-10 minutes.
# Removes: GKE cluster, Cloud SQL, VPC, static IPs, IAM

# 3. (Optional) Also remove the state bucket and Artifact Registry
cd infra/bootstrap
tofu destroy -var="project_id=my-project-id" -var="region=europe-west2"

# 4. Clean up the kubectl context
kubectl config delete-context gke-staging
```

### Step 8: Set up production (when ready)

Identical to steps 2-5, substituting `production` for `staging`:

```bash
cd infra/environments/production
tofu init && tofu plan && tofu apply

gcloud container clusters get-credentials production \
  --region europe-west2 --project my-project-id
kubectl config rename-context \
  gke_my-project-id_europe-west2_production gke-production

# Install Vault, configure it, deploy app — same steps, different values files:
#   vault-values.yaml (same config, install in production cluster)
#   helm/registry-api/values-prod.yaml
```

---

## 4. kubectl Context Switching

### Three contexts

```
┌──────────────────────────────────────────────────────────────────┐
│                        ~/.kube/config                            │
│                                                                  │
│  ┌──────────────────┐  ┌─────────────────┐  ┌────────────────┐  │
│  │  rancher-desktop  │  │   gke-staging    │  │ gke-production │  │
│  │                  │  │                 │  │                │  │
│  │  Local dev       │  │  Staging env    │  │  Production    │  │
│  │  Your laptop     │  │  GKE Autopilot  │  │  GKE Autopilot │  │
│  └──────────────────┘  └─────────────────┘  └────────────────┘  │
│         ▲                                                        │
│    current context                                               │
└──────────────────────────────────────────────────────────────────┘
```

### Commands

```bash
kubectl config get-contexts          # List all contexts
kubectl config current-context       # Which am I on?
kubectl config use-context rancher-desktop    # Local
kubectl config use-context gke-staging        # Staging
kubectl config use-context gke-production     # Production
```

### Proposed bb tasks

```bash
bb ctx              # Show current context
bb ctx-local        # Switch to rancher-desktop
bb ctx-staging      # Switch to gke-staging
bb ctx-prod         # Switch to gke-production
```

### How GKE contexts get added

After `tofu apply` creates each cluster, you run once per cluster:

```bash
gcloud container clusters get-credentials staging \
  --region europe-west2 --project PROJECT_ID
kubectl config rename-context gke_PROJECT_ID_europe-west2_staging gke-staging

gcloud container clusters get-credentials production \
  --region europe-west2 --project PROJECT_ID
kubectl config rename-context gke_PROJECT_ID_europe-west2_production gke-production
```

This adds GKE contexts alongside your existing `rancher-desktop` context. Nothing is modified or removed.

### Environment differences

| Setting | Local (Rancher) | Staging | Production |
|---------|----------------|---------|------------|
| Cluster | Rancher Desktop | GKE Autopilot | GKE Autopilot |
| Region | N/A | europe-west2 (London) | europe-west2 (London) |
| Namespace | `default` | `registry-api` | `registry-api` |
| Replicas | 2 | 2 | 2+ |
| Database | Local PostgreSQL | Cloud SQL (micro) | Cloud SQL (dedicated) |
| Secrets | Env vars / local | Vault | Vault |
| Cloud SQL Proxy | No | Yes (sidecar) | Yes (sidecar) |
| Ingress | No | Yes | Yes + TLS |
| Image source | Local Docker | Artifact Registry | Artifact Registry |
| Deploy method | `bb k8s-deploy` | Auto on merge to main | Manual approval |

### Local vs GKE — what changes, what stays the same

Your existing `k8s/` folder and `bb k8s-*` tasks are **unchanged** — they keep working for local Rancher Desktop development using flat YAML manifests. The Helm chart is a separate set of files used exclusively for GKE environments. You don't have to choose one or the other; they coexist.

---

## 5. OpenTofu — Infrastructure as Code

### What is OpenTofu?

OpenTofu is the open-source fork of Terraform. You write `.tf` files that declare "I want this infrastructure to exist." OpenTofu figures out how to create, update, or delete resources to match.

```
You write:                    OpenTofu does:
─────────                     ──────────────

"I want a GKE cluster"   →   gcloud container clusters create ...
"I want Cloud SQL"        →   gcloud sql instances create ...
"I want an IAM binding"   →   gcloud iam ... add-iam-policy-binding ...

Change a setting?         →   Updates only what changed
tofu destroy?             →   Deletes everything it created, in the right order
```

### Why not a shell script?

| Shell script | OpenTofu |
|-------------|----------|
| "Create this, then that" (imperative) | "This should exist" (declarative) |
| Re-running may fail or duplicate | Re-run safely anytime |
| No state tracking | Knows what exists |
| Hard to tear down | `tofu destroy` removes everything |
| No dependency ordering | Auto-orders operations |
| No drift detection | `tofu plan` shows what changed |

### Directory structure

```
infra/
├── bootstrap/
│   └── main.tf                          # State bucket + API enablement + Artifact Registry
│
├── modules/
│   ├── network/                         # VPC, subnets, static IPs
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── gke/                             # GKE Autopilot cluster
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── cloudsql/                        # Cloud SQL PostgreSQL
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   └── iam/                             # Service accounts + WIF
│       ├── main.tf
│       ├── variables.tf
│       └── outputs.tf
│
└── environments/
    ├── staging/
    │   ├── main.tf                      # Composes modules
    │   ├── variables.tf
    │   ├── terraform.tfvars             # Staging values
    │   └── backend.tf                   # Remote state in GCS
    └── production/
        ├── main.tf                      # Same structure, different tfvars
        ├── variables.tf
        ├── terraform.tfvars
        └── backend.tf
```

### How modules compose

```
infra/environments/staging/main.tf
    │
    │  uses
    ├──────▶ module "network"            → VPC + subnets + static IPs
    │
    ├──────▶ module "gke"                → GKE Autopilot cluster
    │            depends on network       (waits for VPC to exist)
    │
    ├──────▶ module "cloudsql"           → Cloud SQL + database + user
    │            depends on network       (waits for VPC peering,
    │                                      which can take 30+ seconds)
    │
    └──────▶ module "iam"               → Service accounts + WIF

(Artifact Registry is in bootstrap, not per-environment — see section above)
```

OpenTofu resolves these dependencies automatically — you don't need to run modules in order.

### The code

Each module below will become its own `.tf` file. Comments explain what each resource does.

#### `infra/bootstrap/main.tf`

Run once before anything else. Creates the GCS bucket for storing OpenTofu state and the shared Artifact Registry (used by both staging and production).

```hcl
variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "europe-west2"
}

terraform {
  required_version = ">= 1.6.0"   # minimum OpenTofu version

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.44"          # pin to minor version for stability
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# Enable the GCP APIs we need (idempotent — safe to re-run)
resource "google_project_service" "apis" {
  for_each = toset([
    "container.googleapis.com",           # GKE
    "sqladmin.googleapis.com",            # Cloud SQL
    "artifactregistry.googleapis.com",    # Artifact Registry
    "iam.googleapis.com",                 # IAM
    "iamcredentials.googleapis.com",      # Workload Identity
    "compute.googleapis.com",             # VPC / networking
    "servicenetworking.googleapis.com",   # Private services (Cloud SQL)
  ])

  service            = each.value
  disable_on_destroy = false   # don't disable APIs on tofu destroy
}

# GCS bucket for OpenTofu state files
resource "google_storage_bucket" "tofu_state" {
  name     = "${var.project_id}-tofu-state"
  location = var.region

  versioning {
    enabled = true   # keep history of state — allows recovery
  }

  uniform_bucket_level_access = true
}

output "state_bucket" {
  value = google_storage_bucket.tofu_state.name
}

# Artifact Registry — shared across environments (both staging and production
# push/pull from the same repo). Created here in bootstrap, not per-environment.
resource "google_artifact_registry_repository" "registry_api" {
  location      = var.region
  repository_id = "registry-api"
  format        = "DOCKER"
  project       = var.project_id

  cleanup_policy_dry_run = false

  cleanup_policies {
    id     = "keep-recent"
    action = "KEEP"

    most_recent_versions {
      keep_count = 10
    }
  }

  depends_on = [google_project_service.apis]
}

output "artifact_registry_url" {
  value = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.registry_api.repository_id}"
}
```

#### `infra/modules/network/main.tf`

Creates the VPC, subnet, Cloud SQL private peering, and a static IP for the Ingress.

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }

# VPC — one per environment for isolation
resource "google_compute_network" "vpc" {
  name                    = "${var.environment}-vpc"
  auto_create_subnetworks = false
  project                 = var.project_id
}

# Subnet with secondary ranges for GKE pods and services
resource "google_compute_subnetwork" "subnet" {
  name          = "${var.environment}-subnet"
  ip_cidr_range = "10.0.0.0/20"          # primary range: node IPs
  region        = var.region
  network       = google_compute_network.vpc.id

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.4.0.0/14"        # ~260k pod IPs
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.8.0.0/20"        # ~4k service IPs
  }
}

# Reserve an internal IP range for Cloud SQL private access.
# Cloud SQL will get a private IP inside the VPC instead of a public one.
resource "google_compute_global_address" "private_ip" {
  name          = "${var.environment}-private-ip"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.vpc.id
}

# Connect the VPC to Google's internal services network.
# This is what allows Cloud SQL to have a private IP.
# Can take 30+ seconds to complete.
resource "google_service_networking_connection" "private_vpc" {
  network                 = google_compute_network.vpc.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip.name]
}

# Static IP for the Ingress load balancer.
# Reserved so it doesn't change across deploys.
resource "google_compute_global_address" "ingress_ip" {
  name    = "${var.environment}-ingress-ip"
  project = var.project_id
}

output "vpc_id" { value = google_compute_network.vpc.id }
output "vpc_name" { value = google_compute_network.vpc.name }
output "subnet_id" { value = google_compute_subnetwork.subnet.id }
output "ingress_static_ip" { value = google_compute_global_address.ingress_ip.address }
output "private_vpc_connection" { value = google_service_networking_connection.private_vpc.id }
```

#### `infra/modules/gke/main.tf`

Creates the GKE Autopilot cluster.

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
variable "network" { type = string }
variable "subnetwork" { type = string }

resource "google_container_cluster" "autopilot" {
  name     = var.environment
  location = var.region
  project  = var.project_id

  # Autopilot mode — Google manages the nodes entirely.
  # You only define pods; Google provisions nodes to fit them.
  enable_autopilot = true

  network    = var.network
  subnetwork = var.subnetwork

  # Workload Identity lets K8s service accounts act as GCP service accounts.
  # This is how the Cloud SQL proxy authenticates without passwords.
  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  # REGULAR channel: stable but reasonably current K8s version
  release_channel {
    channel = "REGULAR"
  }

  # Private cluster: worker nodes don't get public IPs.
  # The API server stays public so you can kubectl from your laptop.
  # master_ipv4_cidr_block is optional for Autopilot (omit to let GCP choose).
  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = false
  }

  # Allow kubectl access from anywhere.
  # Tighten this to your IP range later for production.
  master_authorized_networks_config {
    cidr_blocks {
      cidr_block   = "0.0.0.0/0"
      display_name = "All"
    }
  }

  # Tell GKE which subnet ranges to use for pods and services
  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  deletion_protection = false   # allows tofu destroy
}

output "cluster_name" { value = google_container_cluster.autopilot.name }
output "cluster_endpoint" { value = google_container_cluster.autopilot.endpoint }
output "cluster_ca_certificate" {
  value     = google_container_cluster.autopilot.master_auth[0].cluster_ca_certificate
  sensitive = true
}
```

#### `infra/modules/cloudsql/main.tf`

Creates the Cloud SQL PostgreSQL instance, database, and application user.

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
variable "tier" { type = string }              # e.g. "db-f1-micro"
variable "availability" { type = string }      # "ZONAL" or "REGIONAL"
variable "disk_size_gb" { type = number }
variable "vpc_name" { type = string }          # VPC name for private networking
variable "private_vpc_connection" {
  type        = string
  description = "ID of the google_service_networking_connection (creates implicit dependency)"
}

# Auto-generate a strong password for the DB user
resource "random_password" "db_password" {
  length  = 32
  special = false   # avoid shell-escaping issues
}

resource "google_sql_database_instance" "main" {
  name             = var.environment
  database_version = "POSTGRES_15"
  region           = var.region
  project          = var.project_id

  # The implicit dependency via var.private_vpc_connection (passed from
  # module.network.private_vpc_connection) ensures OpenTofu creates the
  # VPC peering before this instance. No explicit depends_on needed.

  settings {
    tier              = var.tier
    availability_type = var.availability
    disk_size         = var.disk_size_gb
    disk_type         = "PD_SSD"

    ip_configuration {
      ipv4_enabled    = false   # no public IP — only accessible via VPC
      private_network = "projects/${var.project_id}/global/networks/${var.vpc_name}"
    }

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = var.availability == "REGIONAL"
      start_time                     = "03:00"   # 3am UTC
    }
  }

  deletion_protection = false   # allows tofu destroy
}

resource "google_sql_database" "registry" {
  name     = "registry"
  instance = google_sql_database_instance.main.name
}

resource "google_sql_user" "app" {
  name     = "registry-api"
  instance = google_sql_database_instance.main.name
  password = random_password.db_password.result
}

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

#### Artifact Registry

**Note:** Artifact Registry is created in `infra/bootstrap/main.tf` (not per-environment) because both staging and production share the same image repository. See the bootstrap section above for the code.

#### `infra/modules/iam/main.tf`

Creates service accounts for pods and CI/CD, plus Workload Identity Federation for GitHub Actions.

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "github_repo" { type = string }   # e.g. "your-org/clojure-k8s-starter"
variable "environment" { type = string }
variable "k8s_namespace" {                 # must match the Helm deploy namespace
  type    = string
  default = "registry-api"
}
variable "k8s_service_account" {           # must match the Helm ServiceAccount name
  type    = string
  default = "registry-api"
}

# ── Pod service account (Workload Identity) ──────────────────────
# The GCP service account that pods impersonate.
# Named per-environment so staging and prod have separate permissions.

resource "google_service_account" "pod_sa" {
  account_id   = "registry-api-${var.environment}"
  display_name = "registry-api pod SA (${var.environment})"
  project      = var.project_id
}

# Pod SA can connect to Cloud SQL
resource "google_project_iam_member" "pod_cloudsql" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.pod_sa.email}"
}

# Bind the K8s service account to the GCP service account.
# This means: "pods running as K8s SA 'registry-api' in namespace
# 'registry-api' can act as this GCP SA."
resource "google_service_account_iam_member" "pod_workload_identity" {
  service_account_id = google_service_account.pod_sa.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[${var.k8s_namespace}/${var.k8s_service_account}]"
}

# ── GitHub Actions deployer (Workload Identity Federation) ───────
# A separate GCP SA used by CI/CD to push images and deploy.

resource "google_service_account" "github_deployer" {
  account_id   = "github-deployer-${var.environment}"
  display_name = "GitHub Actions deployer (${var.environment})"
  project      = var.project_id
}

# Deployer can push Docker images
resource "google_artifact_registry_repository_iam_member" "deployer_push" {
  location   = var.region
  repository = "registry-api"
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.github_deployer.email}"
  project    = var.project_id
}

# Deployer can manage GKE workloads (helm upgrade, kubectl)
resource "google_project_iam_member" "deployer_gke" {
  project = var.project_id
  role    = "roles/container.developer"
  member  = "serviceAccount:${google_service_account.github_deployer.email}"
}

# ── Workload Identity Federation (GitHub OIDC → GCP) ────────────
# This lets GitHub Actions authenticate to GCP without storing keys.
# GitHub mints a short-lived OIDC token; GCP validates it and issues
# temporary credentials.

resource "google_iam_workload_identity_pool" "github" {
  workload_identity_pool_id = "github-actions-${var.environment}"
  display_name              = "GitHub Actions (${var.environment})"
  project                   = var.project_id
}

resource "google_iam_workload_identity_pool_provider" "github" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.github.workload_identity_pool_id
  workload_identity_pool_provider_id = "github-oidc"
  display_name                       = "GitHub OIDC"
  project                            = var.project_id

  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.actor"      = "assertion.actor"
    "attribute.repository" = "assertion.repository"
  }

  # Only allow tokens from YOUR repository
  attribute_condition = "assertion.repository == '${var.github_repo}'"

  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

# Allow the GitHub OIDC identity to impersonate the deployer SA
resource "google_service_account_iam_member" "github_wif" {
  service_account_id = google_service_account.github_deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github.name}/attribute.repository/${var.github_repo}"
}

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

**Note on naming:** All GCP resources are environment-scoped (`registry-api-staging`, `github-deployer-staging`, `github-actions-staging`) so staging and production don't collide within the same project. The K8s service account is always `registry-api` (same name in both clusters). The Workload Identity binding connects the K8s SA to the environment-specific GCP SA.

#### `infra/environments/staging/main.tf`

Composes all the modules:

```hcl
terraform {
  required_version = ">= 1.6.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.44"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

module "network" {
  source      = "../../modules/network"
  project_id  = var.project_id
  region      = var.region
  environment = var.environment
}

module "gke" {
  source      = "../../modules/gke"
  project_id  = var.project_id
  region      = var.region
  environment = var.environment
  network     = module.network.vpc_id
  subnetwork  = module.network.subnet_id
}

module "cloudsql" {
  source                 = "../../modules/cloudsql"
  project_id             = var.project_id
  region                 = var.region
  environment            = var.environment
  tier                   = var.db_tier
  availability           = var.db_availability
  disk_size_gb           = var.db_disk_size_gb
  vpc_name               = module.network.vpc_name
  private_vpc_connection = module.network.private_vpc_connection
}

module "iam" {
  source      = "../../modules/iam"
  project_id  = var.project_id
  region      = var.region
  environment = var.environment
  github_repo = var.github_repo
}

# ── Outputs ──────────────────────────────────────────────────────
output "cluster_name" { value = module.gke.cluster_name }
output "db_instance_connection" { value = module.cloudsql.instance_connection_name }
output "db_user" { value = module.cloudsql.db_user }
output "db_password" {
  value     = module.cloudsql.db_password
  sensitive = true
}
# artifact_registry_url comes from bootstrap, not here
output "ingress_static_ip" { value = module.network.ingress_static_ip }
output "workload_identity_provider" { value = module.iam.workload_identity_provider }
output "github_deployer_sa" { value = module.iam.github_deployer_email }
output "pod_sa_email" { value = module.iam.pod_service_account_email }
```

#### `infra/environments/staging/variables.tf`

```hcl
variable "project_id" { type = string }
variable "region" { type = string }
variable "environment" { type = string }
variable "github_repo" { type = string }
variable "db_tier" { type = string }
variable "db_availability" { type = string }
variable "db_disk_size_gb" { type = number }
```

#### `infra/environments/staging/terraform.tfvars`

```hcl
project_id  = "my-project-id"                   # ← replace
region      = "europe-west2"                     # London (closest GCP region to Dublin)
environment = "staging"
github_repo = "your-org/clojure-k8s-starter"    # ← replace

# Cloud SQL — small for staging
db_tier         = "db-f1-micro"       # shared CPU, 614MB RAM (~$10/month)
                                      # If unavailable, try "db-g1-small" or run:
                                      # gcloud sql tiers list --project=YOUR_PROJECT
db_availability = "ZONAL"             # single zone, no failover
db_disk_size_gb = 10
```

#### `infra/environments/staging/backend.tf`

```hcl
terraform {
  backend "gcs" {
    bucket = "my-project-id-tofu-state"   # ← must match bootstrap output
    prefix = "staging"
  }
}
```

#### `infra/environments/production/terraform.tfvars`

Same modules, different values:

```hcl
project_id  = "my-project-id"
region      = "europe-west2"
environment = "production"
github_repo = "your-org/clojure-k8s-starter"

# Cloud SQL — dedicated for production
db_tier         = "db-custom-1-3840"  # 1 vCPU, 3.75GB RAM (~$55/month)
db_availability = "REGIONAL"          # automatic failover to another zone
db_disk_size_gb = 50
```

Production uses the same `main.tf` and `variables.tf` as staging. Only `terraform.tfvars` and `backend.tf` (with `prefix = "production"`) differ.

### When do you run OpenTofu?

| Situation | What to do |
|-----------|-----------|
| **First time setup** | `tofu init` → `tofu plan` → `tofu apply` |
| **Changed a `.tf` file** | `tofu plan` (review) → `tofu apply` |
| **Changed `terraform.tfvars`** | `tofu plan` → `tofu apply` |
| **Want to see current state** | `tofu show` |
| **Want to see what's drifted** | `tofu plan` (shows differences) |
| **Tearing down** | `tofu destroy` |
| **Someone changed infra in GCP console** | `tofu plan` shows the drift, `tofu apply` corrects it |

### What if you change OpenTofu code?

Infrastructure changes follow the same git flow but are applied **before** merging:

```bash
# 1. Create a branch
git checkout -b infra/increase-db-size

# 2. Edit the tfvars
#    e.g., change db_disk_size_gb = 10 → 20

# 3. Preview the change
cd infra/environments/staging
tofu plan

# Output shows exactly what will change:
#   ~ google_sql_database_instance.main
#       ~ settings.disk_size: 10 → 20
#   Plan: 0 to add, 1 to change, 0 to destroy.

# 4. Apply the change (this modifies real infrastructure)
tofu apply

# 5. Commit, push, and PR (for code review and git history)
git add infra/environments/staging/terraform.tfvars
git commit -m "Increase staging DB disk to 20GB"
git push -u origin infra/increase-db-size
gh pr create --title "Increase staging DB disk"
```

**Why apply before merging?** Unlike app code (which deploys on merge), infrastructure changes need `tofu apply` to take effect. The PR captures the change for review and history, but the actual infra change happens when you run `tofu apply` locally. This is standard practice for small teams. (Larger teams add a CI step to run `tofu plan` on PRs and `tofu apply` on merge.)

### OpenTofu safety rules

- **Always run `tofu plan` before `tofu apply`** — read the plan carefully
- **Never edit infrastructure in the GCP console** — it creates drift; always use OpenTofu
- **State is in GCS** — if you lose it, OpenTofu forgets what it created (fixable with `tofu import`, but painful)
- **`tofu destroy` is irreversible** — it deletes all resources. Great for teardown, dangerous in production
- **Lock files** — `tofu apply` acquires a lock so two people can't apply simultaneously

---

## 6. Helm Chart — Application Packaging

### What is Helm?

Helm templates your K8s YAML with per-environment values. Think of it as: **templates + values file = rendered manifests**.

```
templates/*.yaml                       rendered K8s YAML
       │                                      │
       ├── + values-staging.yaml  ────▶      applied to staging cluster
       │
       └── + values-prod.yaml     ────▶      applied to production cluster
```

### Chart directory structure

```
helm/
├── registry-api/
│   ├── Chart.yaml                      # Chart name + version
│   ├── values.yaml                     # Base defaults
│   ├── values-staging.yaml             # Staging overrides
│   ├── values-prod.yaml                # Production overrides
│   └── templates/
│       ├── _helpers.tpl                # Shared labels/names
│       ├── deployment.yaml             # Pod spec + sidecars
│       ├── service.yaml                # ClusterIP service
│       ├── ingress.yaml                # Load balancer
│       ├── configmap.yaml              # Non-secret config
│       └── serviceaccount.yaml         # Workload Identity binding
│
vault-values.yaml                       # Vault Helm install config (Phase 4)
```

### values.yaml (base defaults)

```yaml
replicaCount: 2

image:
  repository: registry-api
  tag: latest
  pullPolicy: IfNotPresent

service:
  port: 80
  targetPort: 8080

ingress:
  enabled: false
  className: ""
  annotations: {}
  host: ""
  tls: false

config:
  appEnv: dev
  port: "8080"
  dbHost: "127.0.0.1"       # localhost when using Cloud SQL proxy sidecar; override for local dev
  dbPort: "5432"
  dbName: registry
  dbSslMode: disable         # proxy handles encryption
  otelEnabled: "false"
  otelEndpoint: "http://localhost:4317"

vault:
  enabled: false
  role: registry-api
  secretPath: ""             # e.g. "secret/data/staging/registry-api/db"

cloudSqlProxy:
  enabled: false
  instanceConnectionName: ""

serviceAccount:
  create: true
  name: registry-api
  annotations: {}

resources:
  requests:
    cpu: 250m       # Autopilot auto-adjusts below-minimum requests upward
    memory: 512Mi   # (minimums: 250m/512Mi without bursting, 50m/52Mi with)
  limits:
    cpu: 500m
    memory: 512Mi
```

### values-staging.yaml

```yaml
image:
  repository: europe-west2-docker.pkg.dev/PROJECT_ID/registry-api/registry-api
  # tag is set by CI: --set image.tag=$GITHUB_SHA

ingress:
  enabled: true
  className: gce                   # use ingressClassName (not the deprecated annotation)
  annotations:
    kubernetes.io/ingress.global-static-ip-name: staging-ingress-ip
  host: staging-api.example.com    # replace with your domain (or omit for IP-only)

config:
  appEnv: staging

vault:
  enabled: true
  secretPath: "secret/data/staging/registry-api/db"

cloudSqlProxy:
  enabled: true
  instanceConnectionName: PROJECT_ID:europe-west2:staging

serviceAccount:
  annotations:
    iam.gke.io/gcp-service-account: registry-api-staging@PROJECT_ID.iam.gserviceaccount.com
```

### values-prod.yaml

```yaml
image:
  repository: europe-west2-docker.pkg.dev/PROJECT_ID/registry-api/registry-api

ingress:
  enabled: true
  className: gce
  annotations:
    kubernetes.io/ingress.global-static-ip-name: production-ingress-ip
    networking.gke.io/managed-certificates: registry-api-cert
  host: api.example.com
  tls: true

config:
  appEnv: production

vault:
  enabled: true
  secretPath: "secret/data/production/registry-api/db"

cloudSqlProxy:
  enabled: true
  instanceConnectionName: PROJECT_ID:europe-west2:production

serviceAccount:
  annotations:
    iam.gke.io/gcp-service-account: registry-api-production@PROJECT_ID.iam.gserviceaccount.com
```

### What the rendered Deployment looks like

```
┌─ Pod ───────────────────────────────────────────────────────┐
│                                                              │
│  ┌─ vault-agent-init (init container) ────────────────────┐ │
│  │  Injected automatically by Vault webhook               │ │
│  │  Fetches DB_USER + DB_PASSWORD from Vault              │ │
│  │  Writes them to /vault/secrets/db (shared volume)      │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ registry-api container ────────────────────────────────┐ │
│  │                                                         │ │
│  │  image: .../registry-api:<git-sha>                     │ │
│  │                                                         │ │
│  │  env from ConfigMap:                                    │ │
│  │    APP_ENV, PORT, DB_HOST, DB_PORT, DB_NAME            │ │
│  │                                                         │ │
│  │  env from Vault (via shared volume):                    │ │
│  │    DB_USER, DB_PASSWORD                                │ │
│  │                                                         │ │
│  │  probes:                                                │ │
│  │    startup:   GET /health  (every 2s, up to 30 tries)  │ │
│  │    readiness: GET /ready   (every 5s)                  │ │
│  │    liveness:  GET /health  (every 10s)                 │ │
│  │                                                         │ │
│  │  connects to → 127.0.0.1:5432                          │ │
│  └──────────────────────────┬──────────────────────────────┘ │
│                             │ localhost                       │
│  ┌─ cloud-sql-proxy sidecar ┴─────────────────────────────┐ │
│  │  Listens on 127.0.0.1:5432                              │ │
│  │  Authenticates to Cloud SQL via IAM (Workload Identity) │ │
│  │  Encrypts traffic — no SSL config needed in the app     │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  ServiceAccount: registry-api                                │
│    annotated → GCP SA: registry-api-staging@...             │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### How Vault secrets become environment variables

The Vault Agent Injector works like this:

1. Your Deployment has annotations like `vault.hashicorp.com/agent-inject-secret-db`
2. The Vault webhook sees the annotations and injects an init container
3. The init container authenticates to Vault using the pod's K8s service account
4. It fetches the secret and writes it to `/vault/secrets/db` as a file
5. Your Deployment template mounts this file and sources it as env vars using an entrypoint wrapper, or you use a Vault template annotation to write it in `KEY=VALUE` format

Your app code doesn't change — `config.edn` keeps reading `DB_USER` and `DB_PASSWORD` from env vars.

---

## 7. GitHub Actions — CI/CD Pipeline

### Pipeline flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                         GitHub Actions                              │
│                                                                     │
│  ON PUSH TO main                                                    │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Job: build-and-test                                        │   │
│  │                                                             │   │
│  │  1. Checkout code                                           │   │
│  │  2. Install Clojure + bb                                    │   │
│  │  3. bb check (lint + format + smoke)                        │   │
│  │  4. Auth to GCP (google-github-actions/auth)                │   │
│  │  5. docker build + push to Artifact Registry                │   │
│  │     tag: europe-west2-docker.pkg.dev/PROJECT/repo:GIT_SHA   │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
│                             ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Job: deploy-staging (automatic)                            │   │
│  │                                                             │   │
│  │  1. Auth to GCP                                             │   │
│  │  2. gcloud container clusters get-credentials staging       │   │
│  │  3. helm upgrade --install -f values-staging.yaml           │   │
│  │       --set image.tag=$GITHUB_SHA --wait --timeout 5m       │   │
│  │       (blocks until pods are healthy)                       │   │
│  └──────────────────────────┬──────────────────────────────────┘   │
│                             │                                       │
│                             ▼                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Job: deploy-production (requires approval)                 │   │
│  │                                                             │   │
│  │  ⏳ Waits for you to click "Approve" in GitHub UI           │   │
│  │                                                             │   │
│  │  1. Auth to GCP                                             │   │
│  │  2. gcloud container clusters get-credentials production    │   │
│  │  3. helm upgrade --install -f values-prod.yaml              │   │
│  │       --set image.tag=$GITHUB_SHA  (same image as staging)  │   │
│  │       --wait --timeout 5m                                   │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

### Workflow skeleton: `.github/workflows/deploy.yaml`

```yaml
name: Build and Deploy

on:
  push:
    branches: [main]

env:
  REGION: europe-west2
  IMAGE: europe-west2-docker.pkg.dev/${{ secrets.GCP_PROJECT_ID }}/registry-api/registry-api

# Required for Workload Identity Federation OIDC token
permissions:
  id-token: write
  contents: read

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    environment: staging           # needed to access environment-scoped GCP secrets
    steps:
      - uses: actions/checkout@v4

      - name: Install Clojure & bb
        uses: DeLaGuardo/setup-clojure@13
        with:
          cli: latest
          bb: latest

      - name: Quality checks
        run: bb check

      - name: Authenticate to GCP
        uses: google-github-actions/auth@v3
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Configure Docker
        run: gcloud auth configure-docker ${{ env.REGION }}-docker.pkg.dev --quiet

      - name: Build and push image
        run: |
          docker build -t ${{ env.IMAGE }}:${{ github.sha }} .
          docker push ${{ env.IMAGE }}:${{ github.sha }}

  deploy-staging:
    needs: build-and-test
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/checkout@v4

      - name: Install Helm
        uses: azure/setup-helm@v5

      - name: Authenticate to GCP
        uses: google-github-actions/auth@v3
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Get GKE credentials
        uses: google-github-actions/get-gke-credentials@v3
        with:
          cluster_name: staging
          location: ${{ env.REGION }}

      - name: Deploy with Helm
        run: |
          helm upgrade --install registry-api helm/registry-api \
            -f helm/registry-api/values-staging.yaml \
            --set image.tag=${{ github.sha }} \
            -n registry-api --create-namespace \
            --wait --timeout 5m

  deploy-production:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production          # ← this triggers the approval gate
    steps:
      - uses: actions/checkout@v4

      - name: Install Helm
        uses: azure/setup-helm@v5

      - name: Authenticate to GCP
        uses: google-github-actions/auth@v3
        with:
          workload_identity_provider: ${{ secrets.GCP_WORKLOAD_IDENTITY_PROVIDER }}
          service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

      - name: Get GKE credentials
        uses: google-github-actions/get-gke-credentials@v3
        with:
          cluster_name: production
          location: ${{ env.REGION }}

      - name: Deploy with Helm
        run: |
          helm upgrade --install registry-api helm/registry-api \
            -f helm/registry-api/values-prod.yaml \
            --set image.tag=${{ github.sha }} \
            -n registry-api --create-namespace \
            --wait --timeout 5m
```

### Workload Identity Federation (keyless auth)

```
GitHub Actions                          GCP
─────────────                          ───

1. Mint OIDC token ──────────────────▶ 2. Workload Identity Federation
   "I am repo X,                          validates the token against
    branch main"                          the pool/provider we created
                                          in OpenTofu
                                       3. Issues short-lived
                  ◀──────────────────     credentials (1 hour TTL)

4. Use credentials to push images and deploy
```

No GCP keys stored anywhere. Tokens are minted per workflow run and expire automatically.

### GitHub repository configuration

**Secrets** (repo → Settings → Secrets → Actions):

Set as **repository-level** secrets (shared):

| Secret | Value | Source |
|--------|-------|--------|
| `GCP_PROJECT_ID` | Your GCP project ID | You set this manually |

Set as **environment-level** secrets (different per env):

| Secret | Staging value | Production value |
|--------|--------------|-----------------|
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `tofu output workload_identity_provider` (from staging) | `tofu output workload_identity_provider` (from production) |
| `GCP_SERVICE_ACCOUNT` | `github-deployer-staging@PROJECT.iam...` | `github-deployer-production@PROJECT.iam...` |

**Environments** (repo → Settings → Environments):

| Environment | Protection | Environment secrets |
|-------------|-----------|-------------------|
| `staging` | None (auto-deploys) | Staging WIF provider + SA |
| `production` | Required reviewer: you | Production WIF provider + SA |

---

## 8. HashiCorp Vault — Secrets Management

### Why Vault instead of K8s Secrets?

K8s Secrets are base64-encoded (not encrypted at rest by default), visible to anyone with namespace access, and have no audit log. Vault provides:

- **Encryption at rest** — secrets are encrypted in storage
- **Audit logging** — who accessed what secret, when
- **Access policies** — fine-grained control per service
- **Lease/renewal** — secrets can have TTLs and auto-expire
- **One tool for all secrets** — DB creds, API keys, TLS certs, all in one place

### How secrets flow

```
┌─────────────┐       ┌──────────────────┐       ┌───────────────┐
│  OpenTofu   │──────▶│     Vault        │──────▶│  Pod          │
│             │       │                  │       │               │
│ Creates DB  │       │ Stores DB creds  │       │ vault-agent   │
│ user +      │       │ at path:         │       │ init container│
│ password    │       │ secret/staging/  │       │ fetches creds │
│             │       │  registry-api/db │       │ writes to     │
└─────────────┘       └──────────────────┘       │ shared volume │
                                                  │ → env vars    │
                                                  └───────────────┘
```

### Vault architecture in GKE

```
┌─ namespace: vault ──────────────────────────────────────────┐
│                                                              │
│  ┌─ vault-0 (StatefulSet) ──────────────────────────────┐   │
│  │  Vault server                                         │   │
│  │  Storage: file (on a persistent volume)                │   │
│  └───────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌─ vault-agent-injector (Deployment) ──────────────────┐   │
│  │  Mutating webhook — watches for pod annotations       │   │
│  │  Automatically injects vault-agent sidecars into      │   │
│  │  annotated pods in ANY namespace                      │   │
│  └───────────────────────────────────────────────────────┘   │
│                                                              │
└──────────────────────────────────────────────────────────────┘

┌─ namespace: registry-api ───────────────────────────────────┐
│                                                              │
│  Pod annotations tell the injector what to fetch:           │
│                                                              │
│    vault.hashicorp.com/agent-inject: "true"                 │
│    vault.hashicorp.com/role: "registry-api"                 │
│    vault.hashicorp.com/agent-inject-secret-db:              │
│      "secret/data/staging/registry-api/db"                  │
│    vault.hashicorp.com/agent-requests-cpu: "250m"           │
│    vault.hashicorp.com/agent-requests-mem: "512Mi"          │
│                                                              │
│  Note: Vault KV v2 stores data at secret/data/... internally│
│  but the vault CLI uses the short path: secret/staging/...  │
│  The annotation needs the full path with /data/.            │
│                                                              │
│  Note: On GKE Autopilot, Autopilot auto-adjusts resource     │
│  requests below its minimums (250m/512Mi without bursting).  │
│  The annotations above set explicit values to avoid surprises.│
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Vault setup script (`scripts/vault-configure.sh`)

Run once after installing Vault into a cluster. Must be run from the project root.

```bash
#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/vault-configure.sh <environment> <vault-root-token>
# Example: ./scripts/vault-configure.sh staging hvs.abc123...

ENV="${1:?Usage: $0 <staging|production> <vault-token>}"
VAULT_TOKEN="${2:?Usage: $0 <staging|production> <vault-token>}"
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

export VAULT_ADDR="http://127.0.0.1:8200"
export VAULT_TOKEN

# Port-forward to Vault (runs in background)
kubectl -n vault port-forward svc/vault 8200:8200 &
PF_PID=$!
trap "kill $PF_PID 2>/dev/null || true" EXIT
sleep 3

echo "Configuring Vault for environment: $ENV"

# 1. Enable KV v2 secrets engine (idempotent)
vault secrets enable -path=secret kv-v2 2>/dev/null || true

# 2. Enable Kubernetes auth method (idempotent)
vault auth enable kubernetes 2>/dev/null || true

# 3. Configure K8s auth — tell Vault how to verify K8s service account tokens
K8S_HOST=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
SA_TOKEN=$(kubectl -n vault exec vault-0 -- \
  cat /var/run/secrets/kubernetes.io/serviceaccount/token)
SA_CA=$(kubectl -n vault exec vault-0 -- \
  cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt)

vault write auth/kubernetes/config \
  kubernetes_host="$K8S_HOST" \
  token_reviewer_jwt="$SA_TOKEN" \
  kubernetes_ca_cert="$SA_CA"

# 4. Create policy — registry-api can only read its own secrets
vault policy write registry-api - <<POLICY
path "secret/data/${ENV}/registry-api/*" {
  capabilities = ["read"]
}
POLICY

# 5. Create K8s auth role — maps the K8s SA to the Vault policy
vault write auth/kubernetes/role/registry-api \
  bound_service_account_names=registry-api \
  bound_service_account_namespaces=registry-api \
  policies=registry-api \
  ttl=1h

# 6. Seed DB credentials from OpenTofu outputs
DB_USER=$(cd "$PROJECT_ROOT/infra/environments/$ENV" && tofu output -raw db_user)
DB_PASS=$(cd "$PROJECT_ROOT/infra/environments/$ENV" && tofu output -raw db_password)

vault kv put "secret/${ENV}/registry-api/db" \
  username="$DB_USER" \
  password="$DB_PASS"

echo ""
echo "Done. Vault configured for $ENV:"
echo "  Secrets path:  secret/$ENV/registry-api/db"
echo "  K8s auth role: registry-api"
echo "  Policy:        registry-api (read-only)"
```

### Your app code doesn't change

`config.edn` keeps reading from env vars as it does today:

```clojure
:secrets {:db-user     #or [#env DB_USER ""]
          :db-password #or [#env DB_PASSWORD ""]}
```

The Vault agent writes the secrets as files, which the pod entrypoint sources as environment variables. The app never talks to Vault directly.

---

## 9. Cloud SQL & Database

### Per environment

```
┌─ Staging ───────────────────────────────────────┐
│                                                  │
│  Instance: "staging"                             │
│  Engine: PostgreSQL 15                           │
│  Tier: db-f1-micro (shared CPU, 614MB RAM)      │
│  Disk: 10GB SSD                                  │
│  Availability: ZONAL (single zone, no failover)  │
│  Backups: daily, 7-day retention                 │
│  Network: private IP only (via VPC peering)      │
│                                                  │
└──────────────────────────────────────────────────┘

┌─ Production ────────────────────────────────────┐
│                                                  │
│  Instance: "production"                          │
│  Engine: PostgreSQL 15                           │
│  Tier: db-custom-1-3840 (1 vCPU, 3.75GB RAM)   │
│  Disk: 50GB SSD                                  │
│  Availability: REGIONAL (auto failover)          │
│  Backups: daily, 30-day retention + PITR         │
│  Network: private IP only                        │
│                                                  │
└──────────────────────────────────────────────────┘
```

### Connection path

```
registry-api            Cloud SQL Auth Proxy         Cloud SQL
───────────            ─────────────────────        ──────────

connects to            Listens on 127.0.0.1:5432    PostgreSQL 15
127.0.0.1:5432  ─────▶ Authenticates via IAM  ─────▶ (private IP only)
                        (Workload Identity)
DB_USER + DB_PASSWORD   Encrypts traffic
from Vault              (no SSL config needed
                         in the app)
```

The app thinks it's connecting to a local PostgreSQL. The proxy handles authentication and encryption transparently.

---

## 10. DNS & Ingress

### How it works

```
Internet
    │
    │  api.example.com
    ▼
┌──────────────────────────────────┐
│  Google Cloud Load Balancer      │
│  (auto-created by GKE Ingress)  │
│                                  │
│  Static IP (reserved by OpenTofu)│
│  TLS: Google-managed cert        │
│  Health check: GET /ready        │
└──────────┬───────────────────────┘
           │
           ▼
    K8s Ingress → Service → Pods
```

| | Staging | Production |
|--|---------|------------|
| Domain | `staging-api.example.com` | `api.example.com` |
| Static IP | Reserved by OpenTofu | Reserved by OpenTofu |
| TLS | Google-managed cert | Google-managed cert |

The static IPs don't change across deploys because OpenTofu reserves them.

### If you don't have a domain yet

Start with `ingress.enabled: false` in your Helm values and set the Service type to `LoadBalancer`. This gives you a raw IP to hit directly. Add the Ingress when DNS is ready.

---

## 11. Day-2 Operations

### Manual deploy

```bash
# To staging
bb ctx-staging
helm upgrade --install registry-api helm/registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag=<git-sha> \
  -n registry-api

# To production
bb ctx-prod
helm upgrade --install registry-api helm/registry-api \
  -f helm/registry-api/values-prod.yaml \
  --set image.tag=<git-sha> \
  -n registry-api
```

### Rollback

```bash
helm history registry-api -n registry-api      # see release history
helm rollback registry-api -n registry-api      # rollback to previous
helm rollback registry-api 3 -n registry-api    # rollback to revision 3
```

### View logs

```bash
kubectl logs -l app=registry-api -n registry-api --tail=50 -f
```

### Check status

```bash
kubectl get pods -n registry-api -o wide
kubectl get ingress -n registry-api
helm status registry-api -n registry-api
```

### Connect to Cloud SQL (debugging)

Since the instances have no public IP (`ipv4_enabled = false`), use the Cloud SQL Auth Proxy locally:

```bash
# Install the proxy (macOS)
brew install cloud-sql-proxy

# Connect to staging (opens a local tunnel on port 5433)
cloud-sql-proxy --port 5433 PROJECT_ID:europe-west2:staging &

# Then connect with psql
psql "host=127.0.0.1 port=5433 dbname=registry user=registry-api"

# Or for production
cloud-sql-proxy --port 5434 PROJECT_ID:europe-west2:production &
psql "host=127.0.0.1 port=5434 dbname=registry user=registry-api"
```

### Check Vault secrets

```bash
kubectl -n vault port-forward svc/vault 8200:8200 &
export VAULT_ADDR=http://127.0.0.1:8200
export VAULT_TOKEN=<root-token>
vault kv get secret/staging/registry-api/db
```

### Infrastructure changes

```bash
cd infra/environments/staging
tofu plan    # preview
tofu apply   # apply
```

---

## 12. Future Infrastructure

All installed as Helm charts into the same cluster:

```
┌─────────────────────────────────────────────────────────────────┐
│                     GKE Autopilot Cluster                       │
│                                                                 │
│  ┌─ namespace: registry-api ────────────────────────────────┐  │
│  │  registry-api pods (your app)                             │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─ namespace: vault ───────────────────────────────────────┐  │
│  │  HashiCorp Vault (secrets for everything)                 │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─ namespace: rabbitmq ────────────────────────────────────┐  │
│  │  RabbitMQ (bitnami/rabbitmq) — Phase 6                    │  │
│  │  Event bus for async projections, sagas, integration evts │  │
│  │  Vault-managed credentials, NetworkPolicy locked down     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─ namespace: observability ───────────────────────────────┐  │
│  │  Grafana LGTM stack (Phase 5)                              │  │
│  │  Loki (logs) + Tempo (traces) + Mimir (metrics, distrib.) │  │
│  │  Grafana (dashboards) + OTel Collector + Alloy (DaemonSet)│  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─ namespace: redis ───────────────────────────────────────┐  │
│  │  Redis (bitnami/redis) — Phase 7                          │  │
│  │  Deduplication, cache-aside, saga state, search cache     │  │
│  │  Vault-managed credentials, NetworkPolicy locked down     │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

Each gets a `values-staging.yaml` and `values-prod.yaml` for environment-specific sizing.

---

## 13. Cost: Spin Up, Test, Tear Down

### GCP billing for short-lived resources

| Resource | How it's billed | Minimum charge | ~30 min cost |
|----------|----------------|----------------|-------------|
| GKE Autopilot management fee | Per hour | $0.10/hr | ~$0.10 |
| GKE Autopilot pods | Per second, per vCPU+memory | 1 minute | ~$0.02-0.05 |
| Cloud SQL (db-f1-micro) | Per hour | 1 hour | ~$0.01 |
| Load Balancer | Per hour | 5 rules free | ~$0.02 |
| Artifact Registry | Per GB stored | Negligible | ~$0.00 |
| Static IP (attached) | Free while in use | Free | $0.00 |
| VPC / subnets | Free | Free | $0.00 |

### Total for a quick test

```
┌──────────────────────────────────────────────────┐
│  Provision → Play for 30 min → Tear down         │
│                                                  │
│  GKE cluster management fee      $0.10           │
│  GKE pods (2 app, 30 min)        $0.05           │
│  Vault pod                       $0.02           │
│  Observability (~13 pods)        $0.15           │
│  RabbitMQ pod                    $0.02           │
│  Redis pod                       $0.02           │
│  Cloud SQL (1 hour minimum)      $0.01           │
│  Load balancer                   $0.02           │
│                                  ──────          │
│  TOTAL                          ~$0.39           │
│                                                  │
│  Realistic (rounding + overhead): $0.50 - $1.50  │
└──────────────────────────────────────────────────┘
```

### What if you forget to tear down?

| Left running | Approximate cost (with observability + RabbitMQ + Redis) |
|-------------|----------------------------------------------|
| 1 day | ~$10-14 |
| 1 week | ~$65-95 |
| 1 month | ~$150-220 |

Set a **budget alert** in GCP Console → Billing → Budgets (e.g., $10/month with email alerts at 50%, 90%, 100%).

### Production costs (running continuously)

| Resource | Staging/month | Production/month |
|----------|-------------|-----------------|
| GKE Autopilot (2 app pods) | ~$40 | ~$40 |
| GKE Autopilot (Vault pod) | ~$5 | ~$5 |
| GKE Autopilot (observability, ~13 pods) | ~$60-80 | ~$60-80 |
| GKE Autopilot (RabbitMQ, 1 pod + PVC) | ~$8-10 | ~$8-10 |
| GKE Autopilot (Redis, 1 pod + PVC) | ~$6-8 | ~$6-8 |
| Cloud SQL | ~$10 (micro) | ~$55 (dedicated + HA) |
| Load Balancer | ~$18 | ~$18 |
| Artifact Registry | ~$1 | (shared) |
| **Subtotal** | **~$148-172** | **~$192-216** |
| **Both environments** | | **~$340-388/month** |

> **Note:** Mimir (distributed) is the largest observability cost (~8 pods). For a lighter setup, consider Grafana Cloud Free Tier for metrics instead of self-hosted Mimir. See Phase 5 for details. RabbitMQ adds ~$8-10/month and Redis adds ~$6-8/month per environment (single replica with 8Gi persistent storage each).

---

## 14. How Hard Is This?

### Honest assessment

This is a substantial setup. Here's a breakdown by phase:

### Phase 1: Infrastructure — OpenTofu + GKE (difficulty: moderate, ~1-2 days)

**What you'll do:** Write the `.tf` files, run `tofu apply`, get a GKE cluster and Cloud SQL running.

**Why it's moderate, not easy:**
- OpenTofu/Terraform has a learning curve if you haven't used it before. The HCL syntax is straightforward, but understanding state management, plan/apply workflow, and provider quirks takes practice.
- GKE Autopilot creation takes ~10 minutes and occasionally fails on first try (quota issues, API enablement race conditions). Re-running `tofu apply` fixes it.
- The VPC peering for Cloud SQL private networking is the fiddliest part — it has timing dependencies. The code handles this via implicit dependency chains between modules, but if it errors, you just re-apply.

**What could go wrong:**
- GCP quota limits (new projects have low defaults — you may need to request increases)
- API enablement takes a few minutes to propagate; `tofu apply` may fail on first run (just re-run)
- Typos in `terraform.tfvars` (wrong project ID, wrong region)

### Phase 2: Helm + Deploy (difficulty: easy-moderate, ~half day)

**What you'll do:** Write the chart templates, values files, and deploy the app to staging with K8s Secrets for DB credentials.

**Why it's manageable:**
- The templates are mostly your existing `k8s/` manifests with `{{ .Values.x }}` substitutions.
- Helm has good error messages when templates don't render.
- You can test locally with `helm template` (renders YAML without deploying).
- Using K8s Secrets (instead of Vault) keeps the first deployment simple.

**What could go wrong:**
- YAML indentation errors in templates (Helm's most common issue)
- The Cloud SQL proxy sidecar needs the right service account annotation — if Workload Identity isn't set up correctly, it can't authenticate

### Phase 3: CI/CD — GitHub Actions (difficulty: easy, ~half day)

**What you'll do:** Write the workflow YAML, configure GitHub secrets and environments.

**Why it's easy:**
- The workflow is mostly boilerplate — Google provides official actions for auth and GKE credentials.
- The `google-github-actions/auth` action handles all the WIF complexity.
- Once the secrets are set, it either works or gives clear error messages.

**What could go wrong:**
- Wrong Workload Identity Provider string in GitHub secrets (copy-paste error from `tofu output`)
- Helm not installed in the runner (the workflow includes `azure/setup-helm@v5` for this)
- The `bb check` step needs Clojure/bb installed (use `DeLaGuardo/setup-clojure` action)

### Phase 4: Vault (difficulty: hardest part, ~1 day)

**What you'll do:** Install Vault via Helm, initialise it, configure K8s auth, seed secrets, and swap K8s Secrets for Vault.

**Why it's the hardest:**
- Vault has the steepest learning curve of all the tools here. Init, unseal, auth methods, policies, secret engines — lots of concepts.
- The Vault Agent Injector (how secrets get into pods) requires correct annotations, correct K8s auth role bindings, and correct policy paths. If any part is wrong, the pod hangs waiting for secrets with an unhelpful error.
- Vault needs to be unsealed after every pod restart. For production, you'd set up auto-unseal via GCP KMS (covered in Phase 4 Day-2 operations).

**What could go wrong:**
- Vault pod resource adjustment on Autopilot (Autopilot auto-adjusts requests below its minimums — the Helm values should set explicit requests to avoid unexpected billing)
- Vault Agent Injector webhook not firing (usually a namespace label issue)
- Wrong Vault policy path (the `/data/` prefix in KV v2 catches everyone at least once)
- Lost unseal keys (unrecoverable — you'd have to reinstall Vault and re-seed secrets)

**Why this is last:** The app works fine with K8s Secrets from Phase 2. Vault adds security (encryption, audit log, policies) but not functionality. Doing it last means you can get a working deployment faster and layer on security when you're ready.

### Phase 5: Observability — Grafana LGTM (difficulty: moderate, ~1 day)

**What you'll do:** Install Loki, Tempo, Mimir (distributed), Grafana, OTel Collector, and Alloy via Helm. Enable telemetry in your app (flip a config flag), create dashboards, and set up alerting.

**Why it's moderate:**
- Lots of Helm installs (6 charts) but each is straightforward — mostly just applying value files.
- Mimir is the most complex piece — its `mimir-distributed` chart creates ~8 pods (distributor, ingester, querier, etc.) with a built-in MinIO for storage. This is by design, not an error.
- The OTel Collector config (receivers → processors → exporters) has its own DSL, but the pipeline concept is intuitive once you see it.
- Alloy's River config syntax looks different from YAML but the log collection config is a copy-paste pattern.

**What could go wrong:**
- Mimir pods pending for a while — Autopilot needs to provision nodes for ~13 new pods
- OTel Collector Service name mismatch — the Helm chart creates `otel-collector-opentelemetry-collector`, not just `otel-collector`
- No traces appearing — usually means `OTEL_ENABLED` is still `false` or the endpoint is wrong
- Alloy not collecting logs from a node — check it's running on the same node as your app pod

**Why this can wait:** Your app runs fine without observability. But once it's running in production, you'll want to know what's happening inside it — Phase 5 gives you that visibility.

### Phase 6: RabbitMQ — Async Messaging (difficulty: easy-moderate, ~half day)

**What you'll do:** Install RabbitMQ via bitnami/rabbitmq Helm chart, store credentials in Vault, declare exchange/queue/binding topology for outbox, saga, and integration event patterns, lock down access with NetworkPolicies, and wire monitoring into the Phase 5 LGTM stack.

**Why it's manageable:**
- Single Helm chart — `bitnami/rabbitmq` is well-documented and battle-tested.
- Credential management follows the same Vault pattern from Phase 4 (Vault Agent Injector annotations).
- The exchange/queue topology is declared in Clojure code via Langohr at app startup — no manual RabbitMQ admin needed.
- If Phase 5 is in place, RabbitMQ metrics are just one more OTel Collector scrape target.

**What could go wrong:**
- RabbitMQ pod stuck pending — Autopilot provisioning a new node for the StatefulSet + 8Gi PVC
- Vault secret path mismatch — `secret/data/rabbitmq/credentials` vs `secret/rabbitmq/credentials` (the KV v2 `/data/` prefix again)
- NetworkPolicy too restrictive — app pods can't reach RabbitMQ (check namespace labels)
- Dead letter queue filling up — usually means a consumer is rejecting messages with `basic.reject` without `requeue: true`

**Why this can wait:** The app's event sourcing framework supports synchronous projections and in-process sagas. RabbitMQ adds true async processing, cross-module integration events, and resilience (messages survive pod restarts). Add it when you need horizontal scaling or multi-module communication.

### Phase 7: Redis — Caching & Idempotency (difficulty: easy, ~half day)

**What you'll do:** Install Redis via bitnami/redis Helm chart in standalone mode, store credentials in Vault, implement four caching patterns in Clojure (deduplication, cache-aside, search cache, saga state), lock down with NetworkPolicies, and wire monitoring into the Phase 5 LGTM stack via redis-exporter sidecar.

**Why it's easy:**
- Single Helm chart — `bitnami/redis` in standalone mode (no replicas) is the simplest stateful workload.
- Same Vault pattern as Phase 4 and 6 — you've done this twice already.
- Carmine (Clojure Redis client) has a simple API — `car/set`, `car/get`, `car/del` cover 90% of use cases.
- Redis itself is the most forgiving stateful service — if it goes down, the app just has cache misses and slightly slower queries. No data loss.

**What could go wrong:**
- Redis pod stuck pending — Autopilot provisioning node for the StatefulSet + 8Gi PVC (same pattern as RabbitMQ)
- Vault secret path mismatch — `secret/data/redis/credentials` vs `secret/redis/credentials` (the KV v2 `/data/` prefix)
- Carmine connection pool exhaustion — if too many concurrent requests (increase `:pool {:max-total}`)
- Cache serving stale data — set appropriate TTLs and invalidate on writes

**Why this can wait:** Without Redis, the app works correctly — just slower on read-heavy queries and without deduplication protection. Redis adds performance (cached reads), correctness (idempotent message processing), and operational convenience (saga state survives pod restarts).

### Summary

| Phase | What | Difficulty | Time estimate | Can skip initially? |
|-------|------|-----------|---------------|-------------------|
| 1 | OpenTofu + GKE + Cloud SQL | Moderate | 1-2 days | No (this is the foundation) |
| 2 | Helm chart + deploy | Easy-Moderate | Half day | No |
| 3 | GitHub Actions CI/CD | Easy | Half day | Yes — deploy manually at first |
| 4 | Vault | Hard | 1 day | Yes — use K8s Secrets from Phase 2 |
| 5 | Observability (LGTM stack) | Moderate | 1 day | Yes — app works without it |
| 6 | RabbitMQ (async messaging) | Easy-Moderate | Half day | Yes — app works synchronously |
| 7 | Redis (caching + idempotency) | Easy | Half day | Yes — app works without caching |
| | **Total** | | **~5-6 days** | |

This order means you get a working deployment in ~2 days (Phases 1-2), automate it on day 2-3 (Phase 3), layer in security (Phase 4) when everything else is stable, add observability (Phase 5) so you can see what's happening in production, add async messaging (Phase 6) when you need cross-module communication, and add caching/idempotency (Phase 7) for performance and correctness.

---

## 15. Files to Create

### OpenTofu

```
infra/
├── bootstrap/main.tf                     (state bucket + Artifact Registry)
├── modules/
│   ├── network/main.tf, variables.tf, outputs.tf
│   ├── gke/main.tf, variables.tf, outputs.tf
│   ├── cloudsql/main.tf, variables.tf, outputs.tf
│   └── iam/main.tf, variables.tf, outputs.tf
└── environments/
    ├── staging/main.tf, variables.tf, terraform.tfvars, backend.tf
    └── production/main.tf, variables.tf, terraform.tfvars, backend.tf
```

### Helm

```
helm/
└── registry-api/
    ├── Chart.yaml
    ├── values.yaml, values-staging.yaml, values-prod.yaml
    └── templates/ (deployment, service, ingress, configmap, serviceaccount)

vault-values.yaml                     (Vault Helm install config — Phase 4)

Observability Helm value files (Phase 5):
  loki-values.yaml
  tempo-values.yaml
  mimir-values.yaml
  grafana-values.yaml
  alloy-values.yaml
  otel-collector-values.yaml

rabbitmq-values.yaml                  (RabbitMQ Helm install config — Phase 6)
redis-values.yaml                     (Redis Helm install config — Phase 7)
```

### GitHub Actions

```
.github/workflows/deploy.yaml
```

### Scripts

```
scripts/vault-configure.sh
```

### Babashka tasks (additions to bb.edn)

```
bb ctx, bb ctx-local, bb ctx-staging, bb ctx-prod
```

### Unchanged

```
k8s/              — local Rancher Desktop (untouched)
Dockerfile        — works as-is
src/, resources/  — no app code changes
```

---

## 16. Implementation Phases

Each phase builds on the previous one. Complete each before moving to the next.

```
Phase 1              Phase 2            Phase 3            Phase 4            Phase 5              Phase 6             Phase 7
───────              ───────            ───────            ───────            ───────              ───────             ───────

 GCP project          Helm chart         GitHub Actions     Vault              LGTM stack           RabbitMQ            Redis
 OpenTofu bootstrap   templates          CI/CD workflow     install +          OTel Collector       Vault creds         Vault creds
 OpenTofu staging     values files       GitHub secrets     configure          Alloy, Grafana       topology            Carmine client
 GKE + Cloud SQL      manual deploy      auto-deploy        K8s auth +         dashboards           NetworkPolicy       dedup + cache
 kubectl connected    app running        staging + prod     policies           + alerting           monitoring          NetworkPolicy
                      on staging                            swap Secrets                                                monitoring

 ✓ Infra exists       ✓ App reachable    ✓ Push-to-deploy   ✓ Secrets          ✓ Logs in Loki       ✓ Broker running    ✓ Redis running
 ✓ Can push images    ✓ DB connected     ✓ Approval gates   ✓ Audit log        ✓ Traces in Tempo    ✓ Exchanges/queues  ✓ Dedup works
                                                                               ✓ Metrics in Mimir   ✓ App connected     ✓ Cache-aside
```

| Phase | What | Depends on | Difficulty | Time | Detailed plan |
|-------|------|-----------|-----------|------|---------------|
| **1. Infrastructure** | GCP project, OpenTofu, GKE cluster, Cloud SQL, Artifact Registry, IAM | Nothing | Moderate | 1-2 days | [K8s-phase1.md](K8s-phase1.md) |
| **2. Helm + Deploy** | Write Helm chart, deploy app to staging with K8s Secrets (no Vault yet), verify it works | Phase 1 | Easy-Moderate | Half day | [K8s-phase2.md](K8s-phase2.md) |
| **3. CI/CD** | GitHub Actions workflow, Workload Identity Federation secrets, environment protection rules | Phase 1 + 2 | Easy | Half day | [K8s-phase3.md](K8s-phase3.md) |
| **4. Vault** | Install Vault, configure K8s auth, seed secrets, swap K8s Secrets for Vault in Helm values | Phase 1 + 2 | Hard | 1 day | [K8s-phase4.md](K8s-phase4.md) |
| **5. Observability** | Grafana LGTM stack — Loki (logs), Tempo (traces), Mimir (metrics), Grafana (dashboards), OTel Collector, Alloy | Phase 1 + 2 | Moderate | 1 day | [K8s-phase5.md](K8s-phase5.md) |
| **6. RabbitMQ** | Message broker for async projections, sagas, integration events. Vault credentials, NetworkPolicy, monitoring | Phase 1 + 2 + 4 | Easy-Moderate | Half day | [K8s-phase6.md](K8s-phase6.md) |
| **7. Redis** | Deduplication, cache-aside, saga state, search cache. Vault credentials, NetworkPolicy, monitoring | Phase 1 + 2 + 4 + 6 | Easy | Half day | [K8s-phase7.md](K8s-phase7.md) |

### Phase boundaries — what's done at each milestone

**After Phase 1:** You have a GKE cluster, Cloud SQL database, Artifact Registry, and IAM/WIF all running in GCP. You can push Docker images and `kubectl` into the cluster. No app deployed yet.

**After Phase 2:** Your app is running on staging, reachable via an IP or domain, connected to Cloud SQL through the proxy sidecar. DB credentials are in K8s Secrets (not Vault). You deploy manually with `helm upgrade`.

**After Phase 3:** Every push to `main` auto-deploys to staging. Production deploys require clicking "Approve" in GitHub. No more manual `helm upgrade` or `docker push`.

**After Phase 4:** DB credentials are in Vault instead of K8s Secrets. Audit log tracks who accessed what. Vault Agent Injector handles secret injection. The app code doesn't change — it still reads env vars.

**After Phase 5:** Full observability — logs flow to Loki, traces to Tempo, metrics to Mimir. Grafana dashboards show request rates, error rates, P99 latency. Alerts fire when things go wrong. You can trace a slow request end-to-end across services.

**After Phase 6:** RabbitMQ is running with Vault-managed credentials. Exchanges, queues, and bindings are declared for outbox (async projections), saga (orchestration), and integration events (cross-module). NetworkPolicy restricts AMQP access to the app namespace only. Metrics flow to Mimir via OTel Collector. The app can now process events asynchronously and communicate between modules.

**After Phase 7:** Redis is running in standalone mode with Vault-managed credentials. Deduplication prevents duplicate message processing (SET NX with 24h TTL). Cache-aside pattern accelerates read-heavy queries (account balances, search results). Saga correlation state lives in Redis HASHes (survives pod restarts). NetworkPolicy restricts access to the app namespace only. Metrics flow to Mimir via redis-exporter sidecar on port 9121. The app is now faster, idempotent, and resilient.

### When to do production

Production is a repeat of phases 1-7 with `production` substituted for `staging`. Do it after staging is stable — don't rush to have both environments simultaneously.

---

## 17. Open Questions

1. **GCP Project** — Do you have one with billing enabled, or need to create one?
2. **Domain name** — Do you have one, or start with raw IPs?
3. **GitHub repo path** — e.g., `your-org/clojure-k8s-starter` (needed for Workload Identity)
4. **Production DB tier** — `db-custom-1-3840` (~$55/mo) or start small with `db-f1-micro` (~$10/mo)?
5. **Who approves production deploys?** — Just you, or a team?
6. **Start with Vault or K8s Secrets?** — Vault is more secure but adds complexity; K8s Secrets work fine initially
