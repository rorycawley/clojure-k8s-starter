# Phase 7: Redis — Caching and Idempotency for Event Sourcing

Add Redis as the cache layer for read model projections, message deduplication, and saga correlation state.

**Prerequisite:** Phases 1-4 complete — app running on GKE with Vault-managed secrets. Phase 6 (RabbitMQ) is recommended for deduplication use case but not required.

**End state:** Redis running in its own namespace, credentials stored in Vault, app connected for caching and deduplication. Read model projections cached for fast queries, RabbitMQ consumer idempotency guaranteed via dedup keys.

```
  Phase 7 — End State

  ┌─ namespace: registry-api ─────────────────────────────────────────┐
  │                                                                    │
  │  registry-api pod                                                  │
  │  ┌────────────────────────────────────────────────────────────┐    │
  │  │  app container                                             │    │
  │  │  REDIS_HOST=redis-master.redis.svc                         │    │
  │  │  REDIS_PORT=6379  (TLS-encrypted)                          │    │
  │  │  REDIS_PASSWORD from Vault                                 │    │
  │  │                                                            │    │
  │  │  Uses Redis for:                                           │    │
  │  │  ├── dedup keys: {agg_id}:{seq_num} → processed? (TTL)   │    │
  │  │  ├── read model cache: account:123:balance → JSON          │    │
  │  │  ├── search cache: search:{hash} → [results]              │    │
  │  │  └── saga state: saga:{id}:{step} → {:status :result}    │    │
  │  └────────────────────────────────────────────────────────────┘    │
  └────────────────────┬───────────────────────────────────────────────┘
                       │ Redis :6379 (TLS)
                       ▼
  ┌─ namespace: redis ────────────────────────────────────────────────┐
  │                                                                    │
  │  ┌──────────────────────────────────────────────────────────┐     │
  │  │  Redis master (StatefulSet, bitnami/redis)               │     │
  │  │  Port :6379 (TLS) | Metrics :9121 (exporter sidecar)     │     │
  │  │  PVC: 8Gi (RDB snapshots + AOF persistence)              │     │
  │  │  Architecture: standalone (no replicas for staging)       │     │
  │  └──────────────────────────────────────────────────────────┘     │
  └────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents

1. [What you're building](#1-what-youre-building)
2. [Step 1 — Install Redis via Helm](#2-step-1--install-redis-via-helm)
3. [Step 2 — Store credentials in Vault](#3-step-2--store-credentials-in-vault)
4. [Step 3 — Configure your app to connect](#4-step-3--configure-your-app-to-connect)
5. [Step 4 — Deploy and verify](#5-step-4--deploy-and-verify)
6. [Step 5 — Implement caching and deduplication patterns](#6-step-5--implement-caching-and-deduplication-patterns)
7. [Step 6 — Security hardening](#7-step-6--security-hardening)
8. [Step 7 — Monitoring with Grafana](#8-step-7--monitoring-with-grafana)
9. [How it works at runtime](#9-how-it-works-at-runtime)
10. [Day-2 operations](#10-day-2-operations)
11. [Troubleshooting](#11-troubleshooting)
12. [Tear down (if needed)](#12-tear-down)
13. [Checklist — Phase 7 complete](#13-checklist--phase-7-complete)

---

## 1. What you're building

#### Why your event sourcing app needs Redis

Redis solves three problems that PostgreSQL alone handles poorly at scale:

```
  Problem 1: Duplicate message processing

  RabbitMQ guarantees at-least-once delivery (Phase 6).
  If a consumer crashes mid-processing, the message is redelivered.

  Without dedup:

  ┌──────────┐    deliver    ┌──────────────┐    write    ┌──────────────┐
  │ RabbitMQ │──────────────►│ Consumer     │────────────►│ PostgreSQL   │
  │          │               │ processes    │             │ balance: 200 │
  │ msg:     │    crash!     │ deposit $100 │             └──────────────┘
  │ deposit  │    redeliver  │              │
  │ $100     │──────────────►│ processes    │────────────►│ balance: 300 │
  └──────────┘               │ deposit $100 │  WRONG!     │ (should be   │
                             │ AGAIN        │  doubled!   │  200)        │
                             └──────────────┘             └──────────────┘

  With Redis dedup:

  ┌──────────┐    deliver    ┌──────────────┐   check     ┌───────────┐
  │ RabbitMQ │──────────────►│ Consumer     │────────────►│ Redis     │
  │          │               │              │  "processed  │ SET key   │
  │ msg:     │               │ key exists?  │   before?"   │ with TTL  │
  │ deposit  │               │ NO → process │             └───────────┘
  │ $100     │               │              │────────────►│ PostgreSQL │
  │          │    crash!     │ deposit $100 │  write      │ balance:200│
  │          │    redeliver  │              │             └────────────┘
  │          │──────────────►│ key exists?  │
  └──────────┘               │ YES → skip  │  ← no duplicate write!
                             │ ACK message  │
                             └──────────────┘
```

```
  Problem 2: Slow read model queries

  Every API query hits PostgreSQL, even for data that rarely changes:

  Without cache:

  GET /accounts/123/balance
    │
    ▼
  ┌──────────────┐   SELECT balance   ┌──────────────┐
  │ API handler  │───────────────────►│ PostgreSQL   │  ~2-10ms
  └──────────────┘   FROM projections  └──────────────┘
                     WHERE id = 123

  100 requests/sec = 100 DB queries/sec for the same data.

  With Redis cache:

  GET /accounts/123/balance
    │
    ▼
  ┌──────────────┐   GET account:123  ┌───────────┐
  │ API handler  │──────────────────►│ Redis     │  ~0.1ms (cache hit)
  │              │◄──────────────────│ returns   │
  │              │   cache hit!       │ cached    │
  └──────────────┘                    └───────────┘

  Cache miss? Fall through to PostgreSQL, then cache the result:

  ┌──────────────┐   GET account:123  ┌───────────┐
  │ API handler  │──────────────────►│ Redis     │  MISS
  │              │                    └───────────┘
  │              │   SELECT ...       ┌──────────────┐
  │              │──────────────────►│ PostgreSQL   │  ~2-10ms
  │              │◄──────────────────│              │
  │              │                    └──────────────┘
  │              │   SET account:123  ┌───────────┐
  │              │──────────────────►│ Redis     │  cache for next time
  └──────────────┘   EX 3600 (1 hr)  └───────────┘
```

```
  Problem 3: Where Redis fits in the event sourcing stack

  ┌─────────────────────────────────────────────────────────────────┐
  │                      Your Clojure App                            │
  │                                                                  │
  │  Write path (commands):                                          │
  │  ┌──────────┐   ┌─────────┐   ┌──────────┐   ┌──────────────┐ │
  │  │ Command  │──►│ Decider │──►│ Events   │──►│ Event Store  │ │
  │  │          │   │         │   │          │   │ (PostgreSQL) │ │
  │  └──────────┘   └─────────┘   └──────────┘   └──────┬───────┘ │
  │                                                       │         │
  │  Read path (queries):               outbox            │         │
  │  ┌──────────────┐                     │               │         │
  │  │ API handler  │◄── cache hit ──┐    ▼               │         │
  │  │              │                │  ┌──────────┐      │         │
  │  │              │◄── cache miss ─┤  │ RabbitMQ │      │         │
  │  │              │   (fallback    │  └────┬─────┘      │         │
  │  │              │    to PG)      │       │            │         │
  │  └──────────────┘                │       ▼            │         │
  │                              ┌───┴────────────┐       │         │
  │                              │  Redis         │       │         │
  │                              │  ┌───────────┐ │       │         │
  │                              │  │ dedup keys│◄┼───────┘         │
  │                              │  │ cache     │ │  consumer checks │
  │                              │  │ saga state│ │  before writing  │
  │                              │  └───────────┘ │                  │
  │                              └────────────────┘                  │
  └─────────────────────────────────────────────────────────────────┘

  PostgreSQL = source of truth (events, projections)
  RabbitMQ   = event distribution (outbox, saga, integration)
  Redis      = ephemeral cache + dedup (speed, not durability)
```

```
  Full architecture — all 7 phases working together

  ┌─────────────────────────────────────────────────────────────────────────┐
  │                          GKE Autopilot Cluster                          │
  │                                                                         │
  │  ┌─ Phase 2: registry-api ──────────────────────────────────────────┐  │
  │  │                                                                   │  │
  │  │  ┌────────────────────────────────────────────────────────────┐  │  │
  │  │  │  registry-api pods (Deployment, 2 replicas)                │  │  │
  │  │  │                                                             │  │  │
  │  │  │  Write path:  HTTP → Command → Decider → Event Store (PG)  │  │  │
  │  │  │  Read path:   HTTP → Redis cache → PostgreSQL fallback     │  │  │
  │  │  │  Consumer:    RabbitMQ → dedup check (Redis) → projection  │  │  │
  │  │  │                                                             │  │  │
  │  │  │  Sidecars:                                                  │  │  │
  │  │  │  ├── Cloud SQL Auth Proxy (Phase 1)                        │  │  │
  │  │  │  └── Vault Agent Injector (Phase 4)                        │  │  │
  │  │  └────────────────────────────────────────────────────────────┘  │  │
  │  └────────┬─────────────┬──────────────┬────────────────────────────┘  │
  │           │             │              │                                │
  │     :5432 │       :5672 │        :6379 │                                │
  │           ▼             ▼              ▼                                │
  │  ┌──────────────┐ ┌──────────┐ ┌───────────┐  ┌───────────────────┐   │
  │  │ Cloud SQL    │ │ RabbitMQ │ │ Redis     │  │ Observability     │   │
  │  │ (Phase 1)    │ │ (Phase 6)│ │ (Phase 7) │  │ (Phase 5)         │   │
  │  │              │ │          │ │           │  │                   │   │
  │  │ Event store  │ │ Exchanges│ │ Dedup keys│  │ Grafana + Loki    │   │
  │  │ Projections  │ │ Queues   │ │ Cache     │  │ Tempo + Mimir     │   │
  │  │ (truth)      │ │ Bindings │ │ Saga state│  │ OTel + Alloy      │   │
  │  └──────────────┘ └──────────┘ └───────────┘  └───────────────────┘   │
  │                                                                         │
  │  ┌────────────┐  ┌────────────────────────────────┐                    │
  │  │ Vault      │  │ CI/CD (Phase 3)                 │                    │
  │  │ (Phase 4)  │  │ GitHub Actions → Helm upgrade   │                    │
  │  │ All creds  │  └────────────────────────────────┘                    │
  │  └────────────┘                                                         │
  └─────────────────────────────────────────────────────────────────────────┘

  Each layer is independent and optional:
  ─ Remove Redis → app works, just slower (cache miss) and less safe (no dedup)
  ─ Remove RabbitMQ → app works synchronously (no async projections)
  ─ Remove observability → app works, just blind to performance issues
  ─ Remove Vault → app works with K8s Secrets (less secure)
```

> **Key principle:** Redis data is **always rebuildable.** If Redis loses all data (crash, flush, restart), the system recovers by re-reading from PostgreSQL (cache miss) and reprocessing messages from RabbitMQ (dedup keys regenerated). Redis is an optimisation layer, not a source of truth.

#### The four Redis use cases in this app

| Use case | Key pattern | TTL | Why Redis, not PostgreSQL |
|----------|------------|-----|--------------------------|
| **Deduplication** | `dedup:{agg_id}:{seq_num}` | 24h | Sub-millisecond check before every message; SET IF NOT EXISTS is atomic |
| **Read model cache** | `cache:account:{id}:balance` | 1h | 100x faster than SQL query; invalidated by projection consumer |
| **Search cache** | `cache:search:{query_hash}` | 15m | Expensive queries cached; short TTL for freshness |
| **Saga state** | `saga:{saga_id}:{step}` | 1h | Correlation state for multi-step workflows; fast read/write |

#### Why Redis, not Memcached?

```
  Redis vs Memcached for event sourcing

  ┌────────────────────────┬───────────────────────┬──────────────────────┐
  │ Feature                │ Redis                 │ Memcached            │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ Data types             │ STRING, HASH, LIST,   │ STRING only          │
  │                        │ SET, SORTED SET       │                      │
  │                        │ → saga HASH state ✓   │ → saga needs JSON ✗  │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ SET NX (if not exists) │ ✓ atomic, built-in    │ ✗ add() only, no    │
  │                        │ → dedup is one command│   TTL control        │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ Persistence            │ ✓ RDB + AOF           │ ✗ none (volatile)   │
  │                        │ → dedup keys survive  │ → all lost on crash  │
  │                        │   restart              │                      │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ Per-key TTL            │ ✓ any TTL per key     │ ✓ per key            │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ Lua scripting          │ ✓ atomic multi-step   │ ✗ no scripting       │
  │                        │   operations           │                      │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ Pub/Sub                │ ✓ built-in            │ ✗ not available      │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ Clojure client         │ Carmine (mature)      │ clojure-memcached    │
  │                        │                       │ (less maintained)    │
  ├────────────────────────┼───────────────────────┼──────────────────────┤
  │ Helm chart (Bitnami)   │ bitnami/redis ✓       │ bitnami/memcached ✓  │
  └────────────────────────┴───────────────────────┴──────────────────────┘

  Memcached wins for: pure key-value cache at massive scale (multi-threaded).
  Redis wins for: anything beyond simple caching (our use case).

  For event sourcing specifically, Redis is the clear choice:
  ─ HASH for saga state (can't do this in Memcached)
  ─ SET NX for dedup (atomic, with TTL in the same command)
  ─ Persistence means dedup keys survive pod restarts
  ─ Lua scripting for future atomic multi-step operations
```

#### Redis data types used

```
  Redis data types and how we use them

  ┌──────────────────────────────────────────────────────────────────┐
  │  STRING (most common)                                            │
  │                                                                   │
  │  SET dedup:acc-123:42 "1"  EX 86400                              │
  │  │   │          │     │    │   │                                  │
  │  │   key pattern│     │    │   └── TTL: 24 hours                 │
  │  │              │     │    └── expire after                      │
  │  │   aggregate  │     └── value (just a flag)                    │
  │  │   id         └── sequence number                              │
  │  └── command                                                      │
  │                                                                   │
  │  GET cache:account:123:balance                                    │
  │  → "{\"balance\":15000,\"currency\":\"EUR\",\"updated\":...}"    │
  │  (JSON-encoded projection snapshot)                               │
  │                                                                   │
  ├──────────────────────────────────────────────────────────────────┤
  │  HASH (for structured data)                                       │
  │                                                                   │
  │  HSET saga:transfer-789 status "debited"                         │
  │                          source-account "acc-123"                 │
  │                          target-account "acc-456"                 │
  │                          amount "10000"                           │
  │  EXPIRE saga:transfer-789 3600                                    │
  │                                                                   │
  │  (saga correlation state — each field is a step result)          │
  └──────────────────────────────────────────────────────────────────┘

  Why STRING for dedup and cache?
  ─ Atomic SET NX (set if not exists) for dedup → no race conditions
  ─ Single GET/SET for cache → minimal round trips
  ─ TTL per key → automatic cleanup, no garbage collection

  Why HASH for saga state?
  ─ Multiple fields per saga (status, accounts, amount)
  ─ Can update individual fields without reading the whole object
  ─ Single EXPIRE for the entire saga lifecycle
```

---

## 2. Step 1 — Install Redis via Helm

### Create the namespace

```bash
kubectl create namespace redis
```

### Add the Bitnami Helm repo (if not already added from Phase 6)

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
```

#### Why standalone, not replication?

```
  Architecture options

  standalone (staging)              replication (production)
  ──────────────────                ────────────────────────

  ┌──────────────┐                  ┌──────────────┐
  │ redis-master │                  │ redis-master │◄── writes
  │ (read+write) │                  │ (writes)     │
  └──────────────┘                  └──────┬───────┘
                                          │ replication
                                   ┌──────┼──────┐
                                   ▼      ▼      ▼
                              ┌────────┐┌────────┐┌────────┐
                              │replica-0││replica-1││replica-2│◄── reads
                              └────────┘└────────┘└────────┘

  1 pod, 1 PVC                     4 pods, 4 PVCs
  ~$8-10/month                     ~$30-40/month

  For staging, standalone is sufficient:
  ─ No replication overhead
  ─ Lower cost
  ─ Redis as a cache means data loss is tolerable
  ─ Switch to replication for production HA
```

### Create `redis-values.yaml`

```
  What this creates in the cluster

  ┌─ namespace: redis ────────────────────────────────────────────┐
  │                                                                │
  │  StatefulSet: redis-master                                     │
  │  ┌───────────────────────────────────────────────────────┐    │
  │  │  redis-master-0  (single replica for staging)          │    │
  │  │                                                         │    │
  │  │  Containers:                                            │    │
  │  │  ├── redis (:6379)  — the Redis server                 │    │
  │  │  └── metrics (:9121) — Prometheus exporter sidecar      │    │
  │  │                                                         │    │
  │  │  PVC: 8Gi  (RDB snapshots + AOF log)                   │    │
  │  └───────────────────────────────────────────────────────┘    │
  │                                                                │
  │  Services:                                                     │
  │  ├── redis-master      ClusterIP  6379                        │
  │  └── redis-headless    Headless   (for StatefulSet DNS)       │
  │                                                                │
  └────────────────────────────────────────────────────────────────┘
```

```yaml
# Redis — standalone for staging (no replicas)
architecture: standalone

# Auth — password auto-generated on first install, stored in K8s Secret.
# We'll move it to Vault in Step 2.
auth:
  enabled: true
  password: ""

# TLS — encrypt all Redis connections
tls:
  enabled: true
  autoGenerated: true              # Bitnami generates self-signed CA + server cert
  authClients: false               # server-only TLS (no client certs required)

# Master configuration
master:
  # Resources — meets GKE Autopilot minimum (non-bursting)
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 1000m
      memory: 1Gi

  # Persistence — RDB snapshots survive pod restarts
  persistence:
    enabled: true
    size: 8Gi

  # Disable dangerous commands (Bitnami default)
  disableCommands:
    - FLUSHDB
    - FLUSHALL

# No replicas in standalone mode
# (redundant with architecture: standalone, but explicit for clarity)
replica:
  replicaCount: 0

# Redis configuration overrides
# NOTE: commonConfiguration REPLACES the chart default entirely,
# so we must include appendonly yes (which the chart default has).
commonConfiguration: |-
  appendonly yes
  # Save RDB snapshot every 60s if at least 100 keys changed
  save 60 100
  # Max memory for cache use — evict LRU keys when full
  maxmemory 400mb
  maxmemory-policy allkeys-lru

# Metrics for Grafana (Phase 5) — exporter sidecar
metrics:
  enabled: true
  resources:
    requests:
      cpu: 50m
      memory: 64Mi
    limits:
      cpu: 100m
      memory: 128Mi
  # redis-exporter needs TLS config to scrape the TLS-enabled Redis
  extraArgs:
    skip-tls-verification: "true"   # trust the local sidecar (same pod)
  serviceMonitor:
    enabled: false          # not needed — Phase 5 uses OTel Collector, not Prometheus Operator
```

> **GKE Autopilot note:** Resource requests are set to 250m CPU / 512Mi memory — the Autopilot minimum for non-bursting clusters. Same sizing rationale as Vault (Phase 4), observability (Phase 5), and RabbitMQ (Phase 6).

#### How TLS encrypts the Redis connection

```
  TLS encryption between app and Redis (same pattern as RabbitMQ in Phase 6)

  Without TLS:

  registry-api pod              network              redis-master-0
  ┌──────────────┐           (plain text)           ┌──────────────┐
  │ Carmine      │──── SET/GET + password ─────────►│ Redis        │
  │              │     visible on the wire           │ :6379        │
  └──────────────┘                                   └──────────────┘

  With TLS:

  registry-api pod              network              redis-master-0
  ┌──────────────┐           (encrypted)            ┌──────────────┐
  │ Carmine      │──── TLS handshake ──────────────►│ Redis        │
  │ :ssl-fn      │◄─── server cert (auto-generated)─│ :6379 (TLS)  │
  │ :default     │──── encrypted SET/GET ──────────►│              │
  └──────────────┘     password + data unreadable    └──────────────┘

  What `autoGenerated: true` creates:

  ┌─ K8s Secret: redis-certs ───────────────────────────────────────┐
  │                                                                  │
  │  ca.crt       Self-signed Certificate Authority                 │
  │  tls.crt      Server certificate (signed by CA)                 │
  │  tls.key      Server private key                                │
  │                                                                  │
  │  Mounted into Redis pod at /opt/bitnami/redis/certs/            │
  └──────────────────────────────────────────────────────────────────┘

  Port stays at 6379 — Redis replaces the plain listener with TLS.
  No port change needed in service URLs.

  authClients: false  →  server-only TLS (not mutual TLS)
  ─ Redis presents its cert to the client ✓
  ─ Client does NOT present a cert back
  ─ Same model as Phase 6 RabbitMQ TLS
```

> **Why not just rely on GKE's encryption?** Same reasoning as Phase 6 — pods on the same node communicate over the local network without GKE's VM-to-VM encryption. TLS at the application level guarantees encryption regardless of pod placement, and satisfies compliance requirements (PCI-DSS, SOC2, HIPAA).

#### Mount the CA certificate in the app pod

Same pattern as Phase 6 (RabbitMQ CA cert). Add to the registry-api deployment:

```yaml
# In helm/registry-api/templates/deployment.yaml:
spec:
  template:
    spec:
      volumes:
        # ... existing volumes (including rabbitmq-ca from Phase 6) ...
        - name: redis-ca
          secret:
            secretName: redis-ca       # created by bitnami/redis TLS auto-generation
            items:
              - key: tls.crt
                path: redis-ca.crt
      containers:
        - name: registry-api
          volumeMounts:
            # ... existing mounts ...
            - name: redis-ca
              mountPath: /etc/ssl/redis
              readOnly: true
```

Import into the JVM truststore alongside the RabbitMQ CA:

```bash
# Add Redis CA to the same truststore (from Phase 6)
keytool -import -alias redis-ca \
  -file /etc/ssl/redis/redis-ca.crt \
  -keystore /tmp/truststore.jks \
  -storepass changeit -noprompt
```

> **Shared truststore:** If you already created a JKS truststore for RabbitMQ in Phase 6, just add the Redis CA to the same file. One `JAVA_TOOL_OPTIONS` setting covers both.

#### How `maxmemory-policy: allkeys-lru` works

```
  Redis memory management with LRU eviction

  maxmemory: 400mb (of 1Gi container limit)
  ┌─────────────────────────────────────────────────────────────────┐
  │                                                                 │
  │  0 MB          200 MB        400 MB            800 MB    1 Gi  │
  │  ├──────────────┼─────────────┼──────────────────┼────────┤    │
  │  │              │             │                  │        │    │
  │  │   normal     │   normal    │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│ Redis  │    │
  │  │   operation  │   operation │   EVICTION ZONE │ crash  │    │
  │  │              │             │                  │        │    │
  │  │              │         maxmemory = 400mb      │        │    │
  │  │              │             │                  │        │    │
  │  │              │             │  LRU eviction:   │        │    │
  │  │              │             │  oldest-accessed │        │    │
  │  │              │             │  keys deleted    │        │    │
  │  │              │             │  to make room    │        │    │
  │  └──────────────┴─────────────┴──────────────────┴────────┘    │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘

  When Redis hits 400mb:
  1. New SET command arrives
  2. Redis finds the least-recently-used key
  3. Evicts it (deletes it) to free memory
  4. Stores the new key
  5. Repeat until under maxmemory

  Why allkeys-lru?
  ─ "allkeys" = evict from ALL keys (not just those with TTL)
  ─ "lru" = Least Recently Used (most stale data goes first)
  ─ Cache keys and dedup keys both have TTLs, so natural expiry
    handles most cleanup. LRU is the safety net for memory pressure.

  Why 400mb and not higher?
  ─ Redis needs headroom for: fork() during RDB save, replication
    buffers, Lua scripting, client output buffers
  ─ 400mb data + ~100mb overhead = ~500mb, well under the 1Gi limit
  ─ The remaining headroom prevents OOM kills during peak load
```

> **Why `save 60 100`?** Redis periodically saves a snapshot (RDB file) to disk. `save 60 100` means "save if at least 100 keys changed in the last 60 seconds." This gives durability for dedup keys while keeping I/O low. If Redis restarts, it reloads the RDB file and recovers most data. For a pure cache this isn't strictly necessary, but the dedup keys benefit from surviving restarts.

#### How RDB + AOF persistence work together

```
  Two persistence mechanisms — belt and suspenders

  RDB (snapshots)                        AOF (append-only file)
  ─────────────                          ─────────────────────

  ┌──────────────────────┐               ┌──────────────────────┐
  │  Point-in-time        │               │  Every write          │
  │  snapshot of all data │               │  operation logged     │
  │                       │               │                       │
  │  Triggered by:        │               │  appendonly yes       │
  │  save 60 100          │               │  (in commonConfig)    │
  │  (60s + 100 changes)  │               │                       │
  │                       │               │  appendfsync everysec │
  │  File: dump.rdb       │               │  (fsync once/second)  │
  │  Binary, compact      │               │  File: appendonly.aof │
  │  Fast to load         │               │  Text log, larger     │
  └──────────────────────┘               └──────────────────────┘

  Timeline of a crash:

  t=0s        t=30s       t=59s        t=60s       t=61s
  ──┬──────────┬───────────┬────────────┬───────────┬──
    │          │           │            │           │
    RDB save   writes      writes       RDB save    CRASH!
    (full      happening   happening    (captures   │
    snapshot)  (AOF logs   (AOF logs    everything  What's lost?
               each one)   each one)    to t=60s)   │
                                                     ▼
                                                    Only writes
                                                    from t=60s to t=61s
                                                    (up to 1 second of
                                                    AOF data)

  On restart, Redis loads data in this order:
  1. If AOF exists → replay AOF (most complete)
  2. If only RDB → load RDB snapshot (faster but may miss recent writes)

  Why BOTH?
  ─ RDB alone: loses up to 60s of data (between snapshots)
  ─ AOF alone: slower restart (replay every operation), file grows large
  ─ Both: AOF catches recent writes, RDB provides fast backup
  ─ For a cache layer, even losing 1s of data is fine (cache miss → PG)
  ─ For dedup keys, AOF means we almost never re-process a message

  The 8Gi PVC stores both files:
  /data/dump.rdb       (~1-50 MB depending on key count)
  /data/appendonly.aof  (~10-200 MB depending on write volume)
  Plenty of headroom in 8Gi for staging workloads.
```

#### Why StatefulSet (not Deployment)?

```
  StatefulSet vs Deployment — why Redis needs stable identity

  Deployment (what registry-api uses):

  ┌──────────┐   ┌──────────┐   ┌──────────┐
  │ pod-abc  │   │ pod-def  │   │ pod-ghi  │   random names
  │          │   │          │   │          │   no stable storage
  │ no PVC   │   │ no PVC   │   │ no PVC   │   interchangeable
  └──────────┘   └──────────┘   └──────────┘

  Pods are cattle — any can be killed, replaced, scaled.
  No persistent storage (or shared PVC).

  StatefulSet (what Redis uses):

  ┌──────────────────┐
  │ redis-master-0   │   stable name (always "-0")
  │                  │   stable DNS: redis-master-0.redis-headless.redis.svc
  │ PVC: redis-data- │   stable storage (PVC bound to THIS pod)
  │  redis-master-0  │   → same data after restart
  └──────────────────┘

  StatefulSet guarantees:
  1. Stable hostname: redis-master-0 (not redis-master-abc)
  2. Stable storage: PVC is reattached after pod restart
  3. Ordered startup: if multiple replicas, -0 starts first
  4. Ordered shutdown: highest ordinal deleted first

  For Redis this matters because:
  ─ RDB/AOF files on the PVC must survive pod restarts
  ─ The Headless service needs a stable DNS name per pod
  ─ Replicas must know the master's stable address
  ─ Bitnami chart handles all of this automatically
```

```bash
helm install redis bitnami/redis \
  --namespace redis \
  -f redis-values.yaml
```

### Verify Redis is running

```bash
kubectl -n redis get pods
# Expected:
# NAME              READY   STATUS    RESTARTS   AGE
# redis-master-0    2/2     Running   0          2m
#                   ^^^
#                   2 containers: redis + metrics exporter

kubectl -n redis get svc
# Expected:
# redis-master      ClusterIP   ...   6379/TCP
# redis-headless    ClusterIP   None  ...
```

> **Why 2/2 containers?** The `metrics.enabled: true` setting adds a `redis-exporter` sidecar container alongside the main Redis container. The exporter scrapes Redis stats and exposes them as Prometheus-format metrics on port 9121.

### Retrieve the auto-generated password

```bash
# macOS:
kubectl -n redis get secret redis \
  -o jsonpath="{.data.redis-password}" | base64 -D; echo

# Linux:
kubectl -n redis get secret redis \
  -o jsonpath="{.data.redis-password}" | base64 -d; echo
```

Save this password — you'll store it in Vault in Step 2.

### Quick connectivity test

> **TLS note:** With TLS enabled, all `redis-cli` commands need `--tls` and `--cacert` flags. The certs are mounted inside the Redis container at `/opt/bitnami/redis/certs/`. Every `redis-cli` command in this document uses these flags.

```bash
# Connect to Redis from inside the pod (TLS)
kubectl -n redis exec -it redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt \
  -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)"

# At the redis-cli prompt:
# > PING
# PONG
# > SET test:hello "world"
# OK
# > GET test:hello
# "world"
# > DEL test:hello
# (integer) 1
# > exit
```

---

## 3. Step 2 — Store credentials in Vault

Store the Redis password in Vault so the app reads it via Vault Agent Injector (same pattern as DB credentials from Phase 4 and RabbitMQ from Phase 6).

### Write the secret to Vault

```bash
# Port-forward to Vault
kubectl -n vault port-forward svc/vault 8200:8200 &

# Login with your admin token (from Phase 4)
export VAULT_ADDR="https://127.0.0.1:8200"
export VAULT_SKIP_VERIFY=true
vault login <your-admin-token>

# Write Redis credentials
vault kv put secret/staging/registry-api/redis \
  password="<password-from-step-1>"
```

### Update the Vault policy

Add Redis to the existing `registry-api` policy:

```bash
vault policy write registry-api - <<'EOF'
# Existing DB credentials (Phase 4)
path "secret/data/staging/registry-api/db" {
  capabilities = ["read"]
}

# RabbitMQ credentials (Phase 6)
path "secret/data/staging/registry-api/rabbitmq" {
  capabilities = ["read"]
}

# Redis credentials (new)
path "secret/data/staging/registry-api/redis" {
  capabilities = ["read"]
}
EOF
```

```
  Vault secret layout after Phase 7

  secret/staging/registry-api/
  ├── db                          (Phase 4)
  │   ├── username: registry-api
  │   └── password: ********
  ├── rabbitmq                    (Phase 6)
  │   ├── username: registry-api
  │   └── password: ********
  └── redis                       (Phase 7 — new)
      └── password: ********

  All three follow the same pattern:
  ─ CLI write: vault kv put secret/staging/registry-api/<name> ...
  ─ Policy path: secret/data/staging/registry-api/<name>
  ─ Agent template: {{- with secret "secret/data/staging/registry-api/<name>" -}}
  ─ Value access: .Data.data.<field>

  (Remember: /data/ prefix is the KV v2 API path — see Phase 4 and 6.)
```

#### Vault KV v2 — the `/data/` prefix (reminder from Phase 4)

```
  The same path appears THREE different ways depending on context:

  ┌─────────────────────────────────────────────────────────────────────┐
  │ CLI (vault kv put):                                                 │
  │                                                                     │
  │   vault kv put secret/staging/registry-api/redis password="..."     │
  │                ──────────────────────────────────                    │
  │                no /data/ — CLI adds it for you                      │
  ├─────────────────────────────────────────────────────────────────────┤
  │ API / Policy path:                                                  │
  │                                                                     │
  │   path "secret/data/staging/registry-api/redis"                     │
  │                ─────                                                │
  │                /data/ required — this is the actual API path        │
  │                                                                     │
  │   Forget /data/ → 403 Forbidden (policy doesn't match)             │
  ├─────────────────────────────────────────────────────────────────────┤
  │ Agent Injector template:                                            │
  │                                                                     │
  │   {{- with secret "secret/data/staging/registry-api/redis" -}}      │
  │                    ─────                                            │
  │   {{ .Data.data.password }}                                         │
  │           ─────                                                     │
  │   Double .Data.data because KV v2 wraps the value                  │
  └─────────────────────────────────────────────────────────────────────┘

  This catches everyone at least once (see Phase 4 and 6).
  The pattern is identical for db, rabbitmq, and redis secrets.
```

### Update the deployment annotations

Add a Vault Agent Injector annotation to inject the Redis secret. In `helm/registry-api/templates/deployment.yaml`, add to the pod annotations:

```yaml
vault.hashicorp.com/agent-inject-secret-redis: "secret/data/staging/registry-api/redis"
vault.hashicorp.com/agent-inject-template-redis: |
  {{- with secret "secret/data/staging/registry-api/redis" -}}
  export REDIS_PASSWORD="{{ .Data.data.password }}"
  {{- end }}
```

---

## 4. Step 3 — Configure your app to connect

### Add Redis config to `config.edn`

```clojure
;; Add to resources/config.edn:
:redis {:host #or [#env REDIS_HOST "localhost"]
        :port #long #or [#env REDIS_PORT 6379]
        :password #or [#env REDIS_PASSWORD ""]
        :db #long #or [#env REDIS_DB 0]
        :ssl true}
```

### Add Redis to the Helm ConfigMap

Update `helm/registry-api/templates/configmap.yaml` — add these keys:

```yaml
  REDIS_HOST: {{ .Values.config.redisHost | quote }}
  REDIS_PORT: {{ .Values.config.redisPort | quote }}
  REDIS_DB: {{ .Values.config.redisDb | quote }}
```

> **Note:** `REDIS_PASSWORD` comes from Vault (Step 2), not the ConfigMap. Same pattern as DB_USER and RABBITMQ_USER — secrets in Vault, connection config in ConfigMap.

### Add values to `values-staging.yaml`

```yaml
config:
  # ... existing config ...
  redisHost: "redis-master.redis.svc"
  redisPort: "6379"
  redisDb: "0"
```

```
  How the config reaches the app

  ┌────────────────────────┐     ┌────────────────────────┐
  │ values-staging.yaml    │     │ Vault                  │
  │                        │     │                        │
  │ redisHost: redis-      │     │ secret/.../redis       │
  │  master.redis.svc      │     │  password: ********    │
  │ redisPort: 6379        │     │                        │
  │ redisDb: 0             │     │                        │
  └──────────┬─────────────┘     └──────────┬─────────────┘
             │                               │
             ▼                               ▼
  ┌──────────────────────┐     ┌──────────────────────────┐
  │ ConfigMap            │     │ /vault/secrets/redis      │
  │ REDIS_HOST=...       │     │ REDIS_PASSWORD=...        │
  │ REDIS_PORT=6379      │     │                           │
  │ REDIS_DB=0           │     │                           │
  └──────────┬───────────┘     └──────────┬───────────────┘
             │                             │
             └──────────┬──────────────────┘
                        ▼
               ┌──────────────────┐
               │ config.edn reads │
               │ #env REDIS_*     │
               │                  │
               │ Carmine connects │
               │ to Redis         │
               └──────────────────┘
```

### Add Carmine dependency

Add to `deps.edn`:

```clojure
com.taoensso/carmine {:mvn/version "3.5.0"}
```

> **Carmine** is the standard Clojure client for Redis. It wraps Jedis with idiomatic Clojure APIs, connection pooling, pipelining, Lua scripting, and pub/sub. Think of it as the Redis equivalent of Langohr (Phase 6) for RabbitMQ.

#### Carmine TLS connection

With TLS enabled on Redis, Carmine needs `:ssl-fn :default` in the connection spec:

```clojure
;; Option A: Trust the JVM truststore (set via JAVA_TOOL_OPTIONS)
;; This is what the dedup/cache code above uses.
(def redis-conn
  {:pool {}
   :spec {:host     (System/getenv "REDIS_HOST")
          :port     (parse-long (System/getenv "REDIS_PORT"))
          :password (System/getenv "REDIS_PASSWORD")
          :ssl-fn   :default}})   ; wraps socket with SSLSocketFactory

;; Option B: Custom ssl-fn (trusts the mounted CA cert directly)
(import '[javax.net.ssl SSLContext TrustManagerFactory]
        '[java.security KeyStore]
        '[java.security.cert CertificateFactory]
        '[java.io FileInputStream])

(defn make-redis-ssl-fn
  "Build a custom ssl-fn that trusts the Redis CA cert."
  [ca-cert-path]
  (let [cf   (CertificateFactory/getInstance "X.509")
        ca   (with-open [fis (FileInputStream. ca-cert-path)]
               (.generateCertificate cf fis))
        ks   (doto (KeyStore/getInstance (KeyStore/getDefaultType))
               (.load nil nil)
               (.setCertificateEntry "redis-ca" ca))
        tmf  (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
               (.init ks))
        ctx  (doto (SSLContext/getInstance "TLSv1.3")
               (.init nil (.getTrustManagers tmf) nil))
        sf   (.getSocketFactory ctx)]
    (fn [{:keys [^java.net.Socket socket host port]}]
      (.createSocket sf socket host (int port) true))))

(def redis-conn
  {:pool {}
   :spec {:host     (System/getenv "REDIS_HOST")
          :port     (parse-long (System/getenv "REDIS_PORT"))
          :password (System/getenv "REDIS_PASSWORD")
          :ssl-fn   (make-redis-ssl-fn "/etc/ssl/redis/redis-ca.crt")}})
```

> **Option A vs B:** Same trade-off as Phase 6's Langohr TLS. Option A (JVM truststore) is simpler and recommended — one `JAVA_TOOL_OPTIONS` env var covers both RabbitMQ and Redis CAs. Option B avoids global JVM state.

#### How Carmine's connection pool works

```
  Connection pooling — why you need it

  Without pooling:                       With pooling (Carmine default):
  ────────────────                       ────────────────────────────────

  Request 1 → open connection ─────┐    Request 1 ─┐
  Request 2 → open connection ──┐  │               ├─► borrow from pool ──► Redis
  Request 3 → open connection ┐ │  │    Request 2 ─┤    (reuse connections)
                               │ │  │               │
                               ▼ ▼  ▼    Request 3 ─┘
                              Redis                  Pool: [conn1, conn2, ...]
                              (3 TCP                 (pre-established, reused)
                               handshakes)

  Carmine uses Apache Commons Pool (via Jedis):
  ─ Default: 8 max connections (GenericObjectPool)
  ─ Connections are borrowed on wcar, returned automatically
  ─ Idle connections kept alive (pool validates before borrow)

  Customise the pool:

  (def redis-conn
    {:pool {:max-total 16          ; max connections (default 8)
            :max-idle  8           ; max idle connections
            :min-idle  2}          ; keep at least 2 warm
     :spec {:host     (System/getenv "REDIS_HOST")
            :port     (parse-long (System/getenv "REDIS_PORT"))
            :password (System/getenv "REDIS_PASSWORD")
            :ssl-fn   :default}})  ; TLS (uses JVM truststore)

  For our staging setup (2 app pods, moderate load),
  the default pool ({}) is fine.
  Production with 10+ pods: consider max-total 16-32.

  What happens when Redis restarts?
  ─────────────────────────────────
  1. Redis pod restarts (e.g., node preemption)
  2. Existing pool connections become dead
  3. Next wcar call gets a dead connection
  4. Carmine detects the broken connection
  5. Pool evicts dead connection, creates new one
  6. Retry succeeds (transparent to your code)
  7. Brief spike in latency (~1-5ms for new TCP handshake)

  No manual reconnection logic needed.
  The pool handles it automatically.
```

---

## 5. Step 4 — Deploy and verify

### Deploy the config change

```bash
helm upgrade --install registry-api helm/registry-api \
  -n registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag="<your-image-tag>"
```

### Verify the connection

```bash
# Check the app logs for Redis connection
kubectl -n registry-api logs -l app=registry-api --tail=20 | grep -i redis
# Look for: "Connected to Redis" or Carmine connection pool initialization

# Verify from inside the app pod that Redis is reachable
kubectl -n registry-api exec <pod-name> -- nc -zv redis-master.redis.svc 6379
# Should show: redis-master.redis.svc (10.x.x.x) open

# Verify TLS is working (from inside the Redis pod)
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt \
  -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  INFO server | grep ssl
# Should show: ssl_enabled:yes
```

### Verify Redis can store and retrieve data

```bash
kubectl -n redis exec -it redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  INFO keyspace
# Should show db0 with some keys if the app has connected and written data
# Empty result is normal if the app hasn't started using Redis yet
```

---

## 6. Step 5 — Implement caching and deduplication patterns

This section shows the Clojure code patterns for using Redis in your event sourcing app.

### Pattern 1: Message deduplication (most important)

This is the primary reason for adding Redis. Every RabbitMQ consumer should check for duplicates before processing.

```clojure
;; es.redis/dedup — idempotency guard for message consumers
;;
;; Requires:
;;   [taoensso.carmine :as car]

(def redis-conn {:pool {} :spec {:host     (System/getenv "REDIS_HOST")
                                  :port     (parse-long (System/getenv "REDIS_PORT"))
                                  :password (System/getenv "REDIS_PASSWORD")
                                  :ssl-fn   :default}})  ; TLS via JVM truststore

(defmacro wcar [& body] `(car/wcar redis-conn ~@body))

(defn already-processed?
  "Check if this event has already been processed. Returns true if duplicate."
  [aggregate-id sequence-number]
  (let [key (str "dedup:" aggregate-id ":" sequence-number)]
    (= "1" (wcar (car/get key)))))

(defn mark-processed!
  "Mark an event as processed. TTL ensures keys expire after 24 hours."
  [aggregate-id sequence-number]
  (let [key (str "dedup:" aggregate-id ":" sequence-number)
        ttl-seconds 86400]   ; 24 hours
    (wcar (car/set key "1" "EX" ttl-seconds))))
```

```
  How deduplication integrates with the RabbitMQ consumer (Phase 6)

  Message arrives from RabbitMQ queue
  │
  ▼
  ┌─────────────────────────────────────────────────────────────┐
  │  Consumer handler                                            │
  │                                                              │
  │  1. Extract aggregate-id and sequence-number from message    │
  │     │                                                        │
  │     ▼                                                        │
  │  2. (already-processed? agg-id seq-num)                      │
  │     │                                                        │
  │     ├── YES (key exists in Redis)                           │
  │     │   └── ACK the message, skip processing                │
  │     │       (idempotent — safe to ACK without doing work)   │
  │     │                                                        │
  │     └── NO (key not in Redis)                               │
  │         │                                                    │
  │         ▼                                                    │
  │  3. Process the message:                                     │
  │     ├── Update PostgreSQL projection                        │
  │     ├── Invalidate Redis cache (if applicable)              │
  │     └── (mark-processed! agg-id seq-num)                    │
  │         │                                                    │
  │         ▼                                                    │
  │  4. ACK the message to RabbitMQ                              │
  │                                                              │
  │  If crash between step 3 and 4:                             │
  │  ─ Message is redelivered by RabbitMQ                       │
  │  ─ Step 2 detects the duplicate → skip                      │
  │  ─ No double-write to PostgreSQL ✓                          │
  └─────────────────────────────────────────────────────────────┘

  Race condition? Two consumers processing the same message?
  ─ SET NX (set if not exists) is atomic in Redis
  ─ Only one consumer wins, the other sees the key and skips
  ─ (In practice, RabbitMQ delivers each message to ONE consumer
     per queue, so this is a safety net, not the normal path)
```

#### How SET NX prevents the race condition

```
  Scenario: RabbitMQ redelivers message to two consumer pods
  (rare, but possible during consumer scaling or network partition)

  Time     Consumer A (pod-0)           Redis               Consumer B (pod-1)
  ────     ─────────────────           ─────               ─────────────────
  t=0ms    receives msg                                    receives msg
           (redelivery)                                    (redelivery)
           │                                                │
  t=1ms    GET dedup:acc-123:42 ──────► nil (not found)     │
           │                                                │
  t=2ms    │                            ◄────── GET dedup:acc-123:42
           │                            nil (not found)
           │                                                │
  t=3ms    SET dedup:acc-123:42 ──────► stored! ✓           │
           "1" EX 86400                 │                    │
           │                            │                    │
  t=4ms    │                            ◄────── SET dedup:acc-123:42
           │                            already exists!      "1" EX 86400
           │                            (returns nil)        │
           │                                                │
  t=5ms    process msg ✓                                   sees nil → skip ✓
           write to PG                                      ACK to RabbitMQ
           mark processed                                   (no duplicate write)
           ACK to RabbitMQ

  WAIT — there's a gap between GET (t=1) and SET (t=3) for Consumer A.
  What if Consumer B's SET lands in that gap?

  This is why we DON'T use separate GET + SET. Instead, we use:

  SET key value NX EX ttl
  │              │  │
  │              │  └── with expiry
  │              └── only if Not eXists (atomic check-and-set)
  └── single command, single Redis operation

  The actual safe pattern (what our code does):

  Time     Consumer A                   Redis               Consumer B
  ────     ──────────                   ─────               ──────────
  t=0ms    receives msg                                    receives msg
           │                                                │
  t=1ms    GET dedup key ────────────► nil                  │
           │                                                │
  t=2ms    process message              ◄────── GET dedup key
           (takes ~5ms)                 nil
           │                                                │
  t=5ms    │                                               process message
           │                                                │
  t=7ms    SET dedup key ────────────► stored! ✓            │
           (mark-processed!)            │                    │
           │                            │                    │
  t=10ms   │                            ◄────── SET dedup key
           │                            stored! (overwrites) │
           │                                                │
  t=11ms   ACK ✓                                           ACK ✓

  Both processed! This is a problem with GET-then-SET.

  The REAL fix: make the business logic itself idempotent.
  ─ Use INSERT ... ON CONFLICT DO NOTHING for projections
  ─ Use aggregate version checks for event appends
  ─ The Redis dedup check is a FAST PATH optimisation
  ─ PostgreSQL constraints are the TRUE safety net
  ─ Redis prevents 99.9% of duplicates, PG catches the rest
```

> **Two layers of protection:** Redis dedup is the fast path — it prevents most duplicate processing with a sub-millisecond check. But for the rare race condition where two consumers both pass the Redis check, your PostgreSQL write must also be idempotent (e.g., `INSERT ON CONFLICT DO NOTHING` or aggregate version checks). Think of Redis as the bouncer and PostgreSQL as the lock on the door.

### Pattern 2: Read model cache

```clojure
;; es.redis/cache — cache-aside pattern for read model projections

(defn cache-get
  "Get a cached value. Returns nil on cache miss."
  [cache-key]
  (let [raw (wcar (car/get cache-key))]
    (when raw
      (edn/read-string raw))))

(defn cache-set!
  "Cache a value with TTL (default 1 hour)."
  ([cache-key value] (cache-set! cache-key value 3600))
  ([cache-key value ttl-seconds]
   (wcar (car/set cache-key (pr-str value) "EX" ttl-seconds))))

(defn cache-invalidate!
  "Remove a cached value (called by projection consumer after update)."
  [cache-key]
  (wcar (car/del cache-key)))

;; Usage in API handler:
(defn get-account-balance [account-id]
  (let [cache-key (str "cache:account:" account-id ":balance")]
    (or (cache-get cache-key)
        (let [balance (db/query-account-balance account-id)]  ; PostgreSQL fallback
          (cache-set! cache-key balance)
          balance))))

;; Usage in projection consumer (invalidate after update):
(defn handle-account-event [event]
  (let [{:keys [aggregate-id]} event]
    (db/update-account-projection! event)                     ; write to PostgreSQL
    (cache-invalidate! (str "cache:account:" aggregate-id ":balance"))))
```

```
  Cache-aside pattern — read and write paths

  Read path (API query):

  GET /accounts/123/balance
       │
       ▼
  ┌──────────┐  GET cache:account:123:balance  ┌───────────┐
  │ handler  │────────────────────────────────►│ Redis     │
  │          │◄────────────────────────────────│           │
  │          │  hit? return cached value        └───────────┘
  │          │
  │          │  miss? query PostgreSQL
  │          │────────────────────────────────►┌──────────────┐
  │          │◄────────────────────────────────│ PostgreSQL   │
  │          │  SET cache:account:123:balance  └──────────────┘
  │          │────────────────────────────────►┌───────────┐
  └──────────┘  EX 3600 (cache for 1 hour)    │ Redis     │
                                               └───────────┘

  Write path (projection consumer):

  RabbitMQ delivers AccountBalanceChanged event
       │
       ▼
  ┌──────────────────┐  UPDATE projections  ┌──────────────┐
  │ projection       │─────────────────────►│ PostgreSQL   │
  │ consumer         │                       └──────────────┘
  │                  │  DEL cache:account:123:balance
  │                  │─────────────────────►┌───────────┐
  └──────────────────┘  (invalidate cache)  │ Redis     │
                                             └───────────┘

  Next read will be a cache miss → fresh data from PostgreSQL
  gets cached. This is "cache-aside" (also called "lazy loading").
```

#### Why cache-aside (and not other strategies)?

```
  Cache invalidation strategies compared

  ┌─────────────────────────────────────────────────────────────────────┐
  │ 1. CACHE-ASIDE (what we use)                                        │
  │                                                                     │
  │    Read: app checks cache → miss → reads DB → fills cache          │
  │    Write: app writes DB → deletes cache key                        │
  │                                                                     │
  │    ✓ Simple to implement                                            │
  │    ✓ Cache only contains data that's actually read (no waste)      │
  │    ✓ App controls what's cached and for how long                   │
  │    ✗ First read after invalidation is slow (cache miss)            │
  │    ✗ Stale data possible if TTL hasn't expired and no invalidation │
  │                                                                     │
  │    Perfect for: read-heavy APIs with event-driven invalidation ← US │
  ├─────────────────────────────────────────────────────────────────────┤
  │ 2. WRITE-THROUGH                                                    │
  │                                                                     │
  │    Read: app reads from cache only                                  │
  │    Write: app writes to cache AND DB (in same operation)           │
  │                                                                     │
  │    ✓ Cache always has latest data                                   │
  │    ✗ Every write is slower (two writes)                             │
  │    ✗ Cache fills with data that may never be read                  │
  │                                                                     │
  │    Better for: shopping carts, session data (read what you write)   │
  ├─────────────────────────────────────────────────────────────────────┤
  │ 3. WRITE-BEHIND (write-back)                                        │
  │                                                                     │
  │    Read: app reads from cache only                                  │
  │    Write: app writes to cache → cache async writes to DB           │
  │                                                                     │
  │    ✓ Fastest writes (async to DB)                                   │
  │    ✗ Data loss risk if cache crashes before DB write                │
  │    ✗ Complex — cache becomes the write path                        │
  │                                                                     │
  │    Better for: high-write systems where speed > durability          │
  └─────────────────────────────────────────────────────────────────────┘

  Why cache-aside fits event sourcing:

  ┌───────────────────────────────────────────────────────────────────┐
  │  Event sourcing already separates reads and writes:               │
  │                                                                   │
  │  Write path: Command → Decider → Event Store (PostgreSQL)        │
  │              (Redis NOT involved — source of truth is PG)        │
  │                                                                   │
  │  Read path:  Query → Redis cache → PostgreSQL fallback           │
  │              (cache-aside — lazy, only caches what's read)       │
  │                                                                   │
  │  Invalidation: Projection consumer writes to PG → deletes cache  │
  │              (event-driven — no polling, no TTL-only staleness)  │
  │                                                                   │
  │  This gives us:                                                   │
  │  ─ Writes go to the source of truth (PG) without cache overhead  │
  │  ─ Reads are fast (Redis) with automatic freshness (invalidation)│
  │  ─ If Redis dies, reads just hit PG (slower, not broken)         │
  └───────────────────────────────────────────────────────────────────┘
```

#### TTL lifecycle — how keys expire and get evicted

```
  Key lifecycle from creation to removal

  t=0          t=30min      t=1hr       t=1hr+1s     t=???
  ─┬────────────┬────────────┬───────────┬────────────┬──
   │            │            │           │            │
   SET key      key used     key used    TTL expires  eviction
   EX 3600      (access      (access     │            (only if
   (1hr TTL)    resets       does NOT    key is       maxmemory
   │            nothing —    reset TTL   silently     pressure)
   │            Redis LRU    in our      removed
   │            tracks last  setup)      from memory
   │            access time)

  Two ways a key gets removed:

  1. TTL EXPIRY (passive + lazy)
     ─────────────────────────────
     Redis does NOT scan all keys looking for expired ones.
     Instead, it uses two strategies:

     Passive: when a client accesses a key, Redis checks TTL.
              If expired → return nil, delete key.

     Active:  10 times per second, Redis samples 20 random keys
              with TTLs. Deletes any that are expired.
              If > 25% were expired, repeat immediately.

     Result: expired keys may linger briefly, but memory is
     reclaimed quickly under normal load.

  2. LRU EVICTION (when maxmemory reached)
     ────────────────────────────────────
     Only kicks in when used_memory > maxmemory (400mb).

     allkeys-lru algorithm:
     ├── Sample 5 random keys (configurable via maxmemory-samples)
     ├── Check their last-access timestamp
     ├── Evict the least-recently-used among the sample
     └── Repeat until under maxmemory

     Not true LRU (would need a linked list of all keys).
     Approximate LRU — good enough, much less memory overhead.

  For our use cases:
  ─ Dedup keys (TTL 24h): expire naturally, rarely evicted
  ─ Cache keys (TTL 1h): expire naturally, evicted under pressure
  ─ Saga keys (TTL 1h): expire naturally after saga completes
  ─ Search cache (TTL 15m): shortest lived, expire first
```

### Pattern 3: Search cache

```clojure
;; es.redis/search-cache — cache expensive search queries

(defn search-cache-key
  "Generate a deterministic cache key from query parameters."
  [query-params]
  (let [sorted (into (sorted-map) query-params)
        hash (hash sorted)]
    (str "cache:search:" hash)))

(defn cached-search
  "Search with cache. Short TTL (15 min) for freshness."
  [query-params search-fn]
  (let [cache-key (search-cache-key query-params)]
    (or (cache-get cache-key)
        (let [results (search-fn query-params)]  ; expensive PostgreSQL query
          (cache-set! cache-key results 900)      ; 15 min TTL
          results))))

;; Usage:
;; (cached-search {:q "transfer" :status "completed" :limit 50}
;;                es.search/query-events)
```

```
  Search cache flow

  GET /api/search?q=transfer&status=completed
       │
       ▼
  ┌──────────────┐  hash(sorted-params) = "a3f2b1"
  │ API handler  │
  │              │  GET cache:search:a3f2b1
  │              │──────────────────────────────►┌───────────┐
  │              │◄──────────────────────────────│ Redis     │
  │              │                                └───────────┘
  │              │  hit? → return cached results (sub-ms)
  │              │
  │              │  miss? → full-text search
  │              │──────────────────────────────►┌──────────────┐
  │              │◄──────────────────────────────│ PostgreSQL   │
  │              │  SET cache:search:a3f2b1      │ (expensive   │
  │              │──────────────────────────────►│  query ~50ms)│
  └──────────────┘  EX 900 (15 min)             └──────────────┘

  Why only 15 minutes?
  ─ Search results change as new events are processed
  ─ Short TTL = reasonable freshness without invalidation complexity
  ─ Unlike account balance (invalidated on event), search touches
    many aggregates — invalidating on every event would negate caching
  ─ 15 min is a pragmatic trade-off: stale by minutes, not hours
```

### Pattern 4: Saga correlation state

```clojure
;; es.redis/saga — correlation state for multi-step sagas

(defn saga-set-state!
  "Store saga state as a Redis hash. TTL prevents orphaned sagas."
  [saga-id state-map ttl-seconds]
  (let [key (str "saga:" saga-id)]
    (wcar
      (apply car/hset key (flatten (seq state-map)))
      (car/expire key ttl-seconds))))

(defn saga-get-state
  "Retrieve saga state. hgetall returns a flat vector [k1 v1 k2 v2 ...],
   so we convert it to a map."
  [saga-id]
  (let [key (str "saga:" saga-id)
        result (wcar (car/hgetall key))]
    (when (seq result)
      (apply hash-map result))))

(defn saga-update-step!
  "Update a single field in the saga state."
  [saga-id field value]
  (let [key (str "saga:" saga-id)]
    (wcar (car/hset key field value))))
```

```
  Saga transfer lifecycle through Redis HASH

  Transfer saga: move $100 from acc-123 to acc-456

  Step 1: Initiate transfer
  ┌──────────────────────────────────────────────────────────────────┐
  │ HSET saga:transfer-789 status "initiated"                        │
  │                         source-account "acc-123"                 │
  │                         target-account "acc-456"                 │
  │                         amount "10000"                           │
  │ EXPIRE saga:transfer-789 3600                                    │
  └──────────────────────────────────────────────────────────────────┘
  Redis HASH: saga:transfer-789
  ┌──────────────────┬────────────┐
  │ status           │ initiated  │
  │ source-account   │ acc-123    │
  │ target-account   │ acc-456    │
  │ amount           │ 10000      │
  └──────────────────┴────────────┘

  Step 2: Debit source account (via RabbitMQ command)
  ┌──────────────────────────────────────────────────────────────────┐
  │ HSET saga:transfer-789 status "debited"                          │
  │                         debit-event-id "evt-abc"                 │
  └──────────────────────────────────────────────────────────────────┘
  Redis HASH: saga:transfer-789
  ┌──────────────────┬────────────┐
  │ status           │ debited    │ ← updated
  │ source-account   │ acc-123    │
  │ target-account   │ acc-456    │
  │ amount           │ 10000      │
  │ debit-event-id   │ evt-abc    │ ← new field
  └──────────────────┴────────────┘

  Step 3: Credit target account
  ┌──────────────────────────────────────────────────────────────────┐
  │ HSET saga:transfer-789 status "completed"                        │
  │                         credit-event-id "evt-def"                │
  └──────────────────────────────────────────────────────────────────┘

  Step 4 (error path): If credit fails → compensation
  ┌──────────────────────────────────────────────────────────────────┐
  │ HSET saga:transfer-789 status "compensating"                     │
  │ → saga reads source-account, amount from HASH                   │
  │ → issues reverse debit command to acc-123                       │
  │ HSET saga:transfer-789 status "compensated"                      │
  └──────────────────────────────────────────────────────────────────┘

  Why Redis HASH and not PostgreSQL?
  ─ Saga state is read/written many times per saga (~4-6 operations)
  ─ Redis HASH reads are ~0.1ms, PG would be ~2-10ms each
  ─ Saga state is short-lived (minutes) — doesn't need durable storage
  ─ TTL auto-cleans orphaned sagas (no garbage collection needed)
  ─ HSET on individual fields avoids read-modify-write cycles

  Why not a Redis STRING with JSON?
  ─ HASH lets you update one field without reading the whole object
  ─ HGET saga:transfer-789 status = just the status (not entire blob)
  ─ No serialize/deserialize overhead for partial reads
```

> **Note:** You've written the cache and dedup code but haven't deployed it yet. You'll verify that Redis integration works correctly in Step 4 (deploy and verify) after re-deploying the app.

---

## 7. Step 6 — Security hardening

### NetworkPolicy — restrict access to Redis

Only allow traffic from the `registry-api` namespace. Redis should never be accessible from other namespaces.

```
  NetworkPolicy — what's allowed and what's blocked

  ┌─ namespace: registry-api ──────┐
  │                                 │
  │  registry-api pod               │
  │  └── Redis :6379 ───────────────────► redis-master-0     ✅ allowed
  │                                 │
  └─────────────────────────────────┘

  ┌─ namespace: redis ───────────────────────────────────────────┐
  │                                                               │
  │  redis-master-0 ←── from registry-api          ✅ allowed     │
  │  redis-master-0 ←── metrics from observability  ✅ allowed     │
  │                                                               │
  └───────────────────────────────────────────────────────────────┘

  ┌─ external / other namespaces ──┐
  │                                 │
  │  anything ──────────────────────────► redis          ✗ blocked
  │                                 │
  └─────────────────────────────────┘
```

Add to `k8s/network-policies.yaml`:

```yaml
---
# Only allow Redis ingress from registry-api namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: redis-ingress
  namespace: redis
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: redis
  policyTypes:
    - Ingress
  ingress:
    # Allow Redis connections from app namespace
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: registry-api
      ports:
        - protocol: TCP
          port: 6379    # Redis
    # Allow metrics scraping from observability namespace
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: observability
      ports:
        - protocol: TCP
          port: 9121    # metrics (Prometheus format, scraped by OTel Collector)
```

Apply:

```bash
kubectl label namespace redis kubernetes.io/metadata.name=redis --overwrite
kubectl apply -f k8s/network-policies.yaml
```

> **GKE Autopilot note:** GKE Autopilot supports NetworkPolicies natively — no extra CNI plugin needed. The `kubernetes.io/metadata.name` label is automatically set on namespaces in recent K8s versions (1.22+), so the `kubectl label` command above is a safety measure. Same pattern as Phases 4, 5, and 6.

### Update the registry-api egress policy

Add this rule to the `registry-api-egress` NetworkPolicy:

```yaml
    # Allow traffic to Redis
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: redis
      ports:
        - protocol: TCP
          port: 6379
```

### Pod security context

The Bitnami chart already runs with strong defaults out of the box (same as RabbitMQ in Phase 6):

```yaml
# Already set by default in bitnami/redis:
podSecurityContext:
  fsGroup: 1001

containerSecurityContext:
  runAsUser: 1001
  runAsGroup: 1001
  runAsNonRoot: true
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities:
    drop: ["ALL"]
  seccompProfile:
    type: "RuntimeDefault"
```

> **No changes needed** — the Bitnami chart ships with security best practices enabled by default.

### Disable dangerous commands

The Helm values already include:

```yaml
master:
  disableCommands:
    - FLUSHDB     # would delete all keys in the current database
    - FLUSHALL    # would delete all keys in ALL databases
```

> **Why disable these?** A single `FLUSHALL` would wipe every dedup key and cached projection. While Redis data is rebuildable, losing all dedup keys means every in-flight RabbitMQ message would be re-processed — potentially causing duplicate side effects until the dedup keys are regenerated. Disabling these commands prevents accidental (or malicious) data loss.

---

## 8. Step 7 — Monitoring with Grafana

If you completed Phase 5 (observability), connect Redis metrics to Grafana.

### How metrics flow

```
  Redis metrics pipeline

  redis-master-0                   OTel Collector              Mimir
  ┌──────────────────┐            ┌───────────────┐          ┌──────────┐
  │ redis container  │            │ prometheus    │── push ──►│ gateway  │
  │ :6379            │            │ receiver      │  remote   │ :80      │
  │                  │            │ (scrapes      │  write    └──────────┘
  │ exporter sidecar │◄── scrape─│  Prom format) │                │
  │ /metrics :9121   │            └───────────────┘                ▼
  └──────────────────┘                                       ┌──────────┐
                                                             │ Grafana  │
  No standalone Prometheus server — OTel Collector            │ PromQL   │
  scrapes the exporter sidecar and remote-writes             └──────────┘
  to Mimir (same pipeline as Phase 5 app metrics
  and Phase 6 RabbitMQ metrics).
```

### Add a scrape config to OTel Collector

Update `otel-collector-values.yaml` — add Redis alongside the existing RabbitMQ scrape target:

```yaml
config:
  receivers:
    # ... existing receivers ...
    prometheus/rabbitmq:
      # ... existing from Phase 6 ...
    prometheus/redis:
      config:
        scrape_configs:
          - job_name: redis
            scrape_interval: 30s
            static_configs:
              - targets: ["redis-master.redis.svc:9121"]

  service:
    pipelines:
      metrics:
        receivers: [otlp, prometheus/rabbitmq, prometheus/redis]   # add redis
        processors: [memory_limiter, resource, batch]
        exporters: [prometheusremotewrite]
```

### Key PromQL queries for Redis (query in Grafana → Mimir datasource)

| Panel | PromQL |
|-------|--------|
| Commands processed/sec | `rate(redis_commands_processed_total[5m])` |
| Cache hit ratio | `rate(redis_keyspace_hits_total[5m]) / (rate(redis_keyspace_hits_total[5m]) + rate(redis_keyspace_misses_total[5m]))` |
| Memory used | `redis_memory_used_bytes` |
| Memory max (maxmemory) | `redis_memory_max_bytes` |
| Connected clients | `redis_connected_clients` |
| Keys per database | `redis_db_keys` |
| Evicted keys/sec | `rate(redis_evicted_keys_total[5m])` |
| Expired keys/sec | `rate(redis_expired_keys_total[5m])` |
| Blocked clients | `redis_blocked_clients` |

### Essential alerts

| Alert | Condition | Severity |
|-------|-----------|----------|
| Redis down | `up{job="redis"} == 0` for 2 min | Critical |
| Memory near maxmemory | `redis_memory_used_bytes / redis_memory_max_bytes > 0.9` for 5 min | Warning |
| High eviction rate | `rate(redis_evicted_keys_total[5m]) > 100` for 10 min | Warning |
| Low cache hit ratio | `rate(redis_keyspace_hits_total[5m]) / (rate(redis_keyspace_hits_total[5m]) + rate(redis_keyspace_misses_total[5m])) < 0.5` for 30 min | Warning |
| No connected clients | `redis_connected_clients == 0` for 5 min | Warning |

```
  What healthy vs unhealthy looks like in Grafana

  HEALTHY                                UNHEALTHY
  ───────                                ─────────

  Commands/sec:  ████████  1200/s        Commands/sec:  ████████  1200/s
  Hit ratio:     █████████  94%          Hit ratio:     ██  23%  ← low!
                 (most reads from cache)                 (cache not working)

  Memory:        ████  180/400 MB        Memory:        ████████ 390/400 MB
                 (well under maxmemory)                 (near eviction!)

  Evicted/sec:   0                       Evicted/sec:   ████  450/s
                 (no pressure)                          (keys being dropped)

  Clients:       ██  4                   Clients:       0
                 (app pods connected)                   (nobody connected!)

  Keys:          ██████  12,847          Keys:          █  500
                 (healthy working set)                  (everything evicted)
```

> **The cache hit ratio is the most important metric.** If it drops below ~70%, you're not getting much value from Redis — investigate whether TTLs are too short, keys are being evicted too aggressively, or the app isn't caching effectively.

---

## 9. How it works at runtime

```
  Complete flow — deposit with dedup + cache

  1. HTTP request: POST /api/accounts/:id/deposit
     │
  2. Command handler (write path — no Redis involved):
     │  ├── Load account aggregate from event store (PostgreSQL)
     │  ├── Apply business rules
     │  ├── Append DepositCompleted event to event store
     │  └── Write outbox record (same DB transaction)
     │
  3. Outbox publisher → RabbitMQ (Phase 6):
     │  └── Publish to es.outbox exchange, routing key "deposit.completed"
     │
  4. Projection consumer receives message:
     │  ├── Extract aggregate-id + sequence-number
     │  ├── Check Redis: (already-processed? "acc-123" 42)
     │  │   ├── YES → ACK message, skip (duplicate)
     │  │   └── NO → continue processing
     │  │
     │  ├── Update PostgreSQL projection (account balance)
     │  ├── Invalidate Redis cache: DEL cache:account:acc-123:balance
     │  ├── Mark processed: SET dedup:acc-123:42 "1" EX 86400
     │  └── ACK message to RabbitMQ
     │
  5. Next API query: GET /api/accounts/acc-123/balance
        ├── Check Redis cache: GET cache:account:acc-123:balance
        │   └── MISS (was just invalidated)
        ├── Query PostgreSQL: SELECT balance FROM projections WHERE ...
        ├── Cache result: SET cache:account:acc-123:balance "{...}" EX 3600
        └── Return fresh balance to client

  Subsequent queries for the same account hit the Redis cache
  until the next event invalidates it again (~0.1ms vs ~2-10ms).
```

---

## 10. Day-2 operations

#### What happens when Redis restarts?

```
  Pod restart → what survives and what doesn't

  SURVIVES (persisted to PVC via RDB):     LOST (if no persistence):
  ─────────────────────────────────        ─────────────────────────

  ✓ Keys written before last RDB save      ✗ Keys written after last
    (save 60 100 = every 60s if               RDB save (up to 60s of
     100+ keys changed)                       data, depending on timing)

  ✓ RDB snapshot file                      ✗ Active connections
    (/data/dump.rdb)                         (app reconnects via Carmine
                                              connection pool)
  After restart:
  ─ Redis loads AOF first (if exists), then RDB as fallback
  ─ Most dedup keys and cache entries recover
  ─ Any lost cache keys → cache miss → PostgreSQL fallback
  ─ Any lost dedup keys → message reprocessed (idempotent consumer)
  ─ System self-heals within seconds
```

```
  Pod restart timeline — what the app experiences

  Time     Redis pod          PVC (disk)           App pods
  ────     ─────────          ──────────           ────────
  t=0s     running            dump.rdb             connected ✓
           serving requests   appendonly.aof        cache hits ✓
           │                                        │
  t=1s     KILLED             persisted on disk     │
           (node preemption   (PVC survives pod     connection lost!
            or OOM)            deletion)             │
           │                                        │
  t=2s     │                                       wcar calls fail
           │                                       Carmine pool:
           │                                        dead connections
           │                                        │
  t=5s     StatefulSet                              cache misses →
           recreates pod                             fall through to PG
           │                                        (app still works,
  t=10s    pod starting                              just slower)
           loading AOF/RDB                           │
           from PVC                                  │
           │                                         │
  t=15s    READY              same data as          pool creates new
           accepting          before crash           connections
           connections        (minus last ~1s)       │
           │                                         │
  t=16s    serving ✓                                 cache hits ✓
                                                     back to normal

  Total disruption: ~15 seconds of cache misses
  Data loss: ~1 second of writes (AOF fsync interval)
  App impact: slower queries, no errors (graceful degradation)
```

#### Daily health check — what to look at in Grafana

```
  Quick daily scan (30 seconds in Grafana)

  1. Redis up?
     redis_up == 1                    ← if 0, everything else is moot

  2. Cache hit ratio > 70%?
     ┌──────────────────────────────────────────────────────┐
     │                                                      │
     │  > 90%  Excellent — Redis is earning its keep        │
     │  70-90% Good — normal for mixed workloads            │
     │  50-70% Investigate — TTLs too short? wrong keys?    │
     │  < 50%  Problem — Redis isn't helping much           │
     │                                                      │
     └──────────────────────────────────────────────────────┘

  3. Memory under control?
     used_memory / maxmemory < 0.8    ← if > 0.9, evictions start

  4. Eviction rate near zero?
     rate(evicted_keys_total) ≈ 0     ← if high, cache is thrashing

  5. Connected clients > 0?
     redis_connected_clients >= 2     ← should match app pod count
                                        (each pod has a connection pool)

  If all five are green, Redis is healthy. Move on.
  If any are amber/red, check the troubleshooting section below.
```

### Connect to Redis CLI

```bash
kubectl -n redis exec -it redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)"
```

### Check key counts and memory

```bash
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  INFO memory | grep -E "used_memory_human|maxmemory_human"

kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  INFO keyspace
```

### Inspect specific keys

```bash
# Count dedup keys
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  EVAL "return #redis.call('KEYS','dedup:*')" 0

# Check a cached value
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  GET cache:account:acc-123:balance

# Check TTL on a dedup key
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  TTL dedup:acc-123:42
```

> **Note:** `KEYS` is fine for debugging with a small dataset. In production with millions of keys, use `SCAN` instead — `KEYS` blocks the server while iterating.

### Scale to replication (production)

For high availability, switch from standalone to replication:

```yaml
# In redis-values.yaml for production:
architecture: replication

replica:
  replicaCount: 3
  resources:
    requests:
      cpu: 250m
      memory: 512Mi
    limits:
      cpu: 1000m
      memory: 1Gi
  persistence:
    enabled: true
    size: 8Gi
```

```
  Production replication topology

  ┌─ namespace: redis ──────────────────────────────────────────────────┐
  │                                                                      │
  │  ┌──────────────┐                                                   │
  │  │ redis-master │◄── all writes go here                            │
  │  │ -0           │                                                   │
  │  └──────┬───────┘                                                   │
  │         │ async replication                                          │
  │         │ (master pushes changes to replicas)                       │
  │  ┌──────┼──────────────┬──────────────┐                             │
  │  ▼      ▼              ▼              ▼                             │
  │  ┌────────────┐ ┌────────────┐ ┌────────────┐                      │
  │  │ replica-0  │ │ replica-1  │ │ replica-2  │◄── reads can go here│
  │  │ (read-only)│ │ (read-only)│ │ (read-only)│                      │
  │  └────────────┘ └────────────┘ └────────────┘                      │
  │                                                                      │
  │  If master fails:                                                   │
  │  ─ No automatic failover (no Sentinel in this config)              │
  │  ─ Replicas have the data but are read-only                        │
  │  ─ For auto-failover, enable Sentinel (sentinel.enabled: true)     │
  │  ─ Or accept brief downtime: delete the master pod,                │
  │    StatefulSet recreates it, loads RDB from PVC                    │
  │                                                                      │
  │  For a pure cache, brief master downtime is acceptable:            │
  │  ─ Cache misses fall through to PostgreSQL                         │
  │  ─ Dedup checks fail → messages reprocessed (idempotent)           │
  │  ─ System degrades gracefully, never breaks                        │
  └──────────────────────────────────────────────────────────────────────┘
```

> **Do you need Sentinel?** For a cache layer with PostgreSQL as the source of truth, probably not. Sentinel adds complexity (3 more pods) for automatic master failover. Since all Redis data is rebuildable, a brief cache-miss window during manual recovery is usually acceptable. Add Sentinel only if you have use cases requiring Redis high availability (e.g., rate limiting, distributed locks).

### Rotate credentials

```
  Credential rotation — order matters (same pattern as Phase 6)

  Step 1: Generate new password
          ▼
  Step 2: Update in Redis FIRST (redis-master accepts new password)
          ▼
  Step 3: Update Vault (so new pods get the new password)
          ▼
  Step 4: Rolling restart app pods (pick up new Vault secret)
```

```
  Why this order? What happens if you get it wrong:

  CORRECT ORDER                           WRONG ORDER (Vault first)
  ─────────────                           ─────────────────────────

  1. Redis gets new password              1. Vault gets new password
     ├── Redis accepts BOTH old            ├── Vault has NEW password
     │   and new (CONFIG SET               │
     │   changes requirepass               │
     │   immediately)                      │
     │                                     │
  2. Vault gets new password              2. App pods restart
     ├── Old pods still work               ├── New pods get NEW password
     │   (connected with old)              │   from Vault
     │                                     ├── Try to connect to Redis
  3. K8s Secret updated                    │   with NEW password
     ├── For next Redis restart            ├── FAIL! Redis still has
     │                                     │   OLD password
  4. App pods restart                      │
     ├── Get NEW password from Vault       │   ┌───────────────────┐
     ├── Connect to Redis with NEW         │   │  OUTAGE!          │
     └── Everything works ✓                │   │  App can't reach  │
                                            │   │  Redis until you  │
  Zero downtime:                           │   │  fix it manually  │
  ─ Old connections keep working           │   └───────────────────┘
  ─ New connections use new password       │
  ─ No gap in service                      3. Now update Redis password
                                              ├── App reconnects ✓
                                              └── But you had downtime ✗
```

```bash
# 1. Generate a new password
NEW_PASSWORD=$(openssl rand -base64 24)

# 2. Update in Redis FIRST
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" \
  CONFIG SET requirepass "$NEW_PASSWORD"

# 3. Update the K8s Secret (so Redis uses it on next restart)
kubectl -n redis create secret generic redis \
  --from-literal=redis-password="$NEW_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

# 4. Update in Vault
vault kv put secret/staging/registry-api/redis \
  password="$NEW_PASSWORD"

# 5. Restart app pods to pick up new secret
kubectl -n registry-api rollout restart deployment/registry-api
```

---

## 11. Troubleshooting

```
  Quick diagnosis — connection issues

  App can't connect to Redis?
  │
  ├── DNS resolution? ──► Can the app resolve the hostname?
  │   kubectl -n registry-api exec <pod> -- nslookup redis-master.redis.svc
  │
  ├── Port reachable? ──► Is Redis port open?
  │   kubectl -n registry-api exec <pod> -- nc -zv redis-master.redis.svc 6379
  │
  ├── Credentials? ──► Is Vault injecting the right password?
  │   kubectl -n registry-api exec <pod> -- cat /vault/secrets/redis
  │
  ├── NetworkPolicy? ──► Is egress to redis namespace allowed?
  │   kubectl get networkpolicy -n registry-api -o yaml | grep redis
  │
  └── Redis healthy? ──► Is the pod running and ready?
      kubectl -n redis get pods
      kubectl -n redis exec redis-master-0 -c redis -- redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$REDIS_PASSWORD" PING
```

### App can't connect to Redis

Follow the decision tree above. Here are the copy-pasteable commands:

```bash
# Get the password first
export REDIS_PASSWORD=$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)

# Check Redis is running and ready
kubectl -n redis get pods
kubectl -n redis exec redis-master-0 -c redis -- redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$REDIS_PASSWORD" PING
# Should return: PONG

# Check DNS resolves from the app namespace
kubectl -n registry-api run dns-test --image=busybox --rm -it -- nslookup redis-master.redis.svc

# Check Redis port is reachable
kubectl -n registry-api run port-test --image=busybox --rm -it -- nc -zv redis-master.redis.svc 6379

# Check auth works
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$(kubectl -n redis get secret redis -o jsonpath='{.data.redis-password}' | base64 -d)" PING
```

### Redis running out of memory

```bash
# Check memory usage
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$REDIS_PASSWORD" INFO memory | grep -E "used_memory_human|maxmemory_human|mem_fragmentation"

# Check eviction stats
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$REDIS_PASSWORD" INFO stats | grep -E "evicted_keys|keyspace_hits|keyspace_misses"

# If memory is full and evictions are high:
# Option A: Increase maxmemory in redis-values.yaml (and container limits)
# Option B: Reduce TTLs on cache keys
# Option C: Review what's being cached — are keys too large?
```

### Low cache hit ratio

```bash
# Check hit/miss counts
kubectl -n redis exec redis-master-0 -c redis -- \
  redis-cli --tls --cacert /opt/bitnami/redis/certs/ca.crt -a "$REDIS_PASSWORD" INFO stats | grep keyspace

# keyspace_hits: 12345
# keyspace_misses: 45678
# Hit ratio = hits / (hits + misses)

# Common causes:
# 1. TTLs too short — keys expire before being re-read
# 2. Eviction too aggressive — maxmemory too low
# 3. Cache keys don't match query patterns
# 4. Projections invalidate cache too frequently
```

### Redis pod won't start after crash

```bash
# Check pod events
kubectl -n redis describe pod redis-master-0

# Check logs
kubectl -n redis logs redis-master-0 -c redis --previous

# If RDB file is corrupted:
# 1. Delete the PVC (loses all cached data — rebuilds from PostgreSQL)
# kubectl delete pvc redis-data-redis-master-0 -n redis
# 2. Delete the pod (StatefulSet recreates with fresh PVC)
# kubectl delete pod redis-master-0 -n redis
```

```
  Recovery from corrupted persistence files

  Symptom: redis-master-0 CrashLoopBackOff
  Logs show: "Bad file format reading the append only file"
  or: "Short read or OOM loading DB"

  ┌─────────────────────────────────────────────────────────────────┐
  │ Option A: Try to repair the AOF file (preserves most data)      │
  │                                                                 │
  │ kubectl -n redis exec redis-master-0 -c redis -- \              │
  │   redis-check-aof --fix /data/appendonly.aof                    │
  │                                                                 │
  │ If successful → restart the pod, data recovers                  │
  │ If not → Option B                                               │
  ├─────────────────────────────────────────────────────────────────┤
  │ Option B: Delete PVC and start fresh (loses cached data)        │
  │                                                                 │
  │ 1. Delete PVC                                                   │
  │    kubectl delete pvc redis-data-redis-master-0 -n redis        │
  │                                                                 │
  │ 2. Delete pod (StatefulSet recreates it with fresh PVC)         │
  │    kubectl delete pod redis-master-0 -n redis                   │
  │                                                                 │
  │ 3. What happens to the app?                                     │
  │    ─ All cache keys gone → 100% cache miss rate temporarily     │
  │    ─ Cache repopulates as queries come in (cache-aside)         │
  │    ─ All dedup keys gone → some messages may reprocess          │
  │    ─ Idempotent PG writes prevent actual duplicates             │
  │    ─ Saga state lost → in-flight sagas may need manual check    │
  │    ─ System self-heals within minutes                           │
  │                                                                 │
  │ This is why Redis is a CACHE, not a source of truth.            │
  │ Losing everything is annoying but not catastrophic.             │
  └─────────────────────────────────────────────────────────────────┘
```

---

## 12. Tear down (if needed)

```
  Tear-down order

  Step 1: Disconnect app
  ┌───────────────┐
  │ registry-api  │  Remove REDIS_* from ConfigMap, Vault annotations,
  └───────┬───────┘  :redis from config.edn, Carmine from deps.edn.
          │          Remove dedup/cache code. Redeploy.
          ▼
  Step 2: Remove NetworkPolicies (before namespace deletion)
  ┌───────────────┐
  │ k8s policies  │  Delete redis-ingress, remove egress rule
  └───────┬───────┘
          ▼
  Step 3: Uninstall Redis + namespace
  ┌───────────────┐
  │ helm, PVC, ns │  Remove everything
  └───────┬───────┘
          ▼
  Step 4: Clean up Vault + OTel
  ┌───────────────┐
  │ vault, otel   │  Remove secret, revert policy, revert OTel config
  └───────────────┘
```

```bash
# 1. Remove Redis config from app and redeploy
#    - Remove REDIS_* from helm/registry-api/templates/configmap.yaml
#    - Remove Vault annotations for redis from deployment.yaml
#    - Remove :redis section from resources/config.edn
#    - Remove com.taoensso/carmine from deps.edn
#    - Remove redis egress rule from registry-api-egress NetworkPolicy
#    - Remove dedup and cache code from app
#    Redeploy: helm upgrade --install registry-api ...

# 2. Delete NetworkPolicy (while namespace still exists)
kubectl delete networkpolicy redis-ingress -n redis

# 3. Uninstall Redis
helm uninstall redis --namespace redis

# 4. Delete PVCs (cached data is permanently lost — rebuildable)
kubectl delete pvc --all -n redis

# 5. Delete the namespace
kubectl delete namespace redis

# 6. Remove Vault secret and revert policy
vault kv delete secret/staging/registry-api/redis
# Re-write the Vault policy WITHOUT the redis path:
vault policy write registry-api - <<'EOF'
path "secret/data/staging/registry-api/db" {
  capabilities = ["read"]
}
path "secret/data/staging/registry-api/rabbitmq" {
  capabilities = ["read"]
}
EOF

# 7. Revert OTel Collector config (if Phase 5)
#    Remove the prometheus/redis receiver from otel-collector-values.yaml
#    Remove it from the metrics pipeline receivers list
#    helm upgrade otel-collector ... -f otel-collector-values.yaml
```

---

## 13. Checklist — Phase 7 complete

```
Infrastructure:
  [ ] redis namespace created
  [ ] Redis pod running (redis-master-0, 2/2 containers)
  [ ] Redis port accessible from app namespace
  [ ] redis-cli PING returns PONG

Credentials:
  [ ] Redis password stored in Vault
  [ ] Vault policy updated to include redis path
  [ ] Vault Agent annotation added to deployment template

App configuration:
  [ ] config.edn has :redis section
  [ ] ConfigMap has REDIS_HOST, REDIS_PORT, REDIS_DB
  [ ] values-staging.yaml has Redis connection config
  [ ] Carmine dependency added to deps.edn
  [ ] App connects to Redis on startup

Caching and deduplication:
  [ ] Dedup guard implemented in RabbitMQ consumers
  [ ] Read model cache-aside pattern implemented
  [ ] Cache invalidation in projection consumers
  [ ] Saga correlation state (if using sagas)

Security:
  [ ] TLS enabled (tls.enabled: true, autoGenerated: true)
  [ ] App connects with :ssl-fn :default (Carmine) over TLS
  [ ] redis-cli requires --tls flag to connect
  [ ] CA cert mounted in app pod (/etc/ssl/redis/redis-ca.crt)
  [ ] NetworkPolicy restricts Redis ingress to registry-api namespace
  [ ] registry-api egress policy allows traffic to redis namespace
  [ ] Metrics scraping allowed from observability namespace
  [ ] redis-exporter configured with skip-tls-verification
  [ ] FLUSHDB and FLUSHALL disabled
  [ ] Redis runs as non-root (Bitnami default)

Monitoring (if Phase 5 completed):
  [ ] Metrics exporter sidecar running (:9121)
  [ ] otel-collector-values.yaml updated with prometheus/redis receiver
  [ ] OTel Collector helm-upgraded with new config
  [ ] Grafana dashboard with hit ratio, memory, evictions
  [ ] Alerts configured: down, memory, eviction rate, hit ratio, no clients
```

**You now have caching and idempotency.** Your app can cache read model projections for sub-millisecond queries, deduplicate RabbitMQ messages to prevent double-processing, and store saga correlation state for multi-step workflows — all through Redis with Vault-managed credentials and LRU eviction as a safety net.

---

## Files created in this phase

```
New files:
  redis-values.yaml                          # Redis Helm install config (project root,
                                             #   same level as rabbitmq-values.yaml from Phase 6)

Modified:
  helm/registry-api/values-staging.yaml      # redisHost, redisPort, redisDb added
  helm/registry-api/templates/configmap.yaml  # REDIS_HOST, REDIS_PORT, REDIS_DB
  helm/registry-api/templates/deployment.yaml # Vault annotation for redis secret
                                             # + redis-ca volume mount for TLS
  resources/config.edn                       # :redis section (with :ssl true)
  deps.edn                                  # com.taoensso/carmine dependency
  k8s/network-policies.yaml                 # redis-ingress NetworkPolicy (new)
                                             # registry-api-egress rule update (modified)
  otel-collector-values.yaml                 # prometheus/redis scrape config (if Phase 5)
```

**This completes the deployment series.** Phases 1-7 give you a production-grade Kubernetes setup: infrastructure (Phase 1), app deployment (Phase 2), CI/CD (Phase 3), secrets (Phase 4), observability (Phase 5), async messaging (Phase 6), and caching/idempotency (Phase 7). Return to [K8s.md](K8s.md) for the full plan overview.
