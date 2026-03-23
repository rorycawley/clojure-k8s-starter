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
- Config set up so you can later add:
  - ConfigMaps for non-secret config
  - Vault-injected secrets for DB credentials
  - OpenTelemetry environment variables

## Run locally

```bash
clojure -M:run
```

Or via Babashka:

```bash
bb run
```

Then test:

```bash
curl http://localhost:8080/health
curl http://localhost:8080/ready
curl http://localhost:8080/api/ping
curl http://localhost:8080/openapi.json
```

## REPL

Start an nREPL server:

```bash
clojure -M:nrepl
```

## Babashka tasks

| Task | Description |
|------|-------------|
| `bb run` | Run the API locally |
| `bb uberjar` | Build the uberjar |
| `bb test-request` | Hit the ping endpoint |
| `bb image-build` | Build container image |
| `bb image-run` | Run container locally |

## Build and run the container

```bash
docker build -t registry-api:0.1.0 .
docker run --rm -p 8080:8080 registry-api:0.1.0
```

## Configuration

Configuration is loaded from `resources/config.edn` using Aero. Key environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | HTTP server port |
| `APP_ENV` | `dev` | Application environment |
| `DB_HOST` | `127.0.0.1` | Database host |
| `DB_PORT` | `5432` | Database port |
| `DB_NAME` | `registry` | Database name |
| `DB_USER` | `""` | Database user (for Vault injection) |
| `DB_PASSWORD` | `""` | Database password (for Vault injection) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | OpenTelemetry endpoint |
| `OTEL_ENABLED` | `false` | Enable OpenTelemetry |

## Project structure

```
src/registry_api/
  config.clj   — Aero config loading
  routes.clj   — Reitit routes, handlers, and Malli schemas
  server.clj   — Jetty start/stop
  main.clj     — Entry point
```

## Why this is the right first app for Kubernetes

Do not start with Postgres, Vault, Helm, or OpenTelemetry.

Start with:
1. one stateless API container
2. one Deployment
3. one Service
4. one ConfigMap
5. liveness + readiness

After that, layer on:
1. external Postgres connection
2. Vault secrets
3. OpenTelemetry
4. Helm templating
