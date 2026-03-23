# registry-api

A tiny Clojure API starter for learning Kubernetes step by step.

## What this app gives you

- Ring + Jetty + Reitit + Malli
- `deps.edn` with nREPL support
- Aero config loading (`resources/config.edn`)
- Babashka task runner
- `/health` — liveness probe
- `/ready` — readiness probe (checks database config is present)
- `/api/ping` — simple test endpoint
- `/openapi.json` — auto-generated OpenAPI spec

## Prerequisites

- [Rancher Desktop](https://rancherdesktop.io/) (provides K8s, Docker, kubectl, nerdctl)
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- [Babashka](https://github.com/babashka/babashka#installation)

Make sure Docker Desktop is **not** running — it conflicts with Rancher Desktop on port 6443.

## Quick start

### 1. Run locally (no K8s)

```bash
bb run
curl http://localhost:8080/api/ping
```

### 2. Deploy to local K8s

Build the image and apply all manifests in one step:

```bash
bb k8s-deploy
```

This builds the Docker image (`registry-api:0.1.0`) and runs `kubectl apply -f k8s/`.

### 3. Verify the deployment

```bash
bb k8s-status
```

You should see 2 pods in `Running` state and the `registry-api` service.

### 4. Access the app

Forward the K8s service port to localhost:

```bash
bb k8s-port-forward
```

Then in another terminal:

```bash
curl http://localhost:8080/api/ping
curl http://localhost:8080/health
curl http://localhost:8080/ready
```

### 5. View logs

```bash
bb k8s-logs
```

### 6. Make changes and redeploy

After editing code, rebuild the image and restart the pods:

```bash
bb k8s-restart
```

### 7. Tear down

```bash
bb k8s-teardown
```

## Babashka tasks

Run `bb help` for the full list. Key tasks:

| Task | Description |
|------|-------------|
| **Development** | |
| `bb run` | Run the API locally |
| `bb repl` | Start nREPL with dev tools |
| `bb test-request` | Hit the ping endpoint |
| **Quality** | |
| `bb check` | Run lint + format check + smoke compile |
| `bb fmt` | Auto-format all Clojure files |
| **Build** | |
| `bb uberjar` | Build the uberjar |
| `bb image-build` | Build container image |
| `bb image-run` | Run container locally |
| `bb startup-time` | Measure startup for K8s probe config |
| **Kubernetes** | |
| `bb k8s-deploy` | Build image and deploy to local K8s |
| `bb k8s-status` | Show pod and service status |
| `bb k8s-logs` | Tail logs from running pods |
| `bb k8s-port-forward` | Forward localhost:8080 to service |
| `bb k8s-restart` | Rebuild image and restart deployment |
| `bb k8s-teardown` | Remove all K8s resources |

## K8s manifests

All manifests live in `k8s/`:

| File | What it does |
|------|-------------|
| `configmap.yaml` | Environment variables (app env, DB config, OTEL) |
| `deployment.yaml` | 2 replicas with startup, liveness, and readiness probes |
| `service.yaml` | ClusterIP service mapping port 80 to container port 8080 |

## Configuration

Configuration is loaded from `resources/config.edn` using Aero. Environment variables are injected via the ConfigMap in K8s:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP server port |
| `APP_ENV` | `dev` | Application environment |
| `DB_HOST` | `127.0.0.1` | Database host |
| `DB_PORT` | `5432` | Database port |
| `DB_NAME` | `registry` | Database name |
| `DB_USER` | `""` | Database user |
| `DB_PASSWORD` | `""` | Database password |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry endpoint |
| `OTEL_ENABLED` | `false` | Enable OpenTelemetry |

## Project structure

```
src/registry_api/
  config.clj   — Aero config loading
  routes.clj   — Reitit routes, handlers, and Malli schemas
  server.clj   — Jetty start/stop
  main.clj     — Entry point

k8s/
  configmap.yaml   — Environment config
  deployment.yaml  — Pod spec with probes and resources
  service.yaml     — ClusterIP service

Dockerfile         — Two-stage build (deps cache + slim JRE runtime)
bb.edn             — Babashka task runner config
```

## Troubleshooting

**Rancher Desktop won't start (ECONNREFUSED on port 6443)**
- Quit Docker Desktop: `osascript -e 'quit app "Docker Desktop"'`
- Kill anything on 6443: `lsof -i :6443` then `kill -9 <PID>`
- Factory reset Rancher Desktop: Troubleshooting > Factory Reset

**Pods stuck in ErrImagePull**
- The image needs to be built locally: `bb image-build`
- `imagePullPolicy` is set to `IfNotPresent` so K8s will use the local image

**Pods crash-looping**
- Check logs: `bb k8s-logs`
- Check events: `kubectl describe pod -l app=registry-api`

## Learning path

Start with what's here:
1. One stateless API container
2. One Deployment
3. One Service
4. One ConfigMap
5. Startup + liveness + readiness probes

Then layer on:
1. External Postgres connection
2. Vault secrets
3. OpenTelemetry
4. Helm templating
