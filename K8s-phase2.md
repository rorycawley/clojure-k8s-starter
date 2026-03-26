# Phase 2: Helm Chart + Deploy to Staging

Write the Helm chart, deploy registry-api to the GKE staging cluster, and verify it connects to Cloud SQL.

**Prerequisite:** Phase 1 complete — GKE cluster running, Cloud SQL ready, Artifact Registry working, `kubectl` connected to `gke-staging`.

**End state:** registry-api running on staging with 2 pods, connected to Cloud SQL via the proxy sidecar, reachable via the Ingress static IP. DB credentials stored in K8s Secrets (Vault comes in Phase 4).

---

## Table of Contents

1. [What you're building](#1-what-youre-building)
2. [Step 1 — Create the chart skeleton](#2-step-1--create-the-chart-skeleton)
3. [Step 2 — Chart.yaml](#3-step-2--chartyaml)
4. [Step 3 — _helpers.tpl](#4-step-3--helperstpl)
5. [Step 4 — Templates](#5-step-4--templates)
6. [Step 5 — Values files](#6-step-5--values-files)
7. [Step 6 — Create the K8s Secret for DB credentials](#7-step-6--create-the-k8s-secret)
8. [Step 7 — Lint and render locally](#8-step-7--lint-and-render)
9. [Step 8 — Build and push the Docker image](#9-step-8--build-and-push)
10. [Step 9 — Deploy with Helm](#10-step-9--deploy)
11. [Step 10 — Verify the deployment](#11-step-10--verify)
12. [Step 11 — Add bb tasks for context switching](#12-step-11--bb-tasks)
13. [Troubleshooting](#13-troubleshooting)
14. [Checklist — Phase 2 complete](#14-checklist--phase-2-complete)

---

## 1. What you're building

Phase 2 uses **K8s Secrets** for DB credentials instead of Vault. This simplifies the first deployment — Vault adds complexity that's better layered on after the app is running.

```
┌─ Pod (Phase 2 — no Vault) ─────────────────────────────────┐
│                                                              │
│  ┌─ registry-api container ────────────────────────────────┐ │
│  │                                                         │ │
│  │  image: .../registry-api:<tag>                         │ │
│  │                                                         │ │
│  │  env from ConfigMap:                                    │ │
│  │    APP_ENV, PORT, DB_HOST, DB_PORT, DB_NAME            │ │
│  │                                                         │ │
│  │  env from K8s Secret:     ← (swapped to Vault in Ph4)  │ │
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
│  │  image: gcr.io/cloud-sql-connectors/cloud-sql-proxy    │ │
│  │  Listens on 127.0.0.1:5432                              │ │
│  │  Authenticates to Cloud SQL via Workload Identity       │ │
│  │  Encrypts traffic — no SSL config needed in the app     │ │
│  └─────────────────────────────────────────────────────────┘ │
│                                                              │
│  ServiceAccount: registry-api                                │
│    annotated → GCP SA: registry-api-staging@...             │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

The only difference from Phase 4 is where `DB_USER` and `DB_PASSWORD` come from — K8s Secret now, Vault later. The app code is identical.

---

## 2. Step 1 — Create the chart skeleton

```bash
mkdir -p helm/registry-api/templates
```

You'll create these files:

```
helm/registry-api/
├── Chart.yaml
├── values.yaml
├── values-staging.yaml
├── values-prod.yaml
└── templates/
    ├── _helpers.tpl
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── configmap.yaml
    └── serviceaccount.yaml
```

---

## 3. Step 2 — Chart.yaml

Create `helm/registry-api/Chart.yaml`:

```yaml
apiVersion: v2
name: registry-api
description: Registry API — Clojure service on GKE
type: application
version: 0.1.0        # chart version (bump when you change templates)
appVersion: "0.1.0"   # app version (informational)
```

---

## 4. Step 3 — _helpers.tpl

Create `helm/registry-api/templates/_helpers.tpl`:

```yaml
{{/*
Common labels applied to every resource.
*/}}
{{- define "registry-api.labels" -}}
app: {{ .Release.Name }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels — used by Deployment and Service to match pods.
Must be a subset of the full labels and must not change between upgrades.
*/}}
{{- define "registry-api.selectorLabels" -}}
app: {{ .Release.Name }}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
```

---

## 5. Step 4 — Templates

### 5.1 deployment.yaml

Create `helm/registry-api/templates/deployment.yaml`:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .Release.Name }}
  labels:
    {{- include "registry-api.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.replicaCount }}
  selector:
    matchLabels:
      {{- include "registry-api.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "registry-api.selectorLabels" . | nindent 8 }}
      {{- if .Values.vault.enabled }}
      annotations:
        vault.hashicorp.com/agent-inject: "true"
        vault.hashicorp.com/role: {{ .Values.vault.role | quote }}
        vault.hashicorp.com/agent-inject-secret-db: {{ .Values.vault.secretPath | quote }}
        vault.hashicorp.com/agent-requests-cpu: "250m"
        vault.hashicorp.com/agent-requests-mem: "512Mi"
        vault.hashicorp.com/agent-inject-template-db: |
          {{`{{- with secret "`}}{{ .Values.vault.secretPath }}{{`" -}}`}}
          export DB_USER="{{ `{{ .Data.data.username }}` }}"
          export DB_PASSWORD="{{ `{{ .Data.data.password }}` }}"
          {{`{{- end -}}`}}
      {{- end }}
    spec:
      serviceAccountName: {{ .Values.serviceAccount.name }}
      containers:
        - name: registry-api
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          ports:
            - containerPort: {{ .Values.config.port | int }}
          envFrom:
            - configMapRef:
                name: {{ .Release.Name }}-config
          {{- if not .Values.vault.enabled }}
          # Phase 2: DB creds from K8s Secret. Phase 4 swaps this for Vault.
          env:
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: {{ .Release.Name }}-db-credentials
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ .Release.Name }}-db-credentials
                  key: password
          {{- end }}
          {{- if .Values.vault.enabled }}
          # Phase 4: source Vault secrets before starting the app
          command: ["/bin/sh", "-c"]
          args:
            - |
              . /vault/secrets/db
              exec java -jar registry-api.jar
          {{- end }}
          startupProbe:
            httpGet:
              path: /health
              port: {{ .Values.config.port | int }}
            failureThreshold: 30
            periodSeconds: 2
          readinessProbe:
            httpGet:
              path: /ready
              port: {{ .Values.config.port | int }}
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /health
              port: {{ .Values.config.port | int }}
            initialDelaySeconds: 5
            periodSeconds: 10
          resources:
            requests:
              cpu: {{ .Values.resources.requests.cpu }}
              memory: {{ .Values.resources.requests.memory }}
            limits:
              cpu: {{ .Values.resources.limits.cpu }}
              memory: {{ .Values.resources.limits.memory }}
        {{- if .Values.cloudSqlProxy.enabled }}
        - name: cloud-sql-proxy
          image: gcr.io/cloud-sql-connectors/cloud-sql-proxy:2.14.1
          args:
            - "--structured-logs"
            - "--port=5432"
            - "{{ .Values.cloudSqlProxy.instanceConnectionName }}"
          ports:
            - containerPort: 5432
              protocol: TCP
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
          securityContext:
            runAsNonRoot: true
        {{- end }}
```

**Key design decisions:**
- When `vault.enabled = false` (Phase 2): DB creds come from a K8s Secret via `env[].valueFrom.secretKeyRef`
- When `vault.enabled = true` (Phase 4): Vault annotations inject a sidecar that writes secrets to a file, and the `command` sources it before starting the JVM
- The Cloud SQL proxy sidecar is controlled by `cloudSqlProxy.enabled`
- Probes match your existing `k8s/deployment.yaml`

### 5.2 service.yaml

#### How pods communicate — Services and K8s DNS

Pods are ephemeral — they get created, destroyed, and rescheduled constantly. Each pod gets a random IP that changes every time it restarts. So you can't hard-code a pod's IP address to talk to it. This is the problem **Services** solve.

A concrete example from this project: your **registry-api pod** needs to talk to the **vault-0 pod** (to fetch secrets in Phase 4). The vault-agent init container inside registry-api calls Vault's API on port 8200. But vault-0's IP changes every time it restarts — so how does registry-api find it?

```
  The problem: pod IPs are ephemeral

  registry-api pod                            vault pod
  ┌──────────────┐                          ┌──────────────┐
  │ vault-agent  │──► 10.1.2.47:8200  ──╳   │ vault-0      │  ← restarted,
  │ (init        │   (old IP, now dead)      │ NEW IP:      │     got a new IP
  │  container)  │                           │ 10.1.5.92    │
  └──────────────┘                           └──────────────┘

  The solution: a Service provides a stable address

  registry-api pod         stable DNS name       ┌─────────────────┐
  ┌──────────────┐                               │ Service "vault" │
  │ vault-agent  │──► vault.vault.svc ──────────►│ ClusterIP:      │
  │ (init        │    (never changes)            │ 10.2.0.15       │
  │  container)  │                               └────────┬────────┘
  └──────────────┘                                        │ routes to
                                                 ┌────────▼────────┐
                                                 │ vault-0 pod     │
                                                 │ (whatever its   │
                                                 │  current IP is) │
                                                 └─────────────────┘
```

A **Service** gives a set of pods:
1. **A stable ClusterIP** — a virtual IP that never changes, even as pods come and go
2. **A DNS name** — K8s automatically creates a DNS entry so other pods can find it by name
3. **Load balancing** — if there are multiple pods, traffic is spread across them

So when vault-agent inside registry-api calls `vault.vault.svc:8200`, K8s DNS resolves it to the Service's ClusterIP, which routes to the vault-0 pod — regardless of what IP vault-0 has today.

#### K8s DNS naming convention

Every Service gets a DNS entry in this format:

```
  ClusterIP service DNS:
  <service-name>.<namespace>.svc.cluster.local

  Examples from this project:

  registry-api.registry-api.svc.cluster.local   ← your app
  vault.vault.svc.cluster.local                  ← Vault server
  rabbitmq.rabbitmq.svc.cluster.local            ← RabbitMQ (Phase 6)
  mimir-gateway.observability.svc.cluster.local   ← Mimir gateway (Phase 5)

  You can also use short names:
  vault.vault.svc        ← works (drops cluster.local)
  vault.vault            ← works (drops svc.cluster.local)
  vault                  ← works ONLY from the same namespace

  Headless service DNS (StatefulSet pods):
  <pod-name>.<headless-svc>.<namespace>.svc.cluster.local

  Example:
  rabbitmq-0.rabbitmq-headless.rabbitmq.svc.cluster.local  ← specific pod
  (used by Erlang clustering — nodes must find each other by name)
```

#### The three layers of external access

```
  How traffic reaches your registry-api pods from the internet:

  Internet (your browser / curl)
     │
     ▼
  ┌────────────────────────────────────┐
  │  Ingress: registry-api             │  Layer 3: external access
  │  (creates a GCP Load Balancer)     │  Routes internet traffic by
  │  Static IP: 34.x.x.x              │  host/path to a Service
  └──────────────┬─────────────────────┘
                 │
                 ▼
  ┌────────────────────────────────────┐
  │  Service: registry-api             │  Layer 2: stable internal address
  │  ClusterIP (internal only)         │  Receives traffic from Ingress
  │  port: 80 → targetPort: 8080      │  or from other pods
  └──────────────┬─────────────────────┘
                 │ routes to pods matching
                 │ selector labels
                 ▼
  ┌──────────────────┐  ┌──────────────────┐
  │  registry-api    │  │  registry-api    │  Layer 1: actual containers
  │  pod (replica 1) │  │  pod (replica 2) │
  │  :8080           │  │  :8080           │
  └──────────────────┘  └──────────────────┘
```

Traffic patterns in this project:

- **Internet → registry-api pods**: request hits the Ingress static IP → Ingress routes to the registry-api Service → Service load-balances across your registry-api pod replicas
- **registry-api pod → vault-0 pod**: vault-agent calls `vault.vault.svc:8200` — the Vault Service routes it to the vault-0 pod (Phase 4)
- **registry-api pod → rabbitmq-0 pod**: app calls `rabbitmq.rabbitmq.svc:5672` — the RabbitMQ Service routes it to the broker (Phase 6)
- **OTel Collector → Mimir/Tempo/Loki**: collector pushes telemetry to `mimir-gateway.observability.svc:80`, `tempo.observability.svc:4318`, etc. (Phase 5)
- **Your laptop** (for debugging): `kubectl port-forward svc/registry-api 8080:80` tunnels directly to the Service, bypassing Ingress

#### Service types

| Type | What it does | Used in this project |
|------|-------------|---------------------|
| **ClusterIP** (default) | Internal only — reachable by other pods, not from outside | Every service — `registry-api`, `vault`, `rabbitmq`, all observability services |
| **Headless** (ClusterIP: None) | No virtual IP — DNS resolves directly to individual pod IPs | `rabbitmq-headless` (Phase 6) — StatefulSet pods need stable per-pod DNS |
| **NodePort** | Opens a port on every node — rarely used directly | No |
| **LoadBalancer** | Creates a cloud load balancer — one per Service | No (we use Ingress instead, which is more efficient for multiple services) |

```
  How traffic flows — all service types in this project

  External traffic:

  Internet ──► Ingress (GCE LB) ──► ClusterIP: registry-api ──► app pods
                                     :80 → :8080

  Internal pod-to-pod traffic (all ClusterIP):

  app pod ──► ClusterIP: vault.vault.svc:8200 ──────────────► vault-0
  app pod ──► ClusterIP: rabbitmq.rabbitmq.svc:5672 ────────► rabbitmq-0
  otel    ──► ClusterIP: mimir-gateway.observability.svc:80 ─► mimir pods
  otel    ──► ClusterIP: tempo.observability.svc:4318 ───────► tempo pod
  otel    ──► ClusterIP: loki.observability.svc:3100 ────────► loki pod

  StatefulSet per-pod DNS (Headless):

  rabbitmq-1 ──► rabbitmq-0.rabbitmq-headless.rabbitmq.svc ──► rabbitmq-0
                 (Headless gives each pod a stable DNS name,
                  needed for Erlang clustering in multi-replica setups)

  The pattern: external traffic enters through Ingress,
  everything internal uses ClusterIP. Headless is only
  for StatefulSets that need per-pod addressability.
```

> **Why no LoadBalancer services?** Each LoadBalancer creates a separate cloud load balancer (~$18/month on GCP). With one Ingress handling all external routing, you pay for just one load balancer no matter how many services sit behind it. Internal services (Vault, RabbitMQ, observability) don't need external access at all — ClusterIP keeps them cluster-internal by design.

The `selector` field in the Service spec is how K8s knows which pods belong to the Service — it matches against pod labels. That's why the deployment template and service template share the same `selectorLabels`:

Create `helm/registry-api/templates/service.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ .Release.Name }}
  labels:
    {{- include "registry-api.labels" . | nindent 4 }}
spec:
  selector:
    {{- include "registry-api.selectorLabels" . | nindent 4 }}
  ports:
    - protocol: TCP
      port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.targetPort }}
```

### 5.3 ingress.yaml

Create `helm/registry-api/templates/ingress.yaml`:

```yaml
{{- if .Values.ingress.enabled }}
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: {{ .Release.Name }}
  labels:
    {{- include "registry-api.labels" . | nindent 4 }}
  {{- with .Values.ingress.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  {{- if .Values.ingress.className }}
  ingressClassName: {{ .Values.ingress.className }}
  {{- end }}
  rules:
    - {{- if .Values.ingress.host }}
      host: {{ .Values.ingress.host }}
      {{- end }}
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: {{ .Release.Name }}
                port:
                  number: {{ .Values.service.port }}
{{- end }}
```

### 5.4 configmap.yaml

A ConfigMap stores **non-secret, environment-specific configuration** as key-value pairs. Each key becomes an environment variable in your pod. Your Clojure app reads them with `#env` in `config.edn` — it doesn't know or care whether they came from a ConfigMap, a Secret, or Vault.

```
  ConfigMap vs Secret vs Vault — what goes where

  ┌──────────────────────────────────┐
  │          ConfigMap               │  Non-secret config (plain text in cluster)
  │  APP_ENV, PORT, DB_HOST,        │  Visible to anyone with kubectl access
  │  DB_PORT, DB_NAME, DB_SSLMODE   │
  └──────────────┬───────────────────┘
                 │ envFrom: configMapRef
                 ▼
  ┌──────────────────────────────────┐
  │          Your Pod                │  Sees all of these as env vars
  └──────────────▲───────────────────┘
                 │ env: secretKeyRef (Phase 2)
                 │ /vault/secrets/db (Phase 4)
  ┌──────────────┴───────────────────┐
  │    Secret / Vault                │  Credentials: DB_USER, DB_PASSWORD
  │    (encrypted, access-controlled)│
  └──────────────────────────────────┘

  Rule of thumb:
  ┌──────────────────────────────────┬─────────────────────┐
  │ Type of data                     │ Where it goes       │
  ├──────────────────────────────────┼─────────────────────┤
  │ App config (ports, hostnames,    │ ConfigMap           │
  │   feature flags)                 │                     │
  │ Credentials (passwords, tokens)  │ Vault (or K8s       │
  │                                  │   Secret as fallback│
  │ Static files (nginx.conf, certs) │ ConfigMap mounted   │
  │                                  │   as a volume       │
  └──────────────────────────────────┴─────────────────────┘
```

**Key things to understand:**
- **Not encrypted** — stored as plain text in etcd. Never put passwords or API keys here.
- **Namespace-scoped** — only visible to pods in the same namespace.
- **Changes don't auto-restart pods** — update a ConfigMap and existing pods keep old values. Run `kubectl rollout restart deployment/registry-api` to pick up changes.
- **Two ways to consume**: `envFrom` (all keys as env vars, used here) or mounting as files (`volumeMounts`, better for config files like `nginx.conf`).

Create `helm/registry-api/templates/configmap.yaml`:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ .Release.Name }}-config
  labels:
    {{- include "registry-api.labels" . | nindent 4 }}
data:
  APP_ENV: {{ .Values.config.appEnv | quote }}
  PORT: {{ .Values.config.port | quote }}
  DB_HOST: {{ .Values.config.dbHost | quote }}
  DB_PORT: {{ .Values.config.dbPort | quote }}
  DB_NAME: {{ .Values.config.dbName | quote }}
  DB_SSLMODE: {{ .Values.config.dbSslMode | quote }}
  OTEL_ENABLED: {{ .Values.config.otelEnabled | quote }}
  OTEL_EXPORTER_OTLP_ENDPOINT: {{ .Values.config.otelEndpoint | quote }}
```

### 5.5 serviceaccount.yaml

Create `helm/registry-api/templates/serviceaccount.yaml`:

```yaml
{{- if .Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ .Values.serviceAccount.name }}
  labels:
    {{- include "registry-api.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}
```

The `iam.gke.io/gcp-service-account` annotation (set in values-staging.yaml) is what connects this K8s ServiceAccount to the GCP service account, enabling Workload Identity for the Cloud SQL proxy.

---

## 6. Step 5 — Values files

### values.yaml (base defaults)

Create `helm/registry-api/values.yaml` — copy from [K8s.md section 6](K8s.md#valuesyaml-base-defaults).

This is the file from K8s.md starting with `replicaCount: 2`. It has all defaults with Vault and Cloud SQL proxy disabled.

### values-staging.yaml

Create `helm/registry-api/values-staging.yaml` — based on K8s.md but **with Vault disabled** for Phase 2:

```yaml
image:
  repository: europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api/registry-api
  # tag is set at deploy time: --set image.tag=<value>

ingress:
  enabled: true
  className: gce
  annotations:
    kubernetes.io/ingress.global-static-ip-name: staging-ingress-ip
  # host: staging-api.example.com    # uncomment when you have DNS

config:
  appEnv: staging

# Phase 2: Vault DISABLED — using K8s Secrets for DB creds
vault:
  enabled: false

cloudSqlProxy:
  enabled: true
  instanceConnectionName: YOUR_PROJECT_ID:europe-west2:staging

serviceAccount:
  annotations:
    iam.gke.io/gcp-service-account: registry-api-staging@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

Replace `YOUR_PROJECT_ID` in all three places. Get `instanceConnectionName` from:

```bash
cd infra/environments/staging && tofu output -raw db_instance_connection
```

### values-prod.yaml

Create `helm/registry-api/values-prod.yaml` — you won't use this yet, but create it so it's ready:

```yaml
image:
  repository: europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api/registry-api

ingress:
  enabled: true
  className: gce
  annotations:
    kubernetes.io/ingress.global-static-ip-name: production-ingress-ip
    # networking.gke.io/managed-certificates: registry-api-cert  # uncomment with TLS
  # host: api.example.com  # uncomment when you have DNS

config:
  appEnv: production

vault:
  enabled: false    # Phase 2: disabled. Enable in Phase 4.

cloudSqlProxy:
  enabled: true
  instanceConnectionName: YOUR_PROJECT_ID:europe-west2:production

serviceAccount:
  annotations:
    iam.gke.io/gcp-service-account: registry-api-production@YOUR_PROJECT_ID.iam.gserviceaccount.com
```

---

## 7. Step 6 — Create the K8s Secret

Since Vault is disabled in Phase 2, the app needs DB credentials from a K8s Secret.

```bash
# Make sure you're on the right cluster
kubectl config use-context gke-staging

# Get the credentials from OpenTofu
cd infra/environments/staging
DB_USER=$(tofu output -raw db_user)
DB_PASS=$(tofu output -raw db_password)
cd -

# Create the namespace (Helm would do this, but we need the secret first)
kubectl create namespace registry-api

# Create the secret
kubectl create secret generic registry-api-db-credentials \
  -n registry-api \
  --from-literal=username="$DB_USER" \
  --from-literal=password="$DB_PASS"

# Verify it exists
kubectl get secret registry-api-db-credentials -n registry-api
```

**This secret is temporary.** Phase 4 replaces it with Vault. The deployment template already has the conditional — when `vault.enabled` flips to `true`, it stops reading the K8s Secret and reads from Vault instead.

---

## 8. Step 7 — Lint and render locally

Before deploying, verify the templates render correctly.

### Lint

```bash
helm lint helm/registry-api -f helm/registry-api/values-staging.yaml
# ==> Linting helm/registry-api
# [INFO] Chart.yaml: icon is recommended
# 1 chart(s) linted, 0 chart(s) failed
```

Warnings about missing icons are fine. Errors are not.

### Render (dry run)

```bash
helm template registry-api helm/registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag=test123 \
  -n registry-api
```

This outputs the rendered YAML without deploying. Check that:

- [ ] The Deployment has the Cloud SQL proxy sidecar
- [ ] The Deployment has `env` blocks for `DB_USER` and `DB_PASSWORD` from the secret (not Vault annotations)
- [ ] The ConfigMap has the correct env vars
- [ ] The ServiceAccount has the `iam.gke.io/gcp-service-account` annotation
- [ ] The Ingress has `ingressClassName: gce` and the static IP annotation
- [ ] The image is `europe-west2-docker.pkg.dev/YOUR_PROJECT/registry-api/registry-api:test123`

If anything looks wrong, fix the templates before deploying.

---

## 9. Step 8 — Build and push

```bash
# Build the image with a meaningful tag
IMAGE=europe-west2-docker.pkg.dev/YOUR_PROJECT_ID/registry-api/registry-api
TAG=$(git rev-parse --short HEAD)

docker build -t $IMAGE:$TAG .
docker push $IMAGE:$TAG

echo "Image: $IMAGE:$TAG"
```

---

## 10. Step 9 — Deploy

```bash
kubectl config use-context gke-staging

helm upgrade --install registry-api helm/registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag=$TAG \
  -n registry-api --create-namespace \
  --wait --timeout 5m
```

What `--wait` does: Helm waits until all pods are Running and pass their readiness probe, or it times out after 5 minutes and marks the release as failed.

### Watch the rollout

In a separate terminal:

```bash
kubectl get pods -n registry-api -w
```

You should see pods progress through:

```
NAME                            READY   STATUS              RESTARTS   AGE
registry-api-xxxxxxxxx-xxxxx   0/2     ContainerCreating   0          5s
registry-api-xxxxxxxxx-xxxxx   1/2     Running             0          15s
registry-api-xxxxxxxxx-xxxxx   2/2     Running             0          30s
registry-api-xxxxxxxxx-yyyyy   0/2     ContainerCreating   0          5s
registry-api-xxxxxxxxx-yyyyy   2/2     Running             0          35s
```

`2/2` means both the `registry-api` container and the `cloud-sql-proxy` sidecar are running.

---

## 11. Step 10 — Verify

### Pods running

```bash
kubectl get pods -n registry-api
# Both pods should be 2/2 Running
```

### Logs look clean

```bash
kubectl logs -l app=registry-api -n registry-api -c registry-api --tail=20
```

Look for your app's startup message. No stack traces = good.

### Cloud SQL proxy connected

```bash
kubectl logs -l app=registry-api -n registry-api -c cloud-sql-proxy --tail=10
```

Look for: `Listening on 127.0.0.1:5432` and no authentication errors.

### Ingress has an IP

```bash
kubectl get ingress -n registry-api
```

The `ADDRESS` column should show your static IP (the one from `tofu output ingress_static_ip`). If it says `<none>`, wait 2-5 minutes — GCE load balancers take time to provision.

### Hit the endpoints

```bash
# Get the IP
STAGING_IP=$(kubectl get ingress registry-api -n registry-api \
  -o jsonpath='{.status.loadBalancer.ingress[0].ip}')

# Health check
curl http://$STAGING_IP/health
# Expected: {"status":"ok"} or similar

# Readiness
curl http://$STAGING_IP/ready

# Ping
curl http://$STAGING_IP/api/ping
```

If the Ingress IP isn't ready yet, port-forward instead:

```bash
kubectl port-forward svc/registry-api 8080:80 -n registry-api &
curl http://localhost:8080/health
curl http://localhost:8080/api/ping
# Kill the port-forward when done
kill %1
```

### Helm release status

```bash
helm status registry-api -n registry-api
# Should show STATUS: deployed

helm history registry-api -n registry-api
# Shows revision history (useful for rollbacks later)
```

---

## 12. Step 11 — bb tasks

Add context-switching tasks to `bb.edn` so you don't have to remember the full `kubectl config` commands.

Add these tasks to your existing `bb.edn`:

```clojure
  ctx
  {:doc "Show current kubectl context"
   :task (shell "kubectl config current-context")}

  ctx-local
  {:doc "Switch kubectl to Rancher Desktop (local)"
   :task (shell "kubectl config use-context rancher-desktop")}

  ctx-staging
  {:doc "Switch kubectl to GKE staging"
   :task (shell "kubectl config use-context gke-staging")}

  ctx-prod
  {:doc "Switch kubectl to GKE production"
   :task (shell "kubectl config use-context gke-production")}
```

Also add these lines to the `help` task (after the "Housekeeping" group):

```clojure
          (println)
          (println "  Context:")
          (println "    ctx            Show current kubectl context")
          (println "    ctx-local      Switch to Rancher Desktop (local)")
          (println "    ctx-staging    Switch to GKE staging")
          (println "    ctx-prod       Switch to GKE production")
```

Test:

```bash
bb ctx            # prints: gke-staging
bb ctx-local      # switches to rancher-desktop
bb ctx            # prints: rancher-desktop
bb ctx-staging    # switches back
```

---

## 13. Troubleshooting

### Pod stuck in `0/2 ContainerCreating`

```bash
kubectl describe pod <pod-name> -n registry-api
```

Common causes:
- **"no nodes available"** — Autopilot is scaling up. Wait 1-2 minutes.
- **"image pull error"** — wrong image path or tag. Check `helm template` output.

### Pod `CrashLoopBackOff`

```bash
kubectl logs <pod-name> -n registry-api -c registry-api --previous
```

Common causes:
- **DB connection refused** — Cloud SQL proxy isn't running or the instance connection name is wrong. Check the proxy logs: `kubectl logs <pod-name> -c cloud-sql-proxy -n registry-api`
- **Missing env var** — the K8s Secret doesn't exist or has the wrong key names. Check: `kubectl get secret registry-api-db-credentials -n registry-api -o yaml`
- **OOM killed** — check `kubectl describe pod` for `OOMKilled`. Increase memory limits in values.yaml.

### Cloud SQL proxy: "Permission denied" or "IAM authentication failed"

The Workload Identity binding isn't set up correctly. Verify:

```bash
# 1. ServiceAccount has the annotation
kubectl get sa registry-api -n registry-api -o yaml | grep gcp-service-account

# 2. GCP SA has the right role
gcloud projects get-iam-policy YOUR_PROJECT_ID \
  --flatten="bindings[].members" \
  --filter="bindings.members:registry-api-staging" \
  --format="table(bindings.role)"
# Should include roles/cloudsql.client
```

### Ingress shows no IP after 10 minutes

```bash
kubectl describe ingress registry-api -n registry-api
```

Look for events. Common issues:
- **"no healthy backends"** — readiness probe is failing. Check pod logs.
- **Static IP not found** — the annotation name doesn't match the OpenTofu-created IP. Verify: `gcloud compute addresses list --global`

### `helm upgrade` fails with "timed out waiting"

The pods didn't become ready within 5 minutes. Check pod status and logs:

```bash
kubectl get pods -n registry-api
kubectl describe pod <failing-pod> -n registry-api
kubectl logs <failing-pod> -c registry-api -n registry-api
kubectl logs <failing-pod> -c cloud-sql-proxy -n registry-api
```

### Rollback to previous version

```bash
helm rollback registry-api -n registry-api
```

---

## 14. Checklist — Phase 2 complete

Before moving to Phase 3, confirm all of these:

- [ ] `helm status registry-api -n registry-api` shows `deployed`
- [ ] `kubectl get pods -n registry-api` shows 2 pods at `2/2 Running`
- [ ] Cloud SQL proxy logs show `Listening on 127.0.0.1:5432` (no errors)
- [ ] `curl http://$STAGING_IP/health` returns a success response
- [ ] `curl http://$STAGING_IP/api/ping` returns a success response
- [ ] `helm template` renders clean YAML with no errors
- [ ] `bb ctx-staging` and `bb ctx-local` switch contexts correctly
- [ ] You can still run `bb k8s-deploy` on `rancher-desktop` for local dev (nothing broken)

### Files created in this phase

```
helm/registry-api/
├── Chart.yaml
├── values.yaml
├── values-staging.yaml
├── values-prod.yaml
└── templates/
    ├── _helpers.tpl
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── configmap.yaml
    └── serviceaccount.yaml
```

Plus: a K8s Secret (`registry-api-db-credentials`) in the `registry-api` namespace and new `ctx*` tasks in `bb.edn`.

**Next:** [Phase 3 — GitHub Actions CI/CD](K8s-phase3.md)
