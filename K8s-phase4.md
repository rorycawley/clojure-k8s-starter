# Phase 4: HashiCorp Vault — Secrets Management

Replace K8s Secrets (base64, no audit trail) with Vault (encrypted, audited, policy-controlled). Your app code doesn't change — it still reads env vars.

**Prerequisite:** Phases 1-3 complete — app running on staging via CI/CD, DB creds in a K8s Secret.

**End state:** DB credentials live in Vault. Vault Agent Injector writes them to each pod as env vars. The K8s Secret (`registry-api-db-credentials`) is no longer used. Every secret access is audit-logged.

---

## Table of Contents

1. [What you're building](#1-what-youre-building)
2. [Step 1 — Install Vault via Helm](#2-step-1--install-vault-via-helm)
3. [Step 2 — Initialize and unseal Vault](#3-step-2--initialize-and-unseal-vault)
4. [Step 3 — Configure Kubernetes auth](#4-step-3--configure-kubernetes-auth)
5. [Step 4 — Create policy and role](#5-step-4--create-policy-and-role)
6. [Step 5 — Seed secrets from OpenTofu outputs](#6-step-5--seed-secrets-from-opentofu-outputs)
7. [Step 6 — Update Helm values to enable Vault](#7-step-6--update-helm-values-to-enable-vault)
8. [Step 7 — Deploy and verify](#8-step-7--deploy-and-verify)
9. [Step 8 — Clean up old K8s Secret](#9-step-8--clean-up-old-k8s-secret)
10. [Step 9 — Security hardening](#10-step-9--security-hardening)
11. [The configure script (all-in-one)](#11-the-configure-script-all-in-one)
12. [How it works at runtime](#12-how-it-works-at-runtime)
13. [Day-2 operations](#13-day-2-operations)
14. [Troubleshooting](#14-troubleshooting)
15. [Checklist — Phase 4 complete](#15-checklist--phase-4-complete)

---

## 1. What you're building

```
Before (Phase 2-3):                     After (Phase 4):

┌──────────────┐                       ┌──────────────┐
│  K8s Secret  │ base64, no audit      │    Vault     │ encrypted, audit log
│  db-creds    │──▶ env vars ──▶ pod   │  secret/     │
└──────────────┘                       │  staging/    │
                                       │  registry-   │
                                       │  api/db      │
                                       └──────┬───────┘
                                              │
                                       vault-agent-injector
                                       (mutating webhook)
                                              │
                                              ▼
                                       ┌──────────────┐
                                       │ init container│
                                       │ writes        │
                                       │ /vault/secrets│
                                       │ /db           │
                                       └──────┬───────┘
                                              │ source
                                              ▼
                                       ┌──────────────┐
                                       │   your pod   │
                                       │ DB_USER=...  │
                                       │ DB_PASSWORD=.│
                                       └──────────────┘
```

**What happens at pod startup:**
1. Pod has Vault annotations → the Vault Agent Injector webhook sees them
2. Injector adds an init container (vault-agent) to the pod
3. Init container authenticates to Vault using the pod's K8s service account token
4. Vault verifies the token, checks the policy, returns the secrets
5. Init container writes secrets to `/vault/secrets/db` as `export DB_USER=...` lines
6. Your container's entrypoint sources that file, then starts the JVM

Your `config.edn` still reads `#env DB_USER` — nothing changes in your app.

---

## 2. Step 1 — Install Vault via Helm

### Install the Vault CLI

You need the `vault` CLI on your laptop for Steps 3-5 (configuring auth, policies, and seeding secrets):

```bash
brew tap hashicorp/tap
brew install hashicorp/tap/vault

vault --version   # should print >= 1.15
```

### Add the HashiCorp Helm repo

```bash
helm repo add hashicorp https://helm.releases.hashicorp.com
helm repo update
```

### Create the Vault namespace

```bash
kubectl create namespace vault
```

### Install Vault in standalone mode

For a single staging cluster, standalone mode with file storage is simpler than HA. Production can use HA with Raft later.

Create `vault-values.yaml` (a temporary file for the install, not part of your Helm chart):

```yaml
server:
  standalone:
    enabled: true
    config: |
      ui = true
      listener "tcp" {
        tls_disable = 1
        address = "[::]:8200"
        cluster_address = "[::]:8201"
      }
      storage "file" {
        path = "/vault/data"
      }
  dataStorage:
    size: 1Gi
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 500m
      memory: 512Mi

injector:
  enabled: true
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 500m
      memory: 512Mi
```

#### Why Vault needs persistent storage (and the app doesn't)

The `dataStorage: size: 1Gi` line above creates a **PersistentVolumeClaim (PVC)** — a durable disk that survives pod restarts. This is the only persistent storage in the entire project.

```
  vault-0 pod
  ┌──────────────────────────────────┐
  │  /vault/data/                    │
  │    ├── encrypted secrets         │
  │    └── audit.log                 │
  └──────────┬───────────────────────┘
             │ bound to
  ┌──────────▼───────────────────────┐
  │  PVC: data-vault-0               │
  │  Size: 1Gi                       │
  │  Created by: Vault Helm chart    │
  └──────────┬───────────────────────┘
             │ backed by
  ┌──────────▼───────────────────────┐
  │  GCE Persistent Disk (on GKE)    │
  │  or local storage (on Rancher)   │
  │                                  │
  │  Survives pod restarts           │
  │  Survives node restarts          │
  │  Deleted only if you delete      │
  │  the PVC explicitly              │
  └──────────────────────────────────┘
```

Without the PVC, every time vault-0 restarted you'd lose all stored secrets and have to re-initialize, re-unseal, and re-seed everything from scratch. The PVC means a restart only requires an unseal (or nothing, with auto-unseal via GCP KMS).

Every other component is stateless:

| Component | Storage approach | Why no persistent disk |
|-----------|-----------------|----------------------|
| **registry-api** | None | All state lives in Cloud SQL (external managed DB) |
| **cloud-sql-proxy** | None | Just a network proxy, holds no data |
| **vault-agent** | emptyDir (RAM) | Temporary secret files, recreated each pod start |
| **vault-0** | **PVC (1Gi)** | Must persist — this is the only stateful pod |

This is the ideal pattern: **stateless app pods + external managed services for state**. Stateless pods are simpler to scale, restart, and replace — K8s can reschedule them anywhere without worrying about data.

> **GKE Autopilot note:** Resource requests are set to 250m/512Mi — Autopilot's minimum without bursting. If you set lower values, Autopilot silently adjusts them upward.

```bash
helm install vault hashicorp/vault \
  --namespace vault \
  -f vault-values.yaml
```

### Wait for Vault pod to be running

```bash
kubectl -n vault get pods -w
```

You'll see `vault-0` in `0/1 Running` — that's expected. It's running but not ready because it's not yet initialized.

```
NAME      READY   STATUS    RESTARTS   AGE
vault-0   0/1     Running   0          30s
```

| Status | Meaning |
|--------|---------|
| `0/1 Running` | Pod is up but Vault isn't initialized yet — proceed to Step 2 |
| `Pending` | Autopilot is provisioning a node — wait 2-3 minutes |
| `CrashLoopBackOff` | Check `kubectl -n vault logs vault-0` for storage or config errors |

---

## 3. Step 2 — Initialize and unseal Vault

Vault starts sealed. You need to initialize it (once, ever) and unseal it (every pod restart).

### Initialize

```bash
kubectl -n vault exec vault-0 -- vault operator init \
  -key-shares=1 \
  -key-threshold=1 \
  -format=json > vault-init.json
```

> **IMPORTANT:** `vault-init.json` contains the root token and unseal key. Save this somewhere safe (a password manager, not git). You cannot recover these if lost.

`-key-shares=1 -key-threshold=1` creates a single unseal key for simplicity. For production, use 3-of-5 or higher.

### Extract the keys

```bash
UNSEAL_KEY=$(cat vault-init.json | jq -r '.unseal_keys_b64[0]')
ROOT_TOKEN=$(cat vault-init.json | jq -r '.root_token')

echo "Unseal key: $UNSEAL_KEY"
echo "Root token: $ROOT_TOKEN"
```

### Unseal

```bash
kubectl -n vault exec vault-0 -- vault operator unseal "$UNSEAL_KEY"
```

### Verify

```bash
kubectl -n vault exec vault-0 -- vault status
```

You should see:

```
Sealed          false
Initialized     true
```

And the pod should now show `1/1 Ready`:

```bash
kubectl -n vault get pods
# NAME      READY   STATUS    RESTARTS   AGE
# vault-0   1/1     Running   0          2m
```

| Problem | Fix |
|---------|-----|
| `Sealed: true` after unseal | Run the unseal command again — it may need the key re-entered |
| Pod `1/1` but `vault status` errors | Port-forward first: `kubectl -n vault port-forward svc/vault 8200:8200`, then `VAULT_ADDR=http://127.0.0.1:8200 vault status` |

> **Unseal after restarts:** Every time `vault-0` restarts (node preemption, upgrades), you must unseal it again. For production, consider Vault auto-unseal with GCP KMS — see [Day-2 operations](#12-day-2-operations).

---

## 4. Step 3 — Configure Kubernetes auth

This tells Vault how to verify that pods in your GKE cluster are who they claim to be.

### Port-forward to Vault

```bash
kubectl -n vault port-forward svc/vault 8200:8200 &
export VAULT_ADDR="http://127.0.0.1:8200"
export VAULT_TOKEN="$ROOT_TOKEN"    # from Step 2
```

### Enable the Kubernetes auth method

```bash
vault auth enable kubernetes
```

(If already enabled, this returns an error — that's fine.)

### Configure it with cluster details

```bash
K8S_HOST=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')

SA_TOKEN=$(kubectl -n vault exec vault-0 -- \
  cat /var/run/secrets/kubernetes.io/serviceaccount/token)

SA_CA=$(kubectl -n vault exec vault-0 -- \
  cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt)

vault write auth/kubernetes/config \
  kubernetes_host="$K8S_HOST" \
  token_reviewer_jwt="$SA_TOKEN" \
  kubernetes_ca_cert="$SA_CA"
```

**What this does:** Vault now trusts tokens issued by your GKE cluster's API server. When a pod presents its service account token, Vault asks the K8s API "is this real?" using the CA cert and reviewer token above.

| Error | Fix |
|-------|-----|
| `permission denied` on `vault write` | Check `VAULT_TOKEN` is set to the root token |
| `connection refused` | Ensure port-forward is still running: `kubectl -n vault port-forward svc/vault 8200:8200 &` |

---

## 5. Step 4 — Create policy and role

### Enable the KV v2 secrets engine

```bash
vault secrets enable -path=secret kv-v2
```

(If already enabled, this returns an error — that's fine.)

### Create the policy

The policy controls what `registry-api` pods can access. They can only read their own secrets — nothing else.

```bash
vault policy write registry-api - <<'POLICY'
path "secret/data/staging/registry-api/*" {
  capabilities = ["read"]
}
POLICY
```

> **KV v2 path note:** You store secrets at `secret/staging/registry-api/db` but the actual API path includes `/data/` — so the policy uses `secret/data/staging/...`. This is a common gotcha.

### Create the Kubernetes auth role

This maps the K8s service account `registry-api` in namespace `registry-api` to the Vault policy:

```bash
vault write auth/kubernetes/role/registry-api \
  bound_service_account_names=registry-api \
  bound_service_account_namespaces=registry-api \
  policies=registry-api \
  ttl=1h
```

**What this means:** When a pod running as service account `registry-api` in namespace `registry-api` authenticates to Vault, it gets a token valid for 1 hour with read access to `secret/data/staging/registry-api/*`.

### Verify the setup

```bash
# List policies
vault policy list
# Should include: default, registry-api

# Read the policy back
vault policy read registry-api

# List auth roles
vault list auth/kubernetes/role
# Should include: registry-api
```

---

## 6. Step 5 — Seed secrets from OpenTofu outputs

OpenTofu created the Cloud SQL database user and password in Phase 1. Pull them out and store them in Vault.

```bash
cd infra/environments/staging

DB_USER=$(tofu output -raw db_user)
DB_PASS=$(tofu output -raw db_password)

vault kv put secret/staging/registry-api/db \
  username="$DB_USER" \
  password="$DB_PASS"
```

### Verify the secret

```bash
vault kv get secret/staging/registry-api/db
```

You should see:

```
====== Data ======
Key         Value
---         -----
username    registry-api
password    <the generated password>
```

| Problem | Fix |
|---------|-----|
| `tofu output` errors | Make sure you're in the `infra/environments/staging` directory and have run `tofu apply` previously |
| `No value found at secret/data/staging/...` | You're using the wrong path — `vault kv get` uses the short path without `/data/` |

---

## 7. Step 6 — Update Helm values to enable Vault

The deployment template already has the Vault conditional from Phase 2. You just need to flip the switch.

### Edit `helm/registry-api/values-staging.yaml`

Change the vault section from:

```yaml
vault:
  enabled: false
```

to:

```yaml
vault:
  enabled: true
  secretPath: "secret/data/staging/registry-api/db"
```

That's it. The `role` defaults to `registry-api` in `values.yaml`.

### What this changes in the rendered deployment

When `vault.enabled = true`, the Helm template:

1. **Adds pod annotations** — tells the Vault Agent Injector to inject a sidecar:
   ```yaml
   annotations:
     vault.hashicorp.com/agent-inject: "true"
     vault.hashicorp.com/role: "registry-api"
     vault.hashicorp.com/agent-inject-secret-db: "secret/data/staging/registry-api/db"
     vault.hashicorp.com/agent-requests-cpu: "250m"
     vault.hashicorp.com/agent-requests-mem: "512Mi"
     vault.hashicorp.com/agent-inject-template-db: |
       {{- with secret "secret/data/staging/registry-api/db" -}}
       export DB_USER="{{ .Data.data.username }}"
       export DB_PASSWORD="{{ .Data.data.password }}"
       {{- end -}}
   ```

2. **Removes `env[].valueFrom.secretKeyRef`** — no more reading from the K8s Secret

3. **Adds `command`/`args`** — sources the Vault secret file before starting the JVM:
   ```yaml
   command: ["/bin/sh", "-c"]
   args:
     - |
       . /vault/secrets/db
       exec java -jar registry-api.jar
   ```

### Preview the change before deploying

```bash
cd helm/registry-api

helm template registry-api . \
  -f values-staging.yaml \
  --set image.tag=test \
  | grep -A 30 "kind: Deployment"
```

Verify you see the Vault annotations and the `command` block. Verify there's no `secretKeyRef` block.

---

## 8. Step 7 — Deploy and verify

### Option A: Push through CI/CD (recommended)

Commit the `values-staging.yaml` change and push to `main`. GitHub Actions deploys it automatically.

```bash
git add helm/registry-api/values-staging.yaml
git commit -m "Enable Vault for staging — swap K8s Secret for Vault Agent"
git push origin main
```

### Option B: Manual deploy (for testing)

```bash
IMAGE_TAG=$(kubectl get deployment registry-api -n registry-api \
  -o jsonpath='{.spec.template.spec.containers[0].image}' | cut -d: -f2)

helm upgrade --install registry-api helm/registry-api \
  -n registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag="$IMAGE_TAG"
```

### Watch the rollout

```bash
kubectl -n registry-api get pods -w
```

You'll see the old pods terminate and new ones start. The new pods will have **3 containers** instead of 2:
1. `registry-api` — your app
2. `cloud-sql-proxy` — the database proxy
3. `vault-agent` — the injected Vault sidecar

```bash
kubectl -n registry-api get pods
# NAME                            READY   STATUS    RESTARTS   AGE
# registry-api-7f8b9c4d5-abc12   3/3     Running   0          45s
# registry-api-7f8b9c4d5-def34   3/3     Running   0          30s
```

### Verify the app still works

```bash
# Port-forward and test
kubectl -n registry-api port-forward svc/registry-api 8080:80 &
curl -i http://localhost:8080/api/ping
curl -i http://localhost:8080/health
```

### Verify secrets are injected

```bash
# Check the vault-agent init container logged successfully
kubectl -n registry-api logs <pod-name> -c vault-agent-init

# Peek at the secret file (should show export lines)
kubectl -n registry-api exec <pod-name> -c registry-api -- cat /vault/secrets/db
# Output:
# export DB_USER="registry-api"
# export DB_PASSWORD="<password>"
```

### Verify env vars are set

```bash
kubectl -n registry-api exec <pod-name> -c registry-api -- env | grep DB_
# DB_USER=registry-api
# DB_PASSWORD=<password>
```

| Symptom | Cause | Fix |
|---------|-------|-----|
| Pod stuck in `Init:0/2` | Vault agent can't authenticate | Check Step 3 — K8s auth config. Check service account name matches. |
| `permission denied` in vault-agent-init logs | Policy doesn't cover the secret path | Check Step 4 — policy path must include `/data/` for KV v2 |
| `/vault/secrets/db` is empty | Template error | Check the annotation `agent-inject-template-db` renders valid Go template syntax |
| `3/3 Running` but app can't connect to DB | Secrets injected but not sourced | Verify the `command`/`args` in the deployment sources the file: `. /vault/secrets/db` |
| `2/3 Running` (vault-agent unhealthy) | Vault is sealed or unreachable | Run `kubectl -n vault exec vault-0 -- vault status` — if sealed, unseal it |

---

## 9. Step 8 — Clean up old K8s Secret

Once the app is running with Vault and you've verified it works:

```bash
kubectl -n registry-api delete secret registry-api-db-credentials
```

This removes the old unencrypted K8s Secret that Phase 2 created. It's no longer referenced by the deployment.

---

## 10. Step 9 — Security hardening

Now that Vault is working, lock down the security gaps. These steps harden the setup for production-readiness.

```
  Current gaps                              After hardening

  ┌──────────────┐  plain HTTP   ┌───────┐    ┌──────────────┐  TLS (HTTPS)  ┌───────┐
  │ vault-agent  │──────────────►│ Vault │    │ vault-agent  │──────────────►│ Vault │
  └──────────────┘  (in-cluster) └───────┘    └──────────────┘  (encrypted)  └───────┘

  No network policies                         NetworkPolicy restricts traffic
  (any pod can talk to Vault)                 (only registry-api pods → Vault)

  Root token still in use                     Root token revoked
  (god-mode access)                           (scoped admin token instead)

  No audit log enabled                        Audit log on (every access logged)

  No pod security hardening                   Containers: non-root, read-only FS,
  on Vault pods                               no privilege escalation
```

### 10.1 Enable TLS on Vault

Currently Vault listens on plain HTTP (`tls_disable = 1`). Even though traffic stays inside the cluster, TLS prevents a compromised pod from sniffing Vault traffic via the pod network.

#### Generate a TLS certificate

Use cert-manager if you have it, or create a self-signed cert with the K8s CA. Here we'll create a self-signed cert using OpenSSL:

```bash
# Create a directory for Vault TLS artifacts
mkdir -p vault-tls

# Generate a private key
openssl genrsa -out vault-tls/vault.key 4096

# Create a CSR config
cat > vault-tls/vault-csr.conf <<'EOF'
[req]
default_bits = 4096
prompt = no
default_md = sha256
distinguished_name = dn
req_extensions = v3_req

[dn]
CN = vault.vault.svc.cluster.local

[v3_req]
subjectAltName = @alt_names

[alt_names]
DNS.1 = vault
DNS.2 = vault.vault
DNS.3 = vault.vault.svc
DNS.4 = vault.vault.svc.cluster.local
DNS.5 = *.vault-internal
IP.1 = 127.0.0.1
EOF

# Generate the CSR
openssl req -new \
  -key vault-tls/vault.key \
  -out vault-tls/vault.csr \
  -config vault-tls/vault-csr.conf

# Self-sign the certificate (valid for 1 year)
openssl x509 -req \
  -in vault-tls/vault.csr \
  -signkey vault-tls/vault.key \
  -out vault-tls/vault.crt \
  -days 365 \
  -extensions v3_req \
  -extfile vault-tls/vault-csr.conf
```

#### Create a K8s Secret with the TLS cert

```bash
kubectl -n vault create secret tls vault-tls \
  --cert=vault-tls/vault.crt \
  --key=vault-tls/vault.key
```

#### Update `vault-values.yaml` to enable TLS

Replace the existing `vault-values.yaml` with:

```yaml
server:
  standalone:
    enabled: true
    config: |
      ui = true
      listener "tcp" {
        tls_disable    = 0
        address        = "[::]:8200"
        cluster_address = "[::]:8201"
        tls_cert_file  = "/vault/tls/tls.crt"
        tls_key_file   = "/vault/tls/tls.key"
      }
      storage "file" {
        path = "/vault/data"
      }
  extraVolumes:
    - type: secret
      name: vault-tls
  dataStorage:
    size: 1Gi
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 500m
      memory: 512Mi

injector:
  enabled: true
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 500m
      memory: 512Mi
  extraEnvironmentVars:
    AGENT_INJECT_VAULT_ADDR: "https://vault.vault.svc:8200"
    AGENT_INJECT_VAULT_CACERT_BYTES: ""   # populated below
```

Set the CA cert bytes for the injector (so vault-agent trusts the self-signed cert):

```bash
# Base64-encode the CA cert for the injector
VAULT_CA_B64=$(base64 < vault-tls/vault.crt)

# Upgrade Vault with TLS enabled
helm upgrade vault hashicorp/vault \
  --namespace vault \
  -f vault-values.yaml \
  --set "injector.extraEnvironmentVars.AGENT_INJECT_VAULT_CACERT_BYTES=$VAULT_CA_B64"
```

#### Update VAULT_ADDR for all future commands

After enabling TLS, all Vault CLI commands use HTTPS:

```bash
# Port-forward still works the same way
kubectl -n vault port-forward svc/vault 8200:8200 &

# But now use https:// (with -tls-skip-verify for the self-signed cert)
export VAULT_ADDR="https://127.0.0.1:8200"
export VAULT_SKIP_VERIFY=true   # because self-signed; remove if using cert-manager
```

> **Important:** After the Helm upgrade, Vault will restart and come back **sealed**. You must unseal it again:
> ```bash
> kubectl -n vault exec vault-0 -- vault operator unseal "$UNSEAL_KEY"
> ```

#### Update the Vault annotations in the Helm chart

Add the CA cert annotation to your deployment template so vault-agent trusts the TLS cert:

In `helm/registry-api/values-staging.yaml`, add:

```yaml
vault:
  enabled: true
  secretPath: "secret/data/staging/registry-api/db"
  tlsSecret: "vault-tls"    # reference to the TLS secret in the vault namespace
```

The vault-agent injector handles CA trust automatically when `AGENT_INJECT_VAULT_CACERT_BYTES` is set (done in the Helm upgrade above).

#### Verify TLS is working

```bash
# Vault status should work over HTTPS
kubectl -n vault exec vault-0 -- vault status

# Check the listener is TLS-enabled
kubectl -n vault exec vault-0 -- vault status -format=json | jq '.listener'
```

#### Clean up TLS artifacts

```bash
# Remove local key material — it's now stored in the K8s Secret
rm -rf vault-tls/

# Add to .gitignore as a safety net
echo "vault-tls/" >> .gitignore
```

### 10.2 Add NetworkPolicies

By default, any pod in the cluster can reach Vault. NetworkPolicies restrict which pods can talk to Vault over the network.

```
  Without NetworkPolicy              With NetworkPolicy

  ┌────────────┐                     ┌────────────┐
  │ any pod    │──► Vault  (open)    │ any pod    │──╳ Vault  (blocked)
  └────────────┘                     └────────────┘

  ┌────────────┐                     ┌────────────┐
  │ registry-  │──► Vault  (open)    │ registry-  │──► Vault  (allowed)
  │ api pods   │                     │ api pods   │
  └────────────┘                     └────────────┘
```

Create `k8s/network-policies.yaml`:

```yaml
# Only allow registry-api pods to reach Vault on port 8200
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: vault-ingress
  namespace: vault
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: vault
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: registry-api
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: vault
      ports:
        - protocol: TCP
          port: 8200
        - protocol: TCP
          port: 8201
---
# Restrict registry-api namespace — only allow egress to Vault and Cloud SQL proxy (localhost)
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: registry-api-egress
  namespace: registry-api
spec:
  podSelector:
    matchLabels:
      app: registry-api
  policyTypes:
    - Egress
  egress:
    # Allow DNS resolution
    - to: []
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
    # Allow traffic to Vault
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: vault
      ports:
        - protocol: TCP
          port: 8200
    # Allow traffic to the K8s API (for service account token review)
    - to:
        - ipBlock:
            cidr: 0.0.0.0/0
      ports:
        - protocol: TCP
          port: 443
```

Apply:

```bash
# Label the registry-api namespace (needed for namespaceSelector)
kubectl label namespace registry-api kubernetes.io/metadata.name=registry-api --overwrite
kubectl label namespace vault kubernetes.io/metadata.name=vault --overwrite

kubectl apply -f k8s/network-policies.yaml
```

#### Verify

```bash
# From a registry-api pod, Vault should be reachable
kubectl -n registry-api exec <pod-name> -c registry-api -- \
  wget -qO- --timeout=3 https://vault.vault.svc:8200/v1/sys/health || echo "expected if no wget"

# From a different namespace, Vault should be blocked
kubectl run test-pod --image=busybox -n default --rm -it -- \
  wget -qO- --timeout=3 http://vault.vault.svc:8200/v1/sys/health
# Should timeout/fail
```

> **GKE Autopilot note:** GKE Autopilot supports NetworkPolicies natively — no extra CNI plugin needed. The `kubernetes.io/metadata.name` label is automatically set on namespaces in recent K8s versions.

### 10.3 Revoke the Vault root token

The root token has unlimited access to Vault. Once configuration is done, revoke it and create a scoped admin token for day-2 operations.

#### Create an admin policy

```bash
export VAULT_ADDR="https://127.0.0.1:8200"
export VAULT_SKIP_VERIFY=true
export VAULT_TOKEN="$ROOT_TOKEN"

vault policy write vault-admin - <<'POLICY'
# Full access to secrets engine
path "secret/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

# Manage auth methods and policies
path "auth/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}
path "sys/auth/*" {
  capabilities = ["create", "read", "update", "delete", "sudo"]
}
path "sys/policies/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

# Manage secrets engines
path "sys/mounts/*" {
  capabilities = ["create", "read", "update", "delete", "list"]
}

# View health and status
path "sys/health" {
  capabilities = ["read"]
}
path "sys/seal" {
  capabilities = ["read", "update", "sudo"]
}

# Audit log management
path "sys/audit/*" {
  capabilities = ["create", "read", "update", "delete", "list", "sudo"]
}
POLICY
```

#### Create an admin token

```bash
ADMIN_TOKEN=$(vault token create \
  -policy=vault-admin \
  -ttl=768h \
  -format=json | jq -r '.auth.client_token')

echo "Admin token: $ADMIN_TOKEN"
# Save this in your password manager alongside the unseal key
```

#### Revoke the root token

```bash
vault token revoke "$ROOT_TOKEN"
```

From now on, use `$ADMIN_TOKEN` for day-2 operations. If you ever need root again, you can generate a new root token using the unseal key:

```bash
vault operator generate-root -init
# Follow the prompts with your unseal key
```

### 10.4 Enable audit logging

Audit logging should be enabled right after Vault setup, not deferred to "later." Every secret read/write is recorded.

```bash
export VAULT_TOKEN="$ADMIN_TOKEN"

# Enable file-based audit log
vault audit enable file file_path=/vault/data/audit.log
```

For production, use stdout so logs go to Cloud Logging:

```bash
vault audit enable -path=stdout file file_path=stdout
```

#### Verify

```bash
# Read a secret — this should generate an audit entry
vault kv get secret/staging/registry-api/db

# Check the log
kubectl -n vault exec vault-0 -- tail -5 /vault/data/audit.log | jq '.request.path'
# Should show: "secret/data/staging/registry-api/db"
```

### 10.5 Harden Vault pod security

Add security context to the Vault pods — run as non-root, read-only filesystem (except the data and TLS volumes), and no privilege escalation.

Update `vault-values.yaml` — add these fields under the `server` key:

```yaml
server:
  securityContext:
    runAsNonRoot: true
    runAsUser: 100
    runAsGroup: 1000
    fsGroup: 1000
  containerSecurityContext:
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop:
        - ALL
```

Then upgrade:

```bash
helm upgrade vault hashicorp/vault \
  --namespace vault \
  -f vault-values.yaml \
  --set "injector.extraEnvironmentVars.AGENT_INJECT_VAULT_CACERT_BYTES=$VAULT_CA_B64"
```

> **Remember:** Vault restarts sealed after upgrade — unseal it again.

### Security hardening checklist

```
TLS:
  [ ] Self-signed cert created and stored as K8s Secret
  [ ] Vault listener has tls_disable = 0
  [ ] vault-agent injector configured with CA cert
  [ ] VAULT_ADDR uses https:// in all scripts
  [ ] Local TLS key material deleted

Network Policies:
  [ ] vault-ingress policy: only registry-api + vault namespaces can reach Vault
  [ ] registry-api-egress policy: app can only reach Vault, DNS, and K8s API
  [ ] Verified: pods in other namespaces cannot reach Vault

Root Token:
  [ ] vault-admin policy created
  [ ] Admin token generated and saved in password manager
  [ ] Root token revoked
  [ ] vault-configure.sh updated to use admin token

Audit:
  [ ] Audit log enabled (file or stdout)
  [ ] Verified: secret reads appear in audit log

Pod Security:
  [ ] Vault runs as non-root (runAsUser: 100)
  [ ] Read-only root filesystem
  [ ] No privilege escalation
  [ ] All capabilities dropped
```

---

## 11. The configure script (all-in-one)

For convenience (and for production later), here's the full script that does Steps 3-5 in one shot.

Create `scripts/vault-configure.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/vault-configure.sh <environment> <vault-root-token>
# Example: ./scripts/vault-configure.sh staging hvs.abc123...
#
# Prerequisites:
#   - Vault installed and unsealed (Steps 1-2)
#   - kubectl context set to the correct cluster
#   - OpenTofu applied for this environment (Phase 1)

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
echo ""

# 1. Enable KV v2 secrets engine (idempotent)
echo "→ Enabling KV v2 secrets engine..."
vault secrets enable -path=secret kv-v2 2>/dev/null || true

# 2. Enable Kubernetes auth method (idempotent)
echo "→ Enabling Kubernetes auth..."
vault auth enable kubernetes 2>/dev/null || true

# 3. Configure K8s auth
echo "→ Configuring Kubernetes auth backend..."
K8S_HOST=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
SA_TOKEN=$(kubectl -n vault exec vault-0 -- \
  cat /var/run/secrets/kubernetes.io/serviceaccount/token)
SA_CA=$(kubectl -n vault exec vault-0 -- \
  cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt)

vault write auth/kubernetes/config \
  kubernetes_host="$K8S_HOST" \
  token_reviewer_jwt="$SA_TOKEN" \
  kubernetes_ca_cert="$SA_CA"

# 4. Create policy
echo "→ Writing policy: registry-api..."
vault policy write registry-api - <<POLICY
path "secret/data/${ENV}/registry-api/*" {
  capabilities = ["read"]
}
POLICY

# 5. Create K8s auth role
echo "→ Creating auth role: registry-api..."
vault write auth/kubernetes/role/registry-api \
  bound_service_account_names=registry-api \
  bound_service_account_namespaces=registry-api \
  policies=registry-api \
  ttl=1h

# 6. Seed DB credentials from OpenTofu outputs
echo "→ Seeding DB credentials from OpenTofu..."
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
echo ""
echo "Next: set vault.enabled=true in values-${ENV}.yaml and deploy."
```

Make it executable:

```bash
chmod +x scripts/vault-configure.sh
```

---

## 12. How it works at runtime

Once deployed, here's the full sequence every time a new pod starts:

```
1. K8s scheduler creates pod
      │
2. Vault Agent Injector webhook intercepts the pod create
   (because it sees vault.hashicorp.com/agent-inject: "true")
      │
3. Webhook mutates the pod spec:
   - Adds init container: vault-agent-init
   - Adds sidecar container: vault-agent
   - Adds shared volume: vault-secrets (emptyDir)
      │
4. Init container (vault-agent-init) runs first:
   a. Reads the pod's K8s service account token
   b. Sends it to Vault: POST /v1/auth/kubernetes/login
   c. Vault asks the K8s API: "is this token valid for SA
      'registry-api' in namespace 'registry-api'?"
   d. K8s says yes → Vault returns a Vault token (TTL 1h)
   e. vault-agent uses the token to GET the secret
   f. Renders the template to /vault/secrets/db:
        export DB_USER="registry-api"
        export DB_PASSWORD="s3cret..."
   g. Init container exits successfully
      │
5. Your container starts:
   command: ["/bin/sh", "-c"]
   args: [". /vault/secrets/db\nexec java -jar registry-api.jar"]
   - Sources /vault/secrets/db → sets DB_USER, DB_PASSWORD in env
   - exec replaces shell with JVM
      │
6. Sidecar (vault-agent) keeps running:
   - Watches for secret changes in Vault
   - Refreshes the token before TTL expires
   - Rewrites /vault/secrets/db if the secret changes
   (Your app would need to re-read env vars to pick up changes
    — for DB creds this rarely matters)
```

---

## 13. Day-2 operations

### Rotate a DB password

```bash
# 1. Update the password in Cloud SQL (via GCP console or gcloud)
# 2. Update Vault
export VAULT_ADDR="https://127.0.0.1:8200"
export VAULT_SKIP_VERIFY=true   # self-signed cert
kubectl -n vault port-forward svc/vault 8200:8200 &
vault kv put secret/staging/registry-api/db \
  username="registry-api" \
  password="new-password-here"

# 3. Restart pods to pick up the new secret
kubectl -n registry-api rollout restart deployment/registry-api
```

### Add a new secret (e.g., API key)

```bash
# 1. Store it
vault kv put secret/staging/registry-api/api-keys \
  stripe-key="sk_test_..."

# 2. Update the policy to allow the new path (if it doesn't already match the wildcard)
#    The existing policy uses /* so it already covers any sub-path under registry-api/

# 3. Add a new annotation to the deployment template for the new secret
#    vault.hashicorp.com/agent-inject-secret-api-keys: "secret/data/staging/registry-api/api-keys"
#    vault.hashicorp.com/agent-inject-template-api-keys: |
#      {{- with secret "secret/data/staging/registry-api/api-keys" -}}
#      export STRIPE_KEY="{{ .Data.data.stripe-key }}"
#      {{- end -}}

# 4. Update the command to source both files:
#    . /vault/secrets/db
#    . /vault/secrets/api-keys
#    exec java -jar registry-api.jar
```

### Unseal after a pod restart

If the Vault pod restarts (node preemption, upgrades), it comes back sealed:

```bash
# Check status
kubectl -n vault exec vault-0 -- vault status
# If Sealed: true

# Unseal
kubectl -n vault exec vault-0 -- vault operator unseal "$UNSEAL_KEY"
```

### Auto-unseal with GCP KMS (production recommendation)

Manual unsealing is fine for staging but risky for production — if the pod restarts at 3 AM, your app can't get secrets until someone unseals it.

Add to `vault-values.yaml` for production:

```yaml
server:
  standalone:
    config: |
      seal "gcpckms" {
        project     = "YOUR_PROJECT_ID"
        region      = "europe-west2"
        key_ring    = "vault-keyring"
        crypto_key  = "vault-unseal"
      }
```

Create the KMS key:

```bash
gcloud kms keyrings create vault-keyring --location=europe-west2
gcloud kms keys create vault-unseal \
  --keyring=vault-keyring \
  --location=europe-west2 \
  --purpose=encryption
```

With auto-unseal, Vault automatically unseals on pod restart using GCP KMS — no manual intervention.

### Enable the audit log

Enable the audit log (do this once after initial setup). Use the `file` device writing to Vault's data directory (which already has a persistent volume):

```bash
vault audit enable file file_path=/vault/data/audit.log
```

View recent entries:

```bash
kubectl -n vault exec vault-0 -- tail -20 /vault/data/audit.log | jq .
```

Each entry shows who accessed what secret, when, and whether it was allowed or denied.

> **Note:** The audit log grows over time. For production, consider using the `stdout` audit device instead (`vault audit enable file file_path=stdout`), which sends entries to container logs where they're captured by your cluster's log pipeline (Cloud Logging on GKE).

---

## 14. Troubleshooting

### Pod stuck in `Init:0/2`

The vault-agent init container can't authenticate.

```bash
# Check init container logs
kubectl -n registry-api logs <pod-name> -c vault-agent-init
```

| Log message | Fix |
|-------------|-----|
| `error authenticating` | Service account name/namespace doesn't match the Vault role. Verify: `vault read auth/kubernetes/role/registry-api` |
| `connection refused` | Vault pod isn't running or is sealed. Check: `kubectl -n vault get pods` and `vault status` |
| `permission denied` | The K8s auth config is wrong. Re-run Step 3 (configure K8s auth) |

### `permission denied` reading secrets

```bash
# Check what the policy allows
vault policy read registry-api

# Test with a manual token
vault write auth/kubernetes/login role=registry-api jwt=$(kubectl -n registry-api exec <pod> -- cat /var/run/secrets/kubernetes.io/serviceaccount/token)
```

Common cause: the secret path in the annotation doesn't match the policy path. Remember KV v2 uses `/data/` in the API path.

### Vault sealed after node restart

```bash
kubectl -n vault get pods
# vault-0   0/1     Running

kubectl -n vault exec vault-0 -- vault operator unseal "$UNSEAL_KEY"
```

All pods depending on Vault will fail their init containers while Vault is sealed. Once unsealed, they'll automatically retry and start.

### Injector webhook not firing

Pods deploy without the vault-agent sidecar (only 2/2 containers instead of 3/3).

```bash
# Check the injector is running
kubectl -n vault get pods -l app.kubernetes.io/name=vault-agent-injector
# Should show 1/1 Running

# Check the mutating webhook exists
kubectl get mutatingwebhookconfigurations | grep vault
# Should show vault-agent-injector-cfg

# Verify pod annotations are correct (check for typos)
kubectl -n registry-api get pod <pod-name> -o yaml | grep vault.hashicorp
```

### Debugging the secret template

If `/vault/secrets/db` is empty or has wrong content:

```bash
# Check what the init container rendered
kubectl -n registry-api exec <pod-name> -c registry-api -- cat /vault/secrets/db

# Read the secret directly from Vault to compare
vault kv get secret/staging/registry-api/db
```

Common mistake: the template references `.Data.data.username` (two levels of `.data`) because KV v2 wraps data in a `data` envelope. For KV v2, it's always `.Data.data.<key>`.

---

## 15. Checklist — Phase 4 complete

```
Infrastructure:
  [ ] Vault installed in vault namespace
  [ ] Vault initialized and unsealed
  [ ] vault-agent-injector running

Authentication:
  [ ] Kubernetes auth method enabled and configured
  [ ] Auth role "registry-api" created

Secrets:
  [ ] KV v2 engine enabled at secret/
  [ ] Policy "registry-api" grants read on secret/data/staging/registry-api/*
  [ ] DB credentials seeded at secret/staging/registry-api/db

Application:
  [ ] values-staging.yaml has vault.enabled: true
  [ ] Pods show 3/3 containers (app + cloud-sql-proxy + vault-agent)
  [ ] /vault/secrets/db contains export lines
  [ ] App responds on /api/ping and /health
  [ ] DB queries work (app can reach Cloud SQL through proxy)

Cleanup:
  [ ] Old K8s Secret (registry-api-db-credentials) deleted
  [ ] vault-init.json saved somewhere safe (not in git)

Security hardening:
  [ ] Vault listener uses TLS (tls_disable = 0)
  [ ] NetworkPolicies restrict Vault access to registry-api pods only
  [ ] Root token revoked, admin token in use
  [ ] Audit logging enabled
  [ ] Vault pods run as non-root with read-only filesystem

Scripts:
  [ ] scripts/vault-configure.sh created and tested
```

**You now have encrypted, audited, policy-controlled secrets.**

**Next:** [Phase 5 — Observability with Grafana LGTM stack](K8s-phase5.md)

---

## Files created in this phase

```
scripts/
└── vault-configure.sh       # All-in-one Vault setup (Steps 3-5)

helm/registry-api/
├── values-staging.yaml       # Modified: vault.enabled: true, tlsSecret added
└── (no new templates — the deployment template from Phase 2 already handles Vault)

k8s/
└── network-policies.yaml     # NetworkPolicies for Vault + registry-api

vault-values.yaml             # Vault Helm config (TLS-enabled, hardened security context)
```
