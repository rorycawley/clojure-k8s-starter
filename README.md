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

You should see 2 pods in `Running` state, the `registry-api` service, and endpoint slices showing the pod IPs that the service routes traffic to.

### 4. Access the app

Your app is running inside K8s, but it's not directly accessible from your machine. Port-forwarding creates a tunnel from your localhost into the cluster:

```bash
bb k8s-port-forward
```

This runs in the foreground — open another terminal to test:

```bash
curl http://localhost:8080/api/ping
curl http://localhost:8080/health
curl http://localhost:8080/ready
```

### 5. View logs

See what your app is printing to stdout inside the cluster. This tails logs from all pods matching the `app=registry-api` label:

```bash
bb k8s-logs
```

Press Ctrl+C to stop following.

### 6. Make changes and redeploy

After editing code, this rebuilds the Docker image and tells K8s to do a rolling restart — it spins up new pods with the new image before killing the old ones, so there's no downtime:

```bash
bb k8s-restart
```

### 7. Tear down

Remove the K8s resources (deployment, service, configmap) but keep the Docker image:

```bash
bb k8s-teardown
```

Or nuke everything — K8s resources, Docker image, and local build artifacts — to start completely fresh:

```bash
bb nuke
```

## Babashka tasks

Run `bb help` for the full list.

### Development

| Task | What it does |
|------|-------------|
| `bb run` | Starts the API on localhost:8080 using `clojure -M:run`. No Docker or K8s involved. |
| `bb repl` | Starts an nREPL server so you can connect from your editor for interactive development. |
| `bb test-request` | Sends `curl -i http://localhost:8080/api/ping` — quick way to check the app is responding. |

### Quality

| Task | What it does |
|------|-------------|
| `bb check` | Runs lint, format check, and smoke compile in sequence. Good pre-commit sanity check. |
| `bb lint` | Runs clj-kondo to catch common Clojure mistakes. |
| `bb fmt` | Auto-formats all Clojure files in place with cljfmt. |
| `bb fmt-check` | Checks formatting without changing files. Useful in CI. |
| `bb smoke` | Requires all namespaces to verify they compile. Catches missing imports and syntax errors. |

### Build

| Task | What it does |
|------|-------------|
| `bb uberjar` | Compiles the app into a single JAR file at `target/registry-api.jar`. |
| `bb image-build` | Builds the Docker image `registry-api:0.1.0`. Uses a two-stage Dockerfile — first stage compiles the uberjar, second stage copies it into a slim JRE image. |
| `bb image-run` | Runs the Docker image locally on port 8080. Useful to test the container before deploying to K8s. |
| `bb startup-time` | Starts the app and measures how long until `/health` responds. Helps you set `initialDelaySeconds` on K8s probes so K8s doesn't kill your app before it's ready. |

### Kubernetes

These tasks wrap `kubectl` commands so you don't have to remember the exact flags and label selectors.

| Task | What it does |
|------|-------------|
| `bb k8s-deploy` | **First-time setup.** Builds the Docker image, then applies all manifests in `k8s/` (configmap, deployment, service). After this, your app is running in the cluster. |
| `bb k8s-status` | **"Is everything OK?"** Shows pods (are they Running?), the service (is it created?), and endpoint slices (is the service actually routing to the pods?). If endpoints are empty, the service selector doesn't match any pods. |
| `bb k8s-logs` | **"What is my app doing?"** Tails stdout from all registry-api pods. This is where you'll see startup messages, request logs, and errors. |
| `bb k8s-port-forward` | **"Let me hit it from my browser/curl."** Creates a tunnel from localhost:8080 to the K8s service. Without this, the app is only reachable inside the cluster. Runs in the foreground — Ctrl+C to stop. |
| `bb k8s-restart` | **"I changed code, redeploy it."** Rebuilds the Docker image with your latest code and does a rolling restart — new pods come up before old ones are killed, so there's zero downtime. |
| `bb k8s-describe` | **"Something's wrong, give me details."** Shows the full K8s description of the deployment and service — probe config, resource limits, events, conditions. This is where you look when pods aren't starting or probes are failing. |
| `bb k8s-teardown` | **"Remove from K8s but keep the image."** Deletes the deployment, service, and configmap. The Docker image stays so `bb k8s-deploy` is fast next time. |
| `bb nuke` | **"Burn it all down."** Tears down K8s resources, force-removes the Docker image, and cleans build artifacts. Use this when you want a completely fresh start. |

## How K8s routes traffic to your app

Understanding this flow is key to debugging:

```
Deployment (creates pods with label app=registry-api)
    │
    ▼
Pods (each gets a unique IP, e.g. 10.42.0.18, 10.42.0.19)
    │
    ▼
EndpointSlice (K8s watches for pods matching the Service selector
               and records their IPs here)
    │
    ▼
Service (receives traffic on ClusterIP:80, routes to pod IPs on port 8080)
    │
    ▼
port-forward (tunnels localhost:8080 → Service:80 → Pod:8080)
```

A Service does **not** point to a Deployment — it points to **Pods** by label selector. The Deployment just happens to create pods with the matching label. This distinction matters later when you use other workload types (Jobs, StatefulSets, DaemonSets).

`bb k8s-status` shows all three levels so you can verify the chain is connected.

## K8s manifests

All manifests live in `k8s/`:

| File | What it does |
|------|-------------|
| `configmap.yaml` | Key-value pairs injected as environment variables into the pods. This is where non-secret config lives (app env, DB host, OTEL settings). |
| `deployment.yaml` | Tells K8s to run 2 replicas of the app. Includes three types of health probes: **startup** (gives the JVM up to 60s to boot), **readiness** (only send traffic when the app is ready), and **liveness** (restart the pod if it becomes unresponsive). Also sets CPU/memory resource requests and limits. |
| `service.yaml` | Creates a stable network endpoint for the pods. Maps port 80 to container port 8080. Other services in the cluster can reach the app at `registry-api:80`. |

## Configuration

Configuration is loaded from `resources/config.edn` using Aero. In K8s, environment variables are injected via the ConfigMap:

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
- `imagePullPolicy` is set to `IfNotPresent` so K8s won't try to pull from a remote registry

**Pods crash-looping**
- Check logs: `bb k8s-logs`
- Check events: `bb k8s-describe` — look at the Events section at the bottom
- Common cause: the startup probe times out because the JVM is slow to start. Increase `failureThreshold` in `deployment.yaml`.

**Port-forward fails with "address already in use"**
- Something else is using port 8080. Find it: `lsof -i :8080`
- Kill it: `kill -9 <PID>`, then retry `bb k8s-port-forward`

**Endpoints are empty in `bb k8s-status`**
- The service selector (`app=registry-api`) doesn't match any running pods.
- Check that pods exist and are Ready: `kubectl get pods -l app=registry-api`
- If pods exist but aren't Ready, the readiness probe is failing — check `bb k8s-describe` for details.

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
