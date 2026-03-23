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

## What problem does Kubernetes solve?

Imagine you've built this `registry-api`. It works on your laptop. Now you need to run it in production where real users depend on it. You have some worries:

- What if the server crashes at 3am?
- What if you get 10x more traffic than expected?
- What if you need to deploy a new version without downtime?

You *could* SSH into a server, run `java -jar registry-api.jar`, and babysit it. But that doesn't scale, and you'd never sleep.

Kubernetes is an automation system. You write YAML files describing **what you want** ("run 2 copies of my app, restart them if they crash, spread traffic between them"), and K8s makes it happen. You declare the desired state — K8s figures out how to get there and keep it there.

This repo has three YAML files, each answering one question:

## K8s manifests

All manifests live in `k8s/`:

| File | What it does |
|------|-------------|
| `configmap.yaml` | **"What config does my app need?"** See [ConfigMap deep dive](#configmap-deep-dive) below. |
| `deployment.yaml` | **"How should my app run?"** See [Deployment deep dive](#deployment-deep-dive) below. |
| `service.yaml` | **"How do other things talk to my app?"** See [Service deep dive](#service-deep-dive) below. |

### ConfigMap deep dive

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: registry-api-config
data:
  APP_ENV: "dev"
  PORT: "8080"
  DB_HOST: "postgres.internal.example"
  DB_PORT: "5432"
  DB_NAME: "registry"
  DB_SSLMODE: "require"
  OTEL_ENABLED: "false"
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://otel-collector:4317"
```

Every K8s YAML file follows the same structure at the top. Think of it like a form you're filling in:

- `apiVersion` — which version of the K8s API understands this form
- `kind` — what *type* of thing you're creating
- `metadata` — a name tag so you can refer to it later
- Then the actual content (here it's `data`, other kinds use `spec`)

The `data` section is just key-value pairs. All strings — K8s ConfigMaps can only store strings.

This file on its own does nothing. It just sits in K8s as a named bundle of values. It only becomes useful when something *else* references it. The connection happens in `deployment.yaml`:

```yaml
envFrom:
  - configMapRef:
      name: registry-api-config
```

Notice how `name: registry-api-config` matches `metadata: name: registry-api-config` in the ConfigMap — that's the link. K8s takes every key-value pair from the ConfigMap and injects them as environment variables into the container.

The full chain looks like this:

```
configmap.yaml                      deployment.yaml
─────────────                       ───────────────
data:                               envFrom:
  DB_HOST: "postgres.example"  ──►    - configMapRef:
  DB_PORT: "5432"                         name: registry-api-config
  APP_ENV: "dev"                              │
                                              ▼
                                    Container starts with:
                                      DB_HOST=postgres.example
                                      DB_PORT=5432
                                      APP_ENV=dev
                                              │
                                              ▼
                                    Aero reads config.edn:
                                      :host #or [#env DB_HOST "127.0.0.1"]
                                              │
                                              ▼
                                    Clojure code:
                                      (get-in config [:database :host])
                                      → "postgres.example"
```

The key insight: you build your Docker image **once**, and the ConfigMap controls which database, which port, which environment it runs against. Dev, staging, and prod can all use the same JAR — just different ConfigMaps.

### Deployment deep dive

`deployment.yaml` is the biggest of the three files. Let's take it chunk by chunk.

**Chunk 1: The header**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: registry-api
```

Same form structure as the ConfigMap. The `kind: Deployment` is the important part — it tells K8s "I want you to **run and manage** containers for me."

**Chunk 2: Replicas and selector**

```yaml
spec:
  replicas: 2
  selector:
    matchLabels:
      app: registry-api
```

`replicas: 2` is the most important line in this file. It says: "I want 2 copies of my app running at all times."

These copies are called **pods**. A pod is the smallest thing K8s manages — basically a wrapper around one or more containers. In this repo, one pod = one container running `java -jar registry-api.jar`.

If one pod crashes, K8s notices and starts a replacement. You always have 2 running. That's the core promise.

The `selector` with `matchLabels` is how K8s answers the question "which pods belong to this Deployment?" It's a label-matching system. The other half of this lives in the pod template below.

**Chunk 3: The pod template**

```yaml
  template:
    metadata:
      labels:
        app: registry-api
    spec:
      containers:
        - name: registry-api
          image: registry-api:0.1.0
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: registry-api-config
```

See `labels: app: registry-api` in the template? And `matchLabels: app: registry-api` in the selector above? Those match — that's how the Deployment knows "these are my pods."

Think of it like this: the Deployment stamps every pod it creates with a sticker that says `app: registry-api`. Then when it needs to check on its pods, it looks for that sticker.

The line `image: registry-api:0.1.0` is what connects this Deployment to the Docker image that the Dockerfile builds. That's why `bb image-build` tags the image as `registry-api:0.1.0` — the name and tag must match exactly. And `imagePullPolicy: IfNotPresent` means "use the local image if it exists, don't try to download it from a remote registry." That's why `bb k8s-deploy` builds the image locally first — K8s finds it already on the machine.

**Chunk 4: The probes**

This is where K8s monitors your app's health.

```yaml
          startupProbe:
            httpGet:
              path: /health
              port: 8080
            failureThreshold: 30
            periodSeconds: 2
          readinessProbe:
            httpGet:
              path: /ready
              port: 8080
            initialDelaySeconds: 3
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
```

Three probes. They fire in a specific order, and each one has a different job. Think of it like hiring a new employee (the pod):

**Startup probe** — "Has the new person finished their first-day orientation?" K8s checks `/health` every 2 seconds. Until this passes, the other two probes don't even start. It allows up to 30 failures — that's 30 × 2 = 60 seconds for the JVM to boot.

**Readiness probe** — "Are they ready to take on work?" Once startup passes, K8s checks `/ready` every 5 seconds. If it fails, K8s stops sending traffic to that pod but keeps it alive. It might recover.

**Liveness probe** — "Are they still conscious?" K8s checks `/health` every 10 seconds. If this fails repeatedly, K8s kills and restarts the pod. This is the drastic measure.

Why does the startup probe exist separately? Without it, the liveness probe starts checking `/health` immediately. The JVM is still booting — `/health` doesn't respond — so K8s sees failures. After 3 consecutive failures it thinks "this pod is dead" and kills it. The pod restarts, starts booting again, gets killed again... forever. It's called a **crash loop**.

The startup probe prevents this by saying "I go first. Don't run any other probes until I've passed." Here's the timeline:

```
t=0s    Pod starts, JVM booting...
        Startup probe: GET /health → fail (1/30)
t=2s    Startup probe: GET /health → fail (2/30)
t=4s    Startup probe: GET /health → fail (3/30)
t=6s    JVM ready, /health responds
        Startup probe: GET /health → 200 OK ✓  (startup complete!)
        ─── readiness and liveness probes now activate ───
t=9s    Readiness probe: GET /ready → 200 OK ✓  (pod added to Service endpoints)
t=11s   Liveness probe:  GET /health → 200 OK ✓ (pod stays alive)
t=14s   Readiness probe: GET /ready → 200 OK ✓
t=21s   Liveness probe:  GET /health → 200 OK ✓
        ... continues indefinitely ...
```

**Chunk 5: Resources**

```yaml
          resources:
            requests:
              cpu: "100m"
              memory: "256Mi"
            limits:
              cpu: "500m"
              memory: "512Mi"
```

This tells K8s how much CPU and memory each pod gets. Two numbers per resource:

- `requests` — the **guaranteed minimum**. K8s uses this when deciding which machine (node) to place the pod on. "I need at least this much."
- `limits` — the **ceiling**. The pod can burst up to this amount, but no further.

`100m` means 100 "millicores" — 10% of one CPU core. `256Mi` is 256 mebibytes of RAM.

What happens if a pod tries to exceed its limits? Two different outcomes depending on the resource:

- **CPU** over limit → the pod gets **throttled** (runs slower, but stays alive)
- **Memory** over limit → the pod gets **killed** with an `OOMKilled` error (Out Of Memory)

That's the entire `deployment.yaml`.

### Service deep dive

`service.yaml` is the shortest file, but it solves an important problem.

You have `replicas: 2` in your Deployment — so two pods are running, each with its own internal IP address. These IPs are temporary — every time a pod restarts, it gets a new one. So you can't hardcode them.

If another service in your cluster wants to call your API, it has two problems:

1. Which pod do I talk to?
2. What IP address do I use if they keep changing?

A **Service** solves both. It gives your app one stable name (`registry-api`) and one stable address inside the cluster. It acts as a load balancer — traffic comes in, and the Service forwards it to a healthy pod.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: registry-api
spec:
  selector:
    app: registry-api
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
```

The `selector` is the key part:

```yaml
  selector:
    app: registry-api
```

Remember the labels in `deployment.yaml`?

```yaml
  template:
    metadata:
      labels:
        app: registry-api
```

Same label, same value. That's how the Service finds its pods. Three things use the `app: registry-api` label — all in just two files:

1. **Deployment `selector.matchLabels`** — "which pods do I manage?"
2. **Deployment `template.metadata.labels`** — stamps the label on each pod it creates
3. **Service `selector`** — "which pods do I route traffic to?"

The ConfigMap is connected differently — by name reference (`configMapRef: name: registry-api-config`), not by label matching.

The `ports` section maps external to internal:

```yaml
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
```

Two different port numbers. `port: 80` is what callers use. `targetPort: 8080` is what your container listens on. So another service in the cluster would call `http://registry-api:80/api/ping`, and the Service rewrites that to port `8080` on a healthy pod. And `bb k8s-port-forward` tunnels your laptop's localhost:8080 into this Service.

### Recap: how everything connects

```
configmap.yaml                 deployment.yaml                    service.yaml
──────────────                 ───────────────                    ────────────
name: registry-api-config      configMapRef: ───── by name ────►  (not involved)
                               name: registry-api-config

(not involved)                 selector.matchLabels: ─┐
                               app: registry-api      │
                                                      ├── by label ── selector:
                               template.labels:       │               app: registry-api
                               app: registry-api  ────┘
```

**`configmap.yaml`** — a named bundle of environment variables. Connected to the Deployment by `configMapRef: name`.

**`deployment.yaml`** — the brain. Says "run 2 pods of this image, inject config from ConfigMap, and monitor them with 3 probes (startup → readiness → liveness)." Also sets CPU/memory limits.

**`service.yaml`** — the front door. Gives the app a stable name and routes traffic on port 80 to port 8080 on healthy pods. Connected to pods by the `app: registry-api` label.

### How probes connect to your Clojure code

K8s is a real HTTP client — it sends an actual `GET /ready` request to your running Jetty server, just like `curl` would. It only cares about one thing: the HTTP status code.

Here's what happens when that request hits your code. The **readiness handler**:

```clojure
(defn ready-handler [config]
  (fn [_request]
    (let [db-host (get-in config [:database :host])
          db-name (get-in config [:database :name])
          database-config-present? (every? seq [db-host db-name])]
      (if database-config-present?
        (response/ok ...)            ;; returns status 200 → K8s sends traffic
        (response/service-unavailable ...)))))  ;; returns status 503 → K8s stops sending traffic
```

K8s sends the request. Reitit matches `/ready`. Middleware runs. This handler executes. The response goes back to K8s with a status code, and K8s makes its decision.

Now the **liveness handler**:

```clojure
(defn health-handler [config]
  (fn [_request]
    (response/ok
     {:status "ok"
      :service (get-in config [:app :name])
      :env (str (get-in config [:app :env]))})))
```

Notice something different? There's no `if`. It always returns `response/ok` (200). It never fails as long as the JVM is running and Jetty can handle requests.

That's deliberate. Think about what each probe is asking:

- **Liveness** (`/health`) — "Is the process alive?" If Jetty can respond at all, the answer is yes. If the JVM has frozen or crashed, there's no response at all — and *that's* the failure K8s detects.
- **Readiness** (`/ready`) — "Is the app ready for real work?" This has actual logic — it checks whether the database config exists.

There's a design principle here: **liveness checks should be simple and almost never fail on purpose**. If you put complex logic in your liveness handler (like checking the database connection) and the database goes down, K8s would kill and restart your pods — even though the *app* is perfectly fine. It would just make things worse.

The **startup probe** also hits `/health`, not `/ready`. Why? Because startup is answering the same question as liveness — "is the process alive yet?" — just during the boot window. It doesn't care whether the app is *ready for traffic*, only whether the JVM has started enough to respond to HTTP. Using `/ready` would be wrong here — the app might be alive but not ready (e.g. waiting for a database migration), and you wouldn't want K8s to think it failed to start.

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
