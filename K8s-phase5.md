# Phase 5: Observability — Grafana LGTM Stack

Add logs, metrics, and traces so you can see what your app is doing in production — not just whether it's running.

**Prerequisite:** Phases 1-4 complete — app running on GKE with Vault-managed secrets.

**End state:** OpenTelemetry Collector receives telemetry from your app, forwards it to Loki (logs), Mimir (metrics), and Tempo (traces). Grafana dashboards visualize everything. All components run in an `observability` namespace.

```
  Phase 5 — End State

  ┌─ namespace: registry-api ─────────────────────────────────────────┐
  │                                                                    │
  │  registry-api pod                                                  │
  │  ┌────────────────────────────────────────────────────────────┐    │
  │  │  app container                                             │    │
  │  │  OTEL_ENABLED=true                                        │    │
  │  │  OTEL_EXPORTER_OTLP_ENDPOINT=                             │    │
  │  │    http://otel-collector.observability.svc:4317            │    │
  │  │                                                            │    │
  │  │  Sends: traces + metrics via OTLP (gRPC :4317)            │    │
  │  │  Sends: logs via stdout → collected by Alloy/Promtail     │    │
  │  └────────────────────────────────────────────────────────────┘    │
  └────────────────────┬───────────────────────────────────────────────┘
                       │ OTLP gRPC
                       ▼
  ┌─ namespace: observability ────────────────────────────────────────┐
  │                                                                    │
  │  ┌──────────────────────────┐                                     │
  │  │  OpenTelemetry Collector │  Receives traces + metrics          │
  │  │  (Deployment)            │  from app via OTLP                  │
  │  └─────┬──────────┬─────────┘                                     │
  │        │          │                                               │
  │        │ traces   │ metrics                                       │
  │        ▼          ▼                                               │
  │  ┌──────────┐  ┌───────────────┐  ┌──────────┐                   │
  │  │  Tempo   │  │ Mimir         │  │  Loki    │                   │
  │  │ (traces) │  │ (distributed) │  │  (logs)  │◄── Alloy          │
  │  │          │  │ gateway :80   │  │          │   (DaemonSet)     │
  │  └──────────┘  └───────────────┘  └──────────┘   scrapes stdout  │
  │        │          │                    │                          │
  │        └──────────┼────────────────────┘                          │
  │                   ▼                                               │
  │           ┌──────────────┐                                        │
  │           │   Grafana    │  Dashboards, alerting                  │
  │           │   :3000      │  Queries all three backends            │
  │           └──────────────┘                                        │
  └───────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents

1. [What you're building](#1-what-youre-building)
2. [Step 1 — Install the LGTM stack via Helm](#2-step-1--install-the-lgtm-stack-via-helm)
3. [Step 2 — Deploy the OpenTelemetry Collector](#3-step-2--deploy-the-opentelemetry-collector)
4. [Step 3 — Enable telemetry in your app](#4-step-3--enable-telemetry-in-your-app)
5. [Step 4 — Deploy and verify](#5-step-4--deploy-and-verify)
6. [Step 5 — Configure Grafana dashboards](#6-step-5--configure-grafana-dashboards)
7. [Step 6 — Add alerting rules](#7-step-6--add-alerting-rules)
8. [Step 7 — Security hardening](#8-step-7--security-hardening)
9. [How it works at runtime](#9-how-it-works-at-runtime)
10. [Day-2 operations](#10-day-2-operations)
11. [Troubleshooting](#11-troubleshooting)
12. [Tear down (if needed)](#12-tear-down)
13. [Checklist — Phase 5 complete](#13-checklist--phase-5-complete)

---

## 1. What you're building

#### The three pillars of observability

```
  ┌─────────────────────────────────────────────────────────────────┐
  │                  The Three Pillars                              │
  │                                                                 │
  │  LOGS                    METRICS                 TRACES         │
  │  "What happened"         "How much / how fast"   "The journey  │
  │                                                   of a request"│
  │  ┌───────────────┐      ┌───────────────┐      ┌────────────┐ │
  │  │ Loki          │      │ Mimir         │      │ Tempo      │ │
  │  │               │      │               │      │            │ │
  │  │ "ERROR failed │      │ http_requests │      │ Request X: │ │
  │  │  to connect   │      │ _total = 1523 │      │ ingress    │ │
  │  │  to database" │      │               │      │  → service │ │
  │  │               │      │ request_      │      │  → db query│ │
  │  │ Structured,   │      │ duration_ms   │      │  → response│ │
  │  │ searchable    │      │ p99 = 142ms   │      │ 142ms total│ │
  │  └───────────────┘      └───────────────┘      └────────────┘ │
  │                                                                 │
  │  Collected by:           Collected by:          Collected by:   │
  │  Alloy (DaemonSet)       OTel Collector         OTel Collector │
  │  scrapes pod stdout      receives OTLP          receives OTLP  │
  │                          from app               from app        │
  │                                                                 │
  │  Query with:             Query with:            Query with:     │
  │  LogQL in Grafana        PromQL in Grafana      TraceQL in     │
  │                                                  Grafana        │
  └─────────────────────────────────────────────────────────────────┘
```

#### How the LGTM components fit together

| Component | What it does | Analogy |
|-----------|-------------|---------|
| **Loki** | Stores and indexes logs | Like CloudWatch Logs or Elasticsearch, but much lighter |
| **Grafana** | Dashboards and alerting UI | The single pane of glass — queries all three backends |
| **Tempo** | Stores distributed traces | Like Jaeger or AWS X-Ray |
| **Mimir** | Stores metrics (Prometheus-compatible, deployed as microservices via `mimir-distributed` chart) | Like Prometheus long-term storage |
| **Alloy** | Log collection agent (DaemonSet) | Runs on every node, scrapes container stdout/stderr |
| **OTel Collector** | Receives traces + metrics from your app via OTLP | A pipeline: receive → process → export |

#### What's already wired up from Phase 2

Your app already has OTEL config placeholders in the ConfigMap:

```yaml
OTEL_ENABLED: "false"                              # ← flip to "true" in this phase
OTEL_EXPORTER_OTLP_ENDPOINT: "http://localhost:4317"  # ← update to OTel Collector address
```

And `config.edn` reads them:

```clojure
:observability {:service-name "registry-api"
                :otlp-endpoint #or [#env OTEL_EXPORTER_OTLP_ENDPOINT "http://localhost:4317"]
                :enabled #boolean #or [#env OTEL_ENABLED false]}
```

Phase 5 installs the backends, deploys the collector, and flips the switch.

---

## 2. Step 1 — Install the LGTM stack via Helm

### What you're about to create

```
  observability namespace — all 6 components

  ┌─ namespace: observability ──────────────────────────────────────────┐
  │                                                                      │
  │  StatefulSets (data with PVCs):          Deployments (stateless):    │
  │  ┌──────────┐ ┌──────────┐              ┌──────────┐ ┌───────────┐ │
  │  │ loki-0   │ │ tempo-0  │              │ grafana  │ │ otel-     │ │
  │  │          │ │          │              │          │ │ collector │ │
  │  │ PVC: 5Gi │ │ PVC: 5Gi │              │ PVC: 1Gi │ │ (no PVC)  │ │
  │  │ :3100    │ │ :3200    │              │ :3000    │ │ :4317     │ │
  │  └──────────┘ └──────────┘              └──────────┘ └───────────┘ │
  │                                                                      │
  │  Mimir (mimir-distributed chart — 8 pods):                           │
  │  ┌────────────────────────────────────────────────────┐              │
  │  │ gateway → distributor → ingester → store-gateway   │              │
  │  │          query-frontend → querier    compactor     │              │
  │  │          minio (S3 storage)                        │              │
  │  │ gateway :80  ← single entry point for reads/writes │              │
  │  └────────────────────────────────────────────────────┘              │
  │                                                                      │
  │  DaemonSet (one per node):                                           │
  │  ┌───────────────────────────────────────────────────┐               │
  │  │ alloy   alloy   alloy   ...  (one pod per node)   │               │
  │  │ scrapes /var/log/pods on each node                │               │
  │  └───────────────────────────────────────────────────┘               │
  │                                                                      │
  │  Total pods: ~13 + (1 × number of nodes for Alloy)                  │
  └──────────────────────────────────────────────────────────────────────┘
```

### Create the namespace

```bash
kubectl create namespace observability
```

### Add the Helm repos

```bash
helm repo add grafana https://grafana.github.io/helm-charts
helm repo update
```

### Install Loki (log storage)

Loki is the log backend — it stores and indexes log lines that Alloy pushes to it. Think of it as a lightweight Elasticsearch that uses labels (like `{app="registry-api"}`) instead of full-text indexing.

```
  How logs get into Loki

  Pod stdout ──► container runtime writes to ──► /var/log/pods/...
                                                        │
                                                        ▼
                                                  Alloy (DaemonSet)
                                                  reads log files
                                                  adds labels:
                                                    namespace, pod, container, app
                                                        │
                                                        ▼
                                                  Loki (:3100)
                                                  /loki/api/v1/push
                                                  stores in PVC (5Gi)
                                                  indexes by labels
                                                        │
                                                        ▼
                                                  Grafana queries via LogQL
                                                  {app="registry-api"} |= "ERROR"
```

Create `loki-values.yaml`:

```yaml
# IMPORTANT: Default deploymentMode is SimpleScalable (3 StatefulSets + object storage).
# SingleBinary runs everything in one pod with local filesystem — right for staging.
deploymentMode: SingleBinary

loki:
  auth_enabled: false          # single-tenant for staging
  commonConfig:
    replication_factor: 1      # single replica for staging
  schemaConfig:
    configs:
      - from: "2024-04-01"
        store: tsdb
        object_store: filesystem
        schema: v13
        index:
          prefix: index_
          period: 24h

singleBinary:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 500m
      memory: 1Gi
  persistence:
    size: 5Gi

gateway:
  enabled: false               # we access Loki directly within the cluster

# lokiCanary is a top-level key (not under monitoring)
lokiCanary:
  enabled: false

monitoring:
  selfMonitoring:
    enabled: false
```

```bash
helm install loki grafana/loki \
  --namespace observability \
  -f loki-values.yaml
```

### Install Tempo (trace storage)

Tempo stores distributed traces — the journey of a single request through your system. Each trace is a tree of "spans" (e.g., HTTP handler → database query → cache lookup). The OTel Collector forwards traces to Tempo via OTLP.

```
  What a trace looks like in Tempo

  Trace ID: abc123def456
  ┌─────────────────────────────────────────────────────────┐
  │ POST /api/accounts/open                          142ms  │
  │ ├── middleware/auth                                12ms  │
  │ ├── open-account command handler                  125ms  │
  │ │   ├── load aggregate from event store            45ms  │
  │ │   ├── apply business rules                        2ms  │
  │ │   └── append events to store                     78ms  │ ← slow!
  │ └── return HTTP 201                                 5ms  │
  └─────────────────────────────────────────────────────────┘

  Each bar is a "span" with: name, duration, attributes, status
  Grafana renders this as a waterfall — click any span to inspect
```

Create `tempo-values.yaml`:

```yaml
tempo:
  storage:
    trace:
      backend: local
      local:
        path: /var/tempo/traces

  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 500m
      memory: 1Gi

  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: "0.0.0.0:4317"
        http:
          endpoint: "0.0.0.0:4318"

persistence:
  enabled: true
  size: 5Gi
```

```bash
helm install tempo grafana/tempo \
  --namespace observability \
  -f tempo-values.yaml
```

### Install Mimir (metrics storage)

Mimir is Prometheus-compatible long-term metric storage. Your app emits metrics (counters, histograms) via OTLP, the OTel Collector converts them to Prometheus remote-write format, and Mimir stores the time series.

```
  Metrics vs Logs vs Traces — when to use which

  "How many requests per second?"         → Metric  (Mimir, PromQL)
  "What did the error message say?"        → Log     (Loki, LogQL)
  "Why was this specific request slow?"    → Trace   (Tempo, TraceQL)

  Metrics are aggregated numbers over time:
  ┌──────────────────────────────────────────────┐
  │  http_request_duration_seconds               │
  │                                              │
  │  150ms ┤          ╭─╮                        │
  │  100ms ┤    ╭─────╯ ╰──╮                     │
  │   50ms ┤───╯            ╰──────              │
  │        └──────────────────────── time →       │
  │                                              │
  │  Cheap to store, fast to query, good for     │
  │  dashboards, alerts, and capacity planning   │
  └──────────────────────────────────────────────┘
```

Create `mimir-values.yaml`:

> **Note:** The `grafana/mimir-distributed` chart always deploys as microservices (distributor, ingester, querier, compactor, store-gateway, gateway). There is no "single-binary" mode in this chart. The config below minimizes replicas and uses the chart's built-in MinIO for object storage — suitable for staging.

```yaml
# The mimir-distributed chart deploys multiple components.
# Minimize replicas for staging — production would scale these up.
ingester:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi

distributor:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi

querier:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi

query_frontend:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi

compactor:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi

store_gateway:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi

# Gateway (nginx proxy) — this is the single entry point for reads/writes
gateway:
  replicas: 1
  resources:
    requests:
      cpu: 250m
      memory: 512Mi

# Built-in MinIO for S3-compatible object storage (staging only)
minio:
  enabled: true
  mode: standalone
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
```

> **GKE Autopilot cost note:** Mimir deploys ~8 pods (7 components + MinIO). At 250m/512Mi each, this is the most expensive component of the observability stack. For a lighter alternative, consider [Grafana Cloud Free Tier](https://grafana.com/pricing/) for metrics, which avoids self-hosting Mimir entirely.

```bash
helm install mimir grafana/mimir-distributed \
  --namespace observability \
  -f mimir-values.yaml
```

The gateway service is the entry point for all Mimir traffic. After install, verify the gateway service name:

```bash
kubectl -n observability get svc | grep gateway
# Expected: mimir-gateway   ClusterIP   ...   80/TCP
```

### Install Grafana (dashboards)

Grafana is the "single pane of glass" — it doesn't store telemetry itself, it queries the three backends. Each datasource is pre-configured in the Helm values so Grafana knows where to find each type of data.

```
  Grafana datasource wiring

  ┌─────────────────────────────────────────────────────┐
  │  Grafana (:3000)                                     │
  │                                                       │
  │  ┌─────────────┐ ┌─────────────┐ ┌───────────────┐  │
  │  │ Explore     │ │ Dashboards  │ │ Alerting      │  │
  │  │ (ad-hoc     │ │ (saved      │ │ (threshold    │  │
  │  │  queries)   │ │  panels)    │ │  rules)       │  │
  │  └──────┬──────┘ └──────┬──────┘ └───────┬───────┘  │
  │         └───────────────┼────────────────┘           │
  │                         │ queries via                 │
  │              ┌──────────┼──────────┐                  │
  │              ▼          ▼          ▼                  │
  │         ┌────────┐ ┌────────┐ ┌────────┐             │
  │         │ Loki   │ │ Tempo  │ │ Mimir  │             │
  │         │ :3100  │ │ :3200  │ │gateway │             │
  │         │ LogQL  │ │TraceQL │ │ :80    │             │
  │         │        │ │        │ │ PromQL │             │
  │         └────────┘ └────────┘ └────────┘             │
  └─────────────────────────────────────────────────────┘

  All connections are within the observability namespace
  using K8s Service DNS: <service>.observability.svc
  Mimir uses a gateway (nginx) — query via /prometheus path
```

Create `grafana-values.yaml`:

```yaml
replicas: 1

resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: 500m
    memory: 512Mi

persistence:
  enabled: true
  size: 1Gi

datasources:
  datasources.yaml:
    apiVersion: 1
    datasources:
      - name: Loki
        type: loki
        uid: loki                          # ← explicit UID, referenced by Tempo below
        url: http://loki.observability.svc:3100
        access: proxy
        isDefault: false
      - name: Tempo
        type: tempo
        uid: tempo
        url: http://tempo.observability.svc:3200
        access: proxy
        isDefault: false
        jsonData:
          tracesToLogsV2:
            datasourceUid: loki            # ← must match Loki's uid above
            filterByTraceID: true
          serviceMap:
            datasourceUid: mimir           # ← must match Mimir's uid below
      - name: Mimir
        type: prometheus
        uid: mimir                         # ← explicit UID, referenced by Tempo above
        url: http://mimir-gateway.observability.svc:80/prometheus   # gateway proxies to Mimir query-frontend
        access: proxy
        isDefault: true

adminPassword: ""    # auto-generated; retrieve with kubectl (see below)
```

> **Note:** Datasource URLs use K8s DNS — `<service>.<namespace>.svc`. This is the same Service DNS pattern from Phase 2: Grafana finds Loki/Tempo/Mimir by their Service names.

> **Trace-to-log correlation:** The `tracesToLogsV2` config lets you click a trace in Tempo and jump to the matching logs in Loki. For this to work, your app's log output must include a `trace_id` field. If your logs don't include trace IDs yet, the correlation link will appear but return no results — you can add trace ID logging later without changing any infrastructure.

```bash
helm install grafana grafana/grafana \
  --namespace observability \
  -f grafana-values.yaml
```

### Retrieve the Grafana admin password

```bash
# macOS:
kubectl -n observability get secret grafana \
  -o jsonpath="{.data.admin-password}" | base64 -D; echo

# Linux:
kubectl -n observability get secret grafana \
  -o jsonpath="{.data.admin-password}" | base64 -d; echo
```

### Install Alloy (log collector)

Alloy is a DaemonSet — it runs a pod on every node to scrape container logs from stdout/stderr and forward them to Loki.

```
  Why a DaemonSet? Alloy needs to read files on each node

  Node 1                              Node 2
  ┌──────────────────────────┐       ┌──────────────────────────┐
  │  ┌──────────┐ ┌────────┐ │       │  ┌──────────┐            │
  │  │registry- │ │vault-0 │ │       │  │registry- │            │
  │  │api pod   │ │        │ │       │  │api pod   │            │
  │  └────┬─────┘ └───┬────┘ │       │  └────┬─────┘            │
  │       │ stdout     │ stdout       │       │ stdout            │
  │       ▼            ▼      │       │       ▼                  │
  │  /var/log/pods/...        │       │  /var/log/pods/...       │
  │       │                   │       │       │                  │
  │       ▼                   │       │       ▼                  │
  │  ┌──────────┐             │       │  ┌──────────┐            │
  │  │ Alloy    │ reads local │       │  │ Alloy    │ reads local│
  │  │ pod      │ log files   │       │  │ pod      │ log files  │
  │  └────┬─────┘             │       │  └────┬─────┘            │
  └───────┼───────────────────┘       └───────┼──────────────────┘
          │                                   │
          └──────────┬────────────────────────┘
                     ▼
               Loki (:3100)
               /loki/api/v1/push

  A Deployment can't do this — it might land on one node
  and miss logs from pods on other nodes.
  DaemonSet guarantees: one Alloy pod per node, always.
```

Create `alloy-values.yaml`:

```yaml
alloy:
  configMap:
    content: |
      // Discover all pods and scrape their logs
      discovery.kubernetes "pods" {
        role = "pod"
      }

      discovery.relabel "pods" {
        targets = discovery.kubernetes.pods.targets

        rule {
          source_labels = ["__meta_kubernetes_namespace"]
          target_label  = "namespace"
        }
        rule {
          source_labels = ["__meta_kubernetes_pod_name"]
          target_label  = "pod"
        }
        rule {
          source_labels = ["__meta_kubernetes_pod_container_name"]
          target_label  = "container"
        }
        rule {
          source_labels = ["__meta_kubernetes_pod_label_app"]
          target_label  = "app"
        }
      }

      loki.source.kubernetes "pods" {
        targets    = discovery.relabel.pods.output
        forward_to = [loki.write.default.receiver]
      }

      loki.write "default" {
        endpoint {
          url = "http://loki.observability.svc:3100/loki/api/v1/push"
        }
      }

  # Resources are under alloy (not controller)
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 500m
      memory: 512Mi

# controller.type defaults to 'daemonset' — set explicitly for clarity
controller:
  type: daemonset
```

```bash
helm install alloy grafana/alloy \
  --namespace observability \
  -f alloy-values.yaml
```

### Verify all pods are running

```bash
kubectl -n observability get pods
```

Expected:

```
NAME                                    READY   STATUS    RESTARTS   AGE
loki-0                                  1/1     Running   0          2m
tempo-0                                 1/1     Running   0          2m
mimir-ingester-0                        1/1     Running   0          2m
mimir-distributor-xxxxxxxxxx-xxxxx      1/1     Running   0          2m
mimir-querier-xxxxxxxxxx-xxxxx          1/1     Running   0          2m
mimir-query-frontend-xxxxxxxxxx-xxxxx   1/1     Running   0          2m
mimir-compactor-0                       1/1     Running   0          2m
mimir-store-gateway-0                   1/1     Running   0          2m
mimir-gateway-xxxxxxxxxx-xxxxx          1/1     Running   0          2m
mimir-minio-xxxxxxxxxx-xxxxx            1/1     Running   0          2m
grafana-xxxxxxxxxx-xxxxx                1/1     Running   0          2m
alloy-xxxxx                             1/1     Running   0          2m    (one per node)
```

> **Note:** Mimir creates many pods because the `mimir-distributed` chart deploys each component separately (distributor, ingester, querier, etc.) plus MinIO for storage. This is normal — each component has a specific role in the write and read paths.

| Status | Meaning |
|--------|---------|
| `Pending` | Autopilot provisioning nodes — wait 2-3 minutes |
| `CrashLoopBackOff` | Check logs: `kubectl -n observability logs <pod-name>` |
| All `Running` | Proceed to Step 2 |

> **GKE Autopilot note:** Resource requests are set to 250m CPU / 512Mi memory — the Autopilot minimum for non-bursting clusters (General-Purpose compute class). If your cluster has bursting enabled, the minimum drops to 50m/52MiB, so you could lower requests. DaemonSet pods (Alloy) have even lower minimums (1m/2MiB on bursting clusters). We use 250m/512Mi to be safe across both modes. See Phase 2 for background.

> **Cost impact:** Phase 5 adds ~13 pods (Loki, Tempo, 8 Mimir components, Grafana, OTel Collector, Alloy) at 250m/512Mi each. On Autopilot, expect ~$60-80/month additional for staging. Mimir is the biggest cost driver — see the note above about Grafana Cloud as a lighter alternative.

---

## 3. Step 2 — Deploy the OpenTelemetry Collector

The OTel Collector sits between your app and the storage backends. It receives OTLP data (traces + metrics) from your app and exports it to Tempo and Mimir.

```
  Why a Collector instead of sending directly to Tempo/Mimir?

  Direct (fragile):                     Via Collector (recommended):
  ┌─────┐──► Tempo                      ┌─────┐──► ┌───────────┐──► Tempo
  │ App │──► Mimir                      │ App │    │ Collector │──► Mimir
  └─────┘                               └─────┘    └───────────┘
  App needs to know all backends         App sends to one endpoint
  Add a backend = change app config      Add a backend = change Collector config
  No buffering, retries, or sampling     Buffering, retries, tail sampling
```

### Add the OpenTelemetry Helm repo

```bash
helm repo add open-telemetry https://open-telemetry.github.io/opentelemetry-helm-charts
helm repo update
```

### Create `otel-collector-values.yaml`

```yaml
mode: deployment
replicaCount: 1

resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: 500m
    memory: 512Mi

config:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: "0.0.0.0:4317"
        http:
          endpoint: "0.0.0.0:4318"

  processors:
    batch:
      timeout: 5s
      send_batch_size: 1024
    memory_limiter:
      check_interval: 1s
      limit_mib: 400
      spike_limit_mib: 100
    resource:
      attributes:
        - key: environment
          value: staging
          action: upsert

  exporters:
    otlphttp/tempo:
      endpoint: "http://tempo.observability.svc:4318"
    prometheusremotewrite:
      endpoint: "http://mimir-gateway.observability.svc:80/api/v1/push"

  service:
    pipelines:
      traces:
        receivers: [otlp]
        processors: [memory_limiter, resource, batch]
        exporters: [otlphttp/tempo]
      metrics:
        receivers: [otlp]
        processors: [memory_limiter, resource, batch]
        exporters: [prometheusremotewrite]
```

```
  Collector pipeline — what happens to your telemetry data

  App sends OTLP ──► Receiver ──► Processors ──► Exporters
                      (otlp)      │               │
                                  ├─ memory_limiter (prevent OOM)
                                  ├─ resource (add environment=staging)
                                  └─ batch (buffer, send in chunks)
                                                  │
                                         ┌────────┴────────┐
                                         │                 │
                                    traces → Tempo    metrics → Mimir
```

### Install

```bash
helm install otel-collector open-telemetry/opentelemetry-collector \
  --namespace observability \
  -f otel-collector-values.yaml
```

### Verify the Collector is running

```bash
kubectl -n observability get pods -l app.kubernetes.io/name=opentelemetry-collector
# Should show 1/1 Running
```

---

## 4. Step 3 — Enable telemetry in your app

Now flip the switch in your Helm values to start sending telemetry.

### Update `helm/registry-api/values-staging.yaml`

Change the OTEL config from:

```yaml
config:
  otelEnabled: "false"
  otelEndpoint: "http://localhost:4317"
```

to:

```yaml
config:
  otelEnabled: "true"
  otelEndpoint: "http://otel-collector.observability.svc:4317"
```

That's it. The ConfigMap template already renders these into `OTEL_ENABLED` and `OTEL_EXPORTER_OTLP_ENDPOINT` env vars, and `config.edn` already reads them.

> **Important — `service.name` resource attribute:** Your app must set the OpenTelemetry `service.name` resource attribute to `registry-api`. This is how Grafana queries filter by service. The app's `config.edn` already defines `:service-name "registry-api"` in the `:observability` section — make sure your OpenTelemetry initialization code uses this value when creating the resource. Without it, the PromQL queries in Step 5 (which filter on `service_name="registry-api"`) will return no results.

> **OTel Collector Service name:** The `helm install otel-collector` command creates a K8s Service named `otel-collector-opentelemetry-collector`. The short DNS name used above (`otel-collector.observability.svc:4317`) may not resolve. Verify the actual Service name after install:
> ```bash
> kubectl -n observability get svc | grep otel
> ```
> If the Service name differs, update `values-staging.yaml` to match, e.g.:
> ```yaml
> otelEndpoint: "http://otel-collector-opentelemetry-collector.observability.svc:4317"
> ```

```
  What changes in the pod

  Before (Phase 2-4):
  ┌───────────────────────────────────────┐
  │  registry-api container              │
  │  OTEL_ENABLED = false                │
  │  (no telemetry sent anywhere)        │
  └───────────────────────────────────────┘

  After (Phase 5):
  ┌───────────────────────────────────────┐
  │  registry-api container              │
  │  OTEL_ENABLED = true                 │
  │  OTEL_EXPORTER_OTLP_ENDPOINT =      │
  │    otel-collector.observability.svc  │
  │                                      │
  │  Sends traces + metrics via gRPC     │──► OTel Collector ──► Tempo + Mimir
  │  Logs go to stdout                   │──► Alloy ──► Loki
  └───────────────────────────────────────┘
```

> **Note:** Logs are **not** sent via OTLP. Your app writes logs to stdout (as it always has), and Alloy (the DaemonSet from Step 1) scrapes them from the container runtime and pushes them to Loki. This is the standard K8s logging pattern — no code change needed for logs.

---

## 5. Step 4 — Deploy and verify

### Deploy the config change

```bash
# Via CI/CD (recommended)
git add helm/registry-api/values-staging.yaml
git commit -m "Enable OpenTelemetry — send traces and metrics to LGTM stack"
git push origin main

# Or manually
helm upgrade --install registry-api helm/registry-api \
  -n registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag="$(kubectl get deployment registry-api -n registry-api \
    -o jsonpath='{.spec.template.spec.containers[0].image}' | cut -d: -f2)"
```

### Verify telemetry is flowing

#### 1. Check the app is sending data

```bash
# Generate some traffic
kubectl -n registry-api port-forward svc/registry-api 8080:80 &
curl http://localhost:8080/api/ping
curl http://localhost:8080/health
```

#### 2. Check the Collector is receiving data

```bash
kubectl -n observability logs -l app.kubernetes.io/name=opentelemetry-collector --tail=20
# Look for: "TracesExported" and "MetricsExported" (not "dropped")
```

#### 3. Access Grafana

```bash
kubectl -n observability port-forward svc/grafana 3000:80 &
```

Open http://localhost:3000 — log in with `admin` and the password from Step 1.

#### 4. Verify each data source

**Logs (Loki):** Go to Explore → select Loki → run:

```
{app="registry-api"}
```

You should see your app's log lines.

**Traces (Tempo):** Go to Explore → select Tempo → search for traces with service name `registry-api`.

**Metrics (Mimir):** Go to Explore → select Mimir → run:

```
up{job="registry-api"}
```

| What to check | Expected result | If missing |
|--------------|-----------------|------------|
| Logs in Loki | App log lines appear | Check Alloy pods are running on the right nodes |
| Traces in Tempo | Traces with spans appear | Check `OTEL_ENABLED=true` in the pod env vars |
| Metrics in Mimir | Metric data points | Check OTel Collector logs for export errors |

```
  The power of correlation — connecting the three pillars

  Dashboard shows: P99 latency spiked at 14:32
  ┌──────────────────────────────────────────────────┐
  │  Mimir (PromQL)                                   │
  │  P99 = 2.3 seconds  ← that's bad                 │
  │                                                    │
  │  Click time range → "Show traces for this period" │
  └────────────────────────┬─────────────────────────┘
                           ▼
  ┌──────────────────────────────────────────────────┐
  │  Tempo (TraceQL)                                  │
  │  Trace abc123: POST /api/accounts/deposit 2.3s   │
  │  └── event-store append: 2.1s  ← found the span  │
  │                                                    │
  │  Click span → "Show logs for this trace"          │
  └────────────────────────┬─────────────────────────┘
                           ▼
  ┌──────────────────────────────────────────────────┐
  │  Loki (LogQL)                                     │
  │  {app="registry-api"} | trace_id="abc123"         │
  │                                                    │
  │  14:32:01 WARN connection pool exhausted           │
  │  14:32:01 INFO retrying database connection        │
  │  14:32:03 INFO connection acquired after 2.1s      │
  │                                                    │
  │  Root cause: database connection pool exhaustion   │
  └──────────────────────────────────────────────────┘
```

---

## 6. Step 5 — Configure Grafana dashboards

### Import the registry-api overview dashboard

Create `grafana/dashboards/registry-api-overview.json` — or configure it in the Grafana UI.

A useful starter dashboard has these panels:

```
  ┌───────────────────────────────────────────────────────────┐
  │  registry-api — Overview Dashboard                        │
  │                                                           │
  │  ┌─────────────────┐  ┌─────────────────┐  ┌───────────┐│
  │  │ Request Rate     │  │ Error Rate (5xx)│  │ P99       ││
  │  │ (requests/sec)   │  │ (errors/sec)    │  │ Latency   ││
  │  │ source: Mimir    │  │ source: Mimir   │  │ (ms)      ││
  │  └─────────────────┘  └─────────────────┘  │ src: Mimir││
  │                                             └───────────┘│
  │  ┌──────────────────────────────────────────────────────┐│
  │  │ Request Duration Histogram (heatmap)                 ││
  │  │ source: Mimir                                        ││
  │  └──────────────────────────────────────────────────────┘│
  │                                                           │
  │  ┌──────────────────────────────────────────────────────┐│
  │  │ Recent Traces (table — click to drill into Tempo)    ││
  │  │ source: Tempo                                        ││
  │  └──────────────────────────────────────────────────────┘│
  │                                                           │
  │  ┌──────────────────────────────────────────────────────┐│
  │  │ Recent Error Logs (table — filtered to ERROR/WARN)   ││
  │  │ source: Loki  query: {app="registry-api"} |= "ERROR"││
  │  └──────────────────────────────────────────────────────┘│
  └───────────────────────────────────────────────────────────┘
```

### Key PromQL queries for your panels

| Panel | PromQL query |
|-------|-------------|
| Request rate | `rate(http_server_request_duration_seconds_count{service_name="registry-api"}[5m])` |
| Error rate | `rate(http_server_request_duration_seconds_count{service_name="registry-api", http_response_status_code=~"5.."}[5m])` |
| P99 latency | `histogram_quantile(0.99, rate(http_server_request_duration_seconds_bucket{service_name="registry-api"}[5m]))` |
| Error ratio | `rate(http_server_request_duration_seconds_count{http_response_status_code=~"5.."}[5m]) / rate(http_server_request_duration_seconds_count[5m])` |

### Key LogQL queries

| What | LogQL query |
|------|------------|
| All app logs | `{app="registry-api"}` |
| Errors only | `{app="registry-api"} \|= "ERROR"` |
| Slow queries | `{app="registry-api"} \|= "duration" \| json \| duration > 1000` |
| By pod | `{app="registry-api", pod="registry-api-7f8b9c4d5-abc12"}` |

---

## 7. Step 6 — Add alerting rules

```
  How alerting works end-to-end

  Mimir (metrics)
       │
       ▼
  Grafana alert rule                   fires when
  ┌──────────────────────────┐        condition is true
  │ PromQL query             │        for X minutes
  │ e.g. error_ratio > 0.05  │───────────────────────┐
  │ for: 5m                  │                        │
  └──────────────────────────┘                        ▼
                                              ┌──────────────┐
                                              │Contact point │
                                              │ Slack / email│
                                              │ / PagerDuty  │
                                              └──────────────┘

  Grafana evaluates the rule every 60s (configurable).
  If the condition stays true for the "for" duration,
  it fires and sends to the contact point.
```

### Configure alert notification channel

In Grafana UI: Alerting → Contact points → Add → choose your channel (Slack, email, PagerDuty, etc.).

### Essential alerts

Create these as Grafana alert rules (Alerting → Alert rules → New):

| Alert | Condition | Severity |
|-------|-----------|----------|
| High error rate | Error ratio > 5% for 5 minutes | Critical |
| High latency | P99 > 2 seconds for 5 minutes | Warning |
| Pod restarts | `kube_pod_container_status_restarts_total` increases | Warning |
| Vault sealed | Vault health check fails | Critical |
| OTel Collector down | `up{job="otel-collector"} == 0` for 2 minutes | Critical |

### Example: high error rate alert (PromQL)

```
rate(http_server_request_duration_seconds_count{
  service_name="registry-api",
  http_response_status_code=~"5.."
}[5m])
/
rate(http_server_request_duration_seconds_count{
  service_name="registry-api"
}[5m])
> 0.05
```

---

## 8. Step 7 — Security hardening

### NetworkPolicy for the observability namespace

Only allow traffic from the app namespace and within the observability namespace itself.

```
  NetworkPolicy — what's allowed and what's blocked

  ┌─ namespace: registry-api ──────┐
  │                                 │
  │  registry-api pod               │
  │  ├── OTLP gRPC :4317  ────────────► OTel Collector   ✅ allowed
  │  ├── random port       ────────────► Loki             ✗ blocked
  │  └── random port       ────────────► Grafana          ✗ blocked
  │                                 │
  └─────────────────────────────────┘

  ┌─ namespace: observability ─────────────────────────────────────┐
  │                                                                 │
  │  OTel Collector ───► Tempo (:4318)                  ✅ internal │
  │  OTel Collector ───► Mimir (:9009)                  ✅ internal │
  │  Alloy          ───► Loki  (:3100)                  ✅ internal │
  │  Grafana        ───► Loki / Tempo / Mimir           ✅ internal │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘

  ┌─ external / other namespaces ──┐
  │                                 │
  │  anything ─────────────────────────► observability    ✗ blocked
  │  (only registry-api namespace       (except allowed
  │   can reach OTel Collector)          internal traffic)
  └─────────────────────────────────┘
```

Add to `k8s/network-policies.yaml`:

```yaml
---
# Only allow ingress to OTel Collector from registry-api namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: otel-collector-ingress
  namespace: observability
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: opentelemetry-collector
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: registry-api
      ports:
        - protocol: TCP
          port: 4317    # OTLP gRPC
        - protocol: TCP
          port: 4318    # OTLP HTTP
---
# Allow observability components to talk to each other
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: observability-internal
  namespace: observability
spec:
  podSelector: {}
  policyTypes:
    - Ingress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: observability
```

Apply:

```bash
kubectl label namespace observability kubernetes.io/metadata.name=observability --overwrite
kubectl apply -f k8s/network-policies.yaml
```

### Update the registry-api egress policy

The existing egress policy from Phase 4 needs to allow traffic to the OTel Collector:

Add this rule to the `registry-api-egress` NetworkPolicy in `k8s/network-policies.yaml`:

```yaml
    # Allow traffic to OTel Collector
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: observability
      ports:
        - protocol: TCP
          port: 4317
```

### Harden pod security contexts

Follow the same pattern as Phase 4's Vault hardening — run observability pods as non-root with read-only filesystems where possible.

Add to each values file under the relevant key:

```yaml
# For Grafana (grafana-values.yaml)
securityContext:
  runAsNonRoot: true
  runAsUser: 472
  runAsGroup: 472
  fsGroup: 472
containerSecurityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop:
      - ALL
```

```yaml
# For OTel Collector (otel-collector-values.yaml)
securityContext:
  runAsNonRoot: true
  runAsUser: 65534
  runAsGroup: 65534
containerSecurityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop:
      - ALL
```

> **Note:** Alloy needs access to host-level container log files (`/var/log/pods`), so it cannot use `readOnlyRootFilesystem`. It does run as non-root by default in the Grafana Helm chart.

### Grafana access control

For staging, access Grafana via `kubectl port-forward`. For production, consider:
- Adding Grafana to the Ingress with authentication
- Using Grafana Cloud (managed) instead of self-hosted
- Storing the Grafana admin password in Vault instead of a K8s Secret
- Configuring Grafana RBAC to separate viewer/editor/admin roles

---

## 9. How it works at runtime

```
  Request lifecycle with observability

  1. Request arrives at Ingress → Service → registry-api pod
        │
  2. App processes the request
     │  ├── Writes log lines to stdout (structured JSON)
     │  ├── Records a trace span (start time, duration, metadata)
     │  └── Increments metrics counters (request count, latency histogram)
        │
  3. Telemetry flows to backends (async — doesn't slow down the request)
     │
     ├── Logs path:
     │   stdout ──► Alloy (DaemonSet on the node)
     │              scrapes container log files
     │              ──► Loki (stores + indexes)
     │
     ├── Traces path:
     │   OTLP gRPC ──► OTel Collector ──► Tempo (stores traces)
     │                  (batches, adds environment label)
     │
     └── Metrics path:
         OTLP gRPC ──► OTel Collector ──► Mimir (stores time series)
                        (batches, adds environment label)
        │
  4. You query in Grafana:
     ├── "Show me errors in the last hour" → Loki (LogQL)
     ├── "What's the P99 latency?"         → Mimir (PromQL)
     └── "Trace this slow request"          → Tempo (TraceQL)
         └── Click trace → see each span → spot the slow DB query
```

---

## 10. Day-2 operations

### Access Grafana

```bash
kubectl -n observability port-forward svc/grafana 3000:80 &
# Open http://localhost:3000
# Username: admin
# Password: kubectl -n observability get secret grafana -o jsonpath="{.data.admin-password}" | base64 -D  (macOS; use -d on Linux)
```

### Check storage usage

```bash
# PVC usage for each component
kubectl -n observability exec loki-0 -- df -h /var/loki
kubectl -n observability exec tempo-0 -- df -h /var/tempo
# Mimir uses MinIO for storage — check MinIO PVC
kubectl -n observability get pvc -l app=minio
```

### Increase retention

By default, data is retained based on each component's defaults. To adjust:

| Component | Config key | Default | Staging recommendation |
|-----------|-----------|---------|----------------------|
| Loki | `loki.limits_config.retention_period` | 744h (31 days) | 168h (7 days) |
| Tempo | `tempo.compactor.compaction.block_retention` | 336h (14 days) | 168h (7 days) |
| Mimir | `mimir.structuredConfig.limits.compactor_blocks_retention_period` | 0 (forever) | 720h (30 days) |

### Add a new app to the pipeline

If you add another service later (e.g., the notification module), it just needs:
1. Set `OTEL_EXPORTER_OTLP_ENDPOINT` to the OTel Collector service (verify name with `kubectl -n observability get svc | grep otel`)
2. Set `OTEL_ENABLED=true`
3. Alloy automatically discovers its logs (no config change needed)

```
  Adding a second service — zero infrastructure changes

  ┌─ registry-api ns ─┐  ┌─ notification ns ──┐
  │                     │  │                     │
  │  registry-api pod   │  │  notification pod   │   ← new service
  │  OTEL_ENABLED=true  │  │  OTEL_ENABLED=true  │
  │                     │  │                     │
  └────────┬────────────┘  └────────┬────────────┘
           │ OTLP                   │ OTLP
           └───────────┬────────────┘
                       ▼
                 OTel Collector          ← same collector, no config change
                       │
              ┌────────┴────────┐
              ▼                 ▼
           Tempo              Mimir      ← same storage, traces/metrics
                                           auto-separated by service.name

  Grafana filter: service_name="notification-service"
  Alloy: automatically discovers new pods → Loki
```

---

## 11. Troubleshooting

```
  Quick diagnosis — which telemetry is missing?

  Data missing in Grafana?
  │
  ├── Logs missing? ──► Check Alloy
  │   │                  Is Alloy running on same node as app?
  │   │                  kubectl -n observability get pods -l app.kubernetes.io/name=alloy -o wide
  │   │
  │   └── Alloy running but no logs? ──► Check Loki
  │       Is Loki accepting pushes?
  │       kubectl -n observability logs loki-0 --tail=20
  │
  ├── Traces missing? ──► Check app config
  │   │                    Is OTEL_ENABLED=true in pod env?
  │   │                    kubectl -n registry-api exec <pod> -- env | grep OTEL
  │   │
  │   └── App sending but no traces? ──► Check OTel Collector → Tempo
  │       kubectl -n observability logs -l app.kubernetes.io/name=opentelemetry-collector
  │       Look for "TracesExported" vs "dropped"
  │
  ├── Metrics missing? ──► Same as traces, but check "MetricsExported"
  │                         and Mimir remote-write endpoint
  │
  └── Grafana can't connect? ──► Check Service DNS
      kubectl -n observability get svc
      Do service names match datasource URLs?
```

### No logs appearing in Loki

```bash
# Check Alloy is running on the node where your app pod is scheduled
kubectl -n observability get pods -l app.kubernetes.io/name=alloy -o wide
kubectl -n registry-api get pods -o wide
# Compare NODE columns — they should overlap

# Check Alloy logs for errors
kubectl -n observability logs -l app.kubernetes.io/name=alloy --tail=20
```

### No traces appearing in Tempo

```bash
# Check OTEL_ENABLED is actually true in the running pod
kubectl -n registry-api exec <pod-name> -c registry-api -- env | grep OTEL
# Should show: OTEL_ENABLED=true

# Check the OTel Collector is receiving data
kubectl -n observability logs -l app.kubernetes.io/name=opentelemetry-collector --tail=30
# Look for "TracesExported" lines. If you see "dropped", check the Tempo endpoint.

# Check Tempo is healthy
kubectl -n observability exec tempo-0 -- wget -qO- http://localhost:3200/ready
# Should print "ready"
```

### No metrics appearing in Mimir

```bash
# Check OTel Collector export logs
kubectl -n observability logs -l app.kubernetes.io/name=opentelemetry-collector --tail=30
# Look for "MetricsExported" or errors with "prometheusremotewrite"

# Check Mimir gateway is routing correctly
kubectl -n observability logs -l app.kubernetes.io/component=gateway --tail=20

# Check Mimir distributor is accepting writes
kubectl -n observability logs -l app.kubernetes.io/component=distributor --tail=20
```

### Grafana can't connect to a datasource

```bash
# Verify the Service DNS names resolve from within the cluster
kubectl -n observability exec grafana-<pod-id> -- nslookup loki.observability.svc
kubectl -n observability exec grafana-<pod-id> -- nslookup tempo.observability.svc
kubectl -n observability exec grafana-<pod-id> -- nslookup mimir-gateway.observability.svc
```

If DNS fails, the Service doesn't exist or the pod name is wrong. Check `kubectl -n observability get svc`.

### OTel Collector OOMKilled

The Collector is buffering too much data. Increase memory limits or reduce batch size:

```yaml
# In otel-collector-values.yaml
resources:
  limits:
    memory: 1Gi    # increase from 512Mi

config:
  processors:
    memory_limiter:
      limit_mib: 800    # increase from 400
```

---

## 12. Tear down (if needed)

To remove all observability infrastructure:

```
  Tear-down order — disable the producer before removing the consumer

  Step 1: Disable telemetry in app
  ┌───────────────┐
  │ registry-api  │  otelEnabled: "false"
  │ (stop sending)│  redeploy
  └───────┬───────┘
          │ no more OTLP data
          ▼
  Step 2: Uninstall consumers, then storage
  ┌───────────────┐
  │ otel-collector│──► uninstall first (receives from app)
  │ alloy         │──► uninstall (reads node logs)
  └───────┬───────┘
          ▼
  ┌───────────────┐
  │ grafana       │──► uninstall (queries backends)
  └───────┬───────┘
          ▼
  ┌───────────────┐
  │ loki, tempo,  │──► uninstall (storage backends)
  │ mimir         │
  └───────┬───────┘
          ▼
  Step 3: Clean up PVCs + namespace
  ┌───────────────┐
  │ PVCs, namespace│──► delete (data is permanently lost)
  └───────────────┘
```

```bash
# 1. Disable telemetry in your app first (so pods don't error on missing collector)
#    In values-staging.yaml, set otelEnabled: "false" and redeploy

# 2. Uninstall all Helm releases (order doesn't matter)
helm uninstall otel-collector --namespace observability
helm uninstall alloy --namespace observability
helm uninstall grafana --namespace observability
helm uninstall mimir --namespace observability
helm uninstall tempo --namespace observability
helm uninstall loki --namespace observability

# 3. Delete PVCs (persistent data — dashboards, logs, traces, metrics are lost)
kubectl delete pvc --all -n observability

# 4. Delete the namespace
kubectl delete namespace observability

# 5. Remove the observability NetworkPolicies
kubectl delete networkpolicy otel-collector-ingress -n observability 2>/dev/null
kubectl delete networkpolicy observability-internal -n observability 2>/dev/null
```

> **Note:** Remove the OTel Collector egress rule from the `registry-api-egress` NetworkPolicy in `k8s/network-policies.yaml` if you're removing observability permanently.

---

## 13. Checklist — Phase 5 complete

```
Infrastructure:
  [ ] observability namespace created
  [ ] Loki pod running (log storage)
  [ ] Tempo pod running (trace storage)
  [ ] Mimir pods running — gateway, distributor, ingester, querier,
      query-frontend, compactor, store-gateway, minio (metrics storage)
  [ ] Grafana pod running (dashboards)
  [ ] Alloy DaemonSet running on all nodes (log collection)
  [ ] OTel Collector pod running (trace + metric pipeline)

App configuration:
  [ ] values-staging.yaml has otelEnabled: "true"
  [ ] values-staging.yaml has otelEndpoint pointing to OTel Collector
  [ ] App pods redeployed with new config

Data flowing:
  [ ] Logs visible in Grafana → Explore → Loki
  [ ] Traces visible in Grafana → Explore → Tempo
  [ ] Metrics visible in Grafana → Explore → Mimir

Dashboards:
  [ ] registry-api overview dashboard created
  [ ] Request rate, error rate, P99 latency panels working

Alerting:
  [ ] At least one alert rule configured (e.g., high error rate)
  [ ] Notification channel configured (Slack, email, etc.)

Security:
  [ ] NetworkPolicy restricts OTel Collector ingress to registry-api namespace
  [ ] NetworkPolicy allows observability-internal traffic
  [ ] registry-api egress policy updated to allow OTel Collector
  [ ] Grafana and OTel Collector run as non-root with read-only filesystem
  [ ] Grafana admin password stored securely
```

**You now have full observability.** Logs, metrics, and traces flow from your app to the LGTM stack, queryable in Grafana. When something goes wrong in production, you can see what happened (logs), measure the impact (metrics), and trace the root cause (traces).

**Next:** [Phase 6 — RabbitMQ async messaging](K8s-phase6.md)

---

## Files created in this phase

```
Helm value files (not part of your chart — used for helm install):
  loki-values.yaml
  tempo-values.yaml
  mimir-values.yaml
  grafana-values.yaml
  alloy-values.yaml
  otel-collector-values.yaml

Modified:
  helm/registry-api/values-staging.yaml    # otelEnabled: true, otelEndpoint updated
  k8s/network-policies.yaml               # added observability NetworkPolicies

No new Helm templates — the ConfigMap from Phase 2 already renders OTEL_ENABLED
and OTEL_EXPORTER_OTLP_ENDPOINT. Flipping the values is all that's needed.
```
