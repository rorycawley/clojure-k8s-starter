# Phase 6: RabbitMQ — Async Messaging for Event Sourcing

Add RabbitMQ as the message broker for async projections, sagas, and integration events between modules.

**Prerequisite:** Phases 1-4 complete — app running on GKE with Vault-managed secrets. Phase 5 (observability) is recommended but not required.

**End state:** RabbitMQ running in its own namespace, credentials stored in Vault, app connected and publishing/consuming messages. Exchanges, queues, and bindings set up for the event sourcing patterns (outbox, saga, integration events).

```
  Phase 6 — End State

  ┌─ namespace: registry-api ─────────────────────────────────────────┐
  │                                                                    │
  │  registry-api pod                                                  │
  │  ┌────────────────────────────────────────────────────────────┐    │
  │  │  app container                                             │    │
  │  │  RABBITMQ_HOST=rabbitmq.rabbitmq.svc                      │    │
  │  │  RABBITMQ_PORT=5671  (AMQPS — TLS)                        │    │
  │  │  RABBITMQ_USER + RABBITMQ_PASSWORD from Vault             │    │
  │  │                                                            │    │
  │  │  Publishes:                                                │    │
  │  │  ├── outbox events → es.outbox exchange                   │    │
  │  │  ├── integration events → bank.events exchange            │    │
  │  │  └── saga commands → es.saga exchange                     │    │
  │  │                                                            │    │
  │  │  Consumes:                                                 │    │
  │  │  ├── async projection updates ← es.projections queue      │    │
  │  │  ├── saga step responses ← es.saga.responses queue        │    │
  │  │  └── integration events ← notification.events queue       │    │
  │  └────────────────────────────────────────────────────────────┘    │
  └────────────────────┬───────────────────────────────────────────────┘
                       │ AMQPS :5671 (TLS)
                       ▼
  ┌─ namespace: rabbitmq ───────────────────────────────────────────┐
  │                                                                  │
  │  ┌──────────────────────────────────────────────────────────┐   │
  │  │  RabbitMQ (StatefulSet, bitnami/rabbitmq)                │   │
  │  │  AMQPS :5671 (TLS) | Management UI :15672 | Metrics :9419│   │
  │  │  PVC: 8Gi (message persistence)                          │   │
  │  │                                                           │   │
  │  │  Exchanges:                                               │   │
  │  │  ├── es.outbox (topic) — outbox pattern                  │   │
  │  │  ├── es.saga (direct) — saga orchestration               │   │
  │  │  ├── bank.events (topic) — cross-module integration      │   │
  │  │  └── es.dlx (fanout) — dead letter exchange              │   │
  │  └──────────────────────────────────────────────────────────┘   │
  └──────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents

1. [What you're building](#1-what-youre-building)
2. [Step 1 — Install RabbitMQ via Helm](#2-step-1--install-rabbitmq-via-helm)
3. [Step 2 — Store credentials in Vault](#3-step-2--store-credentials-in-vault)
4. [Step 3 — Set up exchanges, queues, and bindings](#4-step-3--set-up-exchanges-queues-and-bindings)
5. [Step 4 — Configure your app to connect](#5-step-4--configure-your-app-to-connect)
6. [Step 5 — Deploy and verify](#6-step-5--deploy-and-verify)
7. [Step 6 — Security hardening](#7-step-6--security-hardening)
8. [Step 7 — Monitoring with Grafana](#8-step-7--monitoring-with-grafana)
9. [How it works at runtime](#9-how-it-works-at-runtime)
10. [Day-2 operations](#10-day-2-operations)
11. [Troubleshooting](#11-troubleshooting)
12. [Tear down (if needed)](#12-tear-down)
13. [Checklist — Phase 6 complete](#13-checklist--phase-6-complete)

---

## 1. What you're building

#### AMQP core concepts — how RabbitMQ works

Before diving in, here are the four building blocks you'll work with:

```
  AMQP building blocks

  Producer                                                   Consumer
  (your app)                                                 (your app)
      │                                                          ▲
      │ publish                                                  │ consume
      │ (routing key: "account.opened")                          │
      ▼                                                          │
  ┌──────────────────────────────────────────────────────────────────────────┐
  │                           RabbitMQ Broker                                │
  │                                                                          │
  │  ┌──────────────┐     binding                ┌────────────────────┐     │
  │  │  Exchange     │     (routing rule)         │  Queue             │     │
  │  │              │─────────────────────────────►│                    │     │
  │  │  Receives    │   "account.*" matches       │  Stores messages   │     │
  │  │  messages,   │   "account.opened" ✓        │  until consumed.   │     │
  │  │  routes them │   "deposit.done"   ✗        │  FIFO order.       │     │
  │  │  based on    │                             │  Durable = survives│     │
  │  │  type +      │                             │  broker restart.   │     │
  │  │  routing key.│                             │                    │     │
  │  └──────────────┘                             └────────────────────┘     │
  │                                                                          │
  │  Key insight: producers never send directly to queues.                   │
  │  They send to an exchange, which routes via bindings.                    │
  │  This decoupling is what makes the system flexible.                      │
  └──────────────────────────────────────────────────────────────────────────┘

  Exchange types determine HOW routing works:
  ┌─────────┬───────────────────────────────────────────────────────────────┐
  │ topic   │ Pattern matching on routing key: "account.*" or "transfer.#" │
  │ direct  │ Exact match only: routing key must equal binding key exactly  │
  │ fanout  │ No routing — broadcast to every bound queue                  │
  └─────────┴───────────────────────────────────────────────────────────────┘
```

> **If you've used Kafka:** RabbitMQ exchanges are like topics, queues are like consumer group partitions, and bindings are like subscription filters. The big difference: RabbitMQ pushes to consumers (vs Kafka's pull model) and deletes messages after acknowledgement (vs Kafka's append-only log).

#### Why your event sourcing app needs a message broker

```
  Without a broker — tight coupling, no async

  ┌──────────────┐   direct call   ┌──────────────────┐
  │ Bank module  │────────────────►│ Notification     │
  │ (deposit)    │                  │ module           │
  └──────────────┘                  └──────────────────┘
  Bank must know about Notification.
  If Notification is down, deposit fails.
  Adding a new consumer means changing Bank.

  With RabbitMQ — loose coupling, async, resilient

  ┌──────────────┐   publish    ┌────────────┐   consume   ┌───────────────┐
  │ Bank module  │─────────────►│  RabbitMQ  │◄────────────│ Notification  │
  │ (deposit)    │  "deposit    │            │  "deposit   │ module        │
  └──────────────┘   completed" │            │  completed" └───────────────┘
                                │            │
                                │            │◄──────────── (future modules)
                                └────────────┘
  Bank just publishes. Doesn't know who consumes.
  If Notification is down, messages queue up.
  Adding a consumer = zero changes to Bank.
```

#### The three messaging patterns in this app

| Pattern | What it does | Exchange | Example |
|---------|-------------|----------|---------|
| **Outbox** | Reliably publishes domain events after DB commit | `es.outbox` (topic) | Account opened → publish `account.opened` |
| **Saga** | Orchestrates multi-step workflows across aggregates | `es.saga` (direct) | Transfer: debit source → credit target → complete |
| **Integration events** | Cross-module communication (bank → notification) | `bank.events` (topic) | Large deposit → notify compliance team |

```
  How the three patterns flow through RabbitMQ

  ┌─ Bank module ───────────────────────────────────────────────────┐
  │                                                                  │
  │  Command → Decider → Events → Event Store                      │
  │                                    │                             │
  │                    ┌───────────────┼─────────────────┐          │
  │                    ▼               ▼                 ▼          │
  │              ┌──────────┐   ┌──────────┐   ┌──────────────┐    │
  │              │ Outbox   │   │ Saga     │   │ Integration  │    │
  │              │ Publisher│   │ Publisher│   │ Publisher    │    │
  │              └────┬─────┘   └────┬─────┘   └──────┬───────┘    │
  └───────────────────┼──────────────┼────────────────┼────────────┘
                      │              │                │
                      ▼              ▼                ▼
               ┌──────────┐  ┌──────────┐  ┌──────────────────┐
               │es.outbox │  │ es.saga  │  │ bank.events      │
               │(topic)   │  │(direct)  │  │ (topic)          │
               └────┬─────┘  └────┬─────┘  └──────┬───────────┘
                    │              │                │
            ┌───────┴───┐    ┌────┴────┐     ┌─────┴──────┐
            ▼           ▼    ▼         ▼     ▼            ▼
      ┌──────────┐ ┌──────┐ ┌───────┐ ┌──┐ ┌───────────┐ ┌──────┐
      │async     │ │search│ │saga   │ │..│ │notification│ │audit │
      │projection│ │index │ │handler│ │  │ │module      │ │log   │
      └──────────┘ └──────┘ └───────┘ └──┘ └───────────┘ └──────┘
```

#### Deep dive — the outbox pattern

The outbox solves a fundamental problem: how do you reliably publish an event to RabbitMQ *and* commit to the database, when either one could fail independently?

```
  The problem: dual write

  ┌──────────────┐   1. write event    ┌──────────────┐
  │ App          │──────────────────────►│ Database     │  ✓ committed
  │              │                       └──────────────┘
  │              │   2. publish event   ┌──────────────┐
  │              │──────────────────────►│ RabbitMQ     │  ✗ network error!
  └──────────────┘                       └──────────────┘

  Event is in the DB but NOT in RabbitMQ.
  Consumers never see it. Data is inconsistent.

  The solution: transactional outbox

  ┌──────────────┐   SINGLE DB transaction:     ┌──────────────────┐
  │ App          │──────────────────────────────►│ Database         │
  │              │   1. write event to           │ ┌──────────────┐ │
  └──────────────┘      event_store table        │ │ event_store  │ │
                    2. write event to            │ ├──────────────┤ │
                        outbox table             │ │ outbox       │ │
                    (atomic — both or neither)   │ └──────────────┘ │
                                                 └────────┬─────────┘
                                                          │
  ┌──────────────┐   3. poll outbox               ┌──────┴─────────┐
  │ RabbitMQ     │◄───────────────────────────────│ Outbox         │
  │              │   4. publish to es.outbox      │ Publisher      │
  └──────────────┘   5. mark row as published     │ (background)   │
                                                  └────────────────┘

  Now the event is guaranteed to reach RabbitMQ — the publisher
  keeps retrying until the message is confirmed by the broker.
  At-least-once delivery (consumers must be idempotent).
```

#### Deep dive — the saga pattern

A saga coordinates a multi-step workflow across aggregates. Unlike a database transaction that locks rows, a saga uses compensating actions to undo partial work if a step fails.

```
  Transfer saga — debit source, credit target

  ┌───────────────────────────────────────────────────────────────────────┐
  │  Saga Orchestrator                                                    │
  │                                                                       │
  │  State machine:  STARTED → DEBITED → COMPLETED                       │
  │                            └─ FAILED (compensate: refund source)      │
  └──────────┬─────────────────────────────┬──────────────────────────────┘
             │                             │
             │ Step 1: debit source        │ Step 2: credit target
             │ routing key:                │ routing key:
             │ "transfer.debit"            │ "transfer.credit"
             ▼                             ▼
  ┌────────────────────┐       ┌────────────────────┐
  │ es.saga exchange   │       │ es.saga exchange    │
  │ (direct)           │       │ (direct)            │
  └────────┬───────────┘       └────────┬────────────┘
           │ exact match                │ exact match
           ▼                            ▼
  ┌──────────────────┐       ┌──────────────────────┐
  │ es.saga.transfer │       │ es.saga.transfer     │
  │ .debit queue     │       │ .credit queue        │
  └────────┬─────────┘       └────────┬─────────────┘
           │                          │
           ▼                          ▼
  ┌──────────────────┐       ┌──────────────────────┐
  │ Debit handler    │       │ Credit handler       │
  │ Account A: -$100 │       │ Account B: +$100     │
  │ Reply: success   │       │ Reply: success       │
  └──────────────────┘       └──────────────────────┘

  Why direct exchange? Each saga step has exactly one handler.
  No pattern matching needed — "transfer.debit" goes to exactly
  one queue. This is simpler and faster than topic routing.

  Why not a DB transaction? Accounts A and B might be different
  aggregates (or even different services). A saga lets each
  step succeed/fail independently and compensate if needed.
```

#### Deep dive — integration events

Integration events are how modules communicate without depending on each other. The bank module doesn't import the notification module — it publishes events, and anyone interested subscribes.

```
  Cross-module communication via integration events

  ┌─ Bank module ─────────────────────────────────────────────────────┐
  │                                                                    │
  │  deposit handler                                                   │
  │  ├── write DepositCompleted to event store (domain event)         │
  │  └── publish to bank.events exchange (integration event)          │
  │       routing key: "deposit.completed"                             │
  │       payload: {account_id, amount, timestamp}                    │
  │                                                                    │
  │  The bank module's job is done. It doesn't know or care           │
  │  who subscribes to this event.                                     │
  └────────────────────────────────────┬───────────────────────────────┘
                                       │
                                       ▼
                            ┌──────────────────┐
                            │ bank.events      │
                            │ (topic exchange) │
                            └──┬──────────┬────┘
                               │          │
               deposit.*  ─────┘          └───── #
                               │          │
                               ▼          ▼
               ┌───────────────────┐  ┌───────────────┐
               │ notification      │  │ audit         │
               │ .events queue     │  │ .events queue │
               └────────┬──────────┘  └───────┬───────┘
                        │                     │
                        ▼                     ▼
  ┌─ Notification module ───────┐  ┌─ Audit module ───────────┐
  │                              │  │                           │
  │  "Large deposit? Send alert" │  │  "Log all bank events    │
  │  "New account? Send welcome" │  │   for compliance"        │
  │                              │  │                           │
  │  This module has its OWN     │  │  Subscribes to # (all)   │
  │  bounded context. It only    │  │  so it sees everything   │
  │  sees the integration event  │  │  the bank publishes.     │
  │  contract, not bank internals│  │                           │
  └──────────────────────────────┘  └───────────────────────────┘

  Key difference from outbox events:
  ─ Outbox events = same module, internal (projection updates)
  ─ Integration events = cross-module, external contract
  ─ Different exchange, different consumers, different lifecycle
```

#### Exchange types — which one and why

| Type | Routing | Use case in this app |
|------|---------|---------------------|
| **topic** | Pattern matching (`account.*`, `transfer.#`) | Outbox + integration events — consumers subscribe to patterns |
| **direct** | Exact routing key match | Saga — route commands to specific saga step handlers |
| **fanout** | Broadcast to all bound queues | Dead letter exchange — all failed messages go to one place |

#### Topic exchange routing — `*` vs `#`

This is the most important routing concept to understand, because the outbox and integration event exchanges both use topic routing:

```
  Routing key pattern matching

  Routing keys are dot-separated words:  "account.opened"
                                          "deposit.completed"
                                          "transfer.saga.debit.failed"

  Two wildcards:
  ┌──────┬──────────────────────────────────────────────────────────────┐
  │  *   │ Matches exactly ONE word                                     │
  │  #   │ Matches ZERO OR MORE words                                   │
  └──────┴──────────────────────────────────────────────────────────────┘

  Examples with routing key "deposit.completed":

  binding pattern        match?   why
  ─────────────────      ──────   ───
  deposit.completed      ✓        exact match
  deposit.*              ✓        * matches "completed" (one word)
  *.completed            ✓        * matches "deposit" (one word)
  account.*              ✗        "account" ≠ "deposit"
  #                      ✓        # matches everything
  deposit.#              ✓        # matches "completed"
  #.completed            ✓        # matches "deposit"

  Examples with routing key "transfer.saga.debit.failed":

  transfer.*             ✗        * matches ONE word, but 3 remain
  transfer.#             ✓        # matches "saga.debit.failed"
  transfer.saga.*        ✗        * matches ONE word, but 2 remain
  transfer.saga.#        ✓        # matches "debit.failed"
  #.failed               ✓        # matches "transfer.saga.debit"
```

> **Why this matters for your app:** The `es.projections.account-balance` queue binds to `es.outbox` with pattern `account.*`. This means it receives `account.opened`, `account.closed`, `account.suspended` — but not `deposit.completed` or `transfer.initiated`. Each projection only gets the events it cares about, without the publisher needing to know which projections exist.

---

## 2. Step 1 — Install RabbitMQ via Helm

### Create the namespace

```bash
kubectl create namespace rabbitmq
```

### Add the Bitnami Helm repo

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
```

#### Why StatefulSet, not Deployment?

```
  Deployment (what your app uses)         StatefulSet (what RabbitMQ uses)
  ─────────────────────────────           ──────────────────────────────────

  Pods are interchangeable:               Pods have stable identities:
  ┌────────────┐ ┌────────────┐          ┌────────────┐ ┌────────────┐
  │ api-7f8d2  │ │ api-3k9x1  │          │ rabbitmq-0 │ │ rabbitmq-1 │
  │ (random)   │ │ (random)   │          │ (always 0) │ │ (always 1) │
  └────────────┘ └────────────┘          └─────┬──────┘ └─────┬──────┘
        │              │                       │              │
        ▼              ▼                       ▼              ▼
  ┌──────────────────────────┐          ┌────────────┐ ┌────────────┐
  │  shared ephemeral storage │          │  PVC-0     │ │  PVC-1     │
  │  (lost on restart)        │          │  (8Gi)     │ │  (8Gi)     │
  └──────────────────────────┘          │  persists  │ │  persists  │
                                         └────────────┘ └────────────┘

  Pods come up in any order.              Pods start in order: 0, then 1.
  No stable DNS per pod.                  Each pod has stable DNS:
                                          rabbitmq-0.rabbitmq-headless.rabbitmq.svc

  RabbitMQ needs StatefulSet because:
  1. Messages are stored on disk — PVCs must survive pod restarts
  2. Clustering requires stable hostnames — nodes must find each other
  3. The Erlang cookie (shared secret) must persist across restarts
```

### Create `rabbitmq-values.yaml`

```
  What this creates in the cluster

  ┌─ namespace: rabbitmq ──────────────────────────────────────────┐
  │                                                                 │
  │  StatefulSet: rabbitmq                                          │
  │  ┌───────────────────────────────────────────────────────┐     │
  │  │  rabbitmq-0  (single replica for staging)             │     │
  │  │                                                        │     │
  │  │  Ports:                                                │     │
  │  │  ├── 5671  AMQPS (TLS-encrypted app connections)     │     │
  │  │  ├── 15672 Management UI (admin dashboard)            │     │
  │  │  └── 9419  metrics (Prometheus format, scraped by OTel) │     │
  │  │                                                        │     │
  │  │  PVC: 8Gi  (message + index persistence)              │     │
  │  │  Plugins: management, peer_discovery_k8s,             │     │
  │  │           consistent_hash_exchange                     │     │
  │  └───────────────────────────────────────────────────────┘     │
  │                                                                 │
  │  Services:                                                      │
  │  ├── rabbitmq           ClusterIP  5671, 15672, 9419           │
  │  └── rabbitmq-headless  Headless   (for StatefulSet DNS)       │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘
```

```yaml
# RabbitMQ — single replica for staging
replicaCount: 1

auth:
  username: registry-api
  # Password is auto-generated on first install and stored in a K8s Secret.
  # We'll move it to Vault in Step 2.
  password: ""
  erlangCookie: ""
  # TLS — encrypt AMQP connections between app and RabbitMQ
  tls:
    enabled: true
    autoGenerated: true            # Bitnami generates self-signed CA + server cert
    failIfNoPeerCert: false        # server-only TLS (no client certs required)
    sslOptionsVerify: verify_none  # clients don't present certs

# Plugins beyond the defaults (management + peer_discovery_k8s)
extraPlugins: "rabbitmq_consistent_hash_exchange"

# Resources — meets GKE Autopilot minimum (non-bursting)
resources:
  requests:
    cpu: 250m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 1Gi

# Persistence — messages survive pod restarts
persistence:
  enabled: true
  size: 8Gi

# Memory high watermark — RabbitMQ will stop accepting publishes
# when memory usage exceeds this percentage of the limit
memoryHighWatermark:
  enabled: true
  type: "relative"
  value: 0.4                  # 40% of 1Gi limit = ~410Mi

# Service type — internal access only
service:
  type: ClusterIP

# Metrics for Grafana (Phase 5)
metrics:
  enabled: true
  serviceMonitor:
    default:
      enabled: false          # not needed — Phase 5 uses OTel Collector, not Prometheus Operator
```

> **GKE Autopilot note:** Resource requests are set to 250m CPU / 512Mi memory — the Autopilot minimum for non-bursting clusters. Same sizing rationale as Vault in Phase 4 and the observability pods in Phase 5. If your cluster has bursting enabled, you could lower requests to 50m/52MiB, but RabbitMQ benefits from having dedicated resources for message throughput.

#### How TLS encrypts the AMQP connection

```
  TLS encryption between app and RabbitMQ

  Without TLS (port 5672):

  registry-api pod              network              rabbitmq-0
  ┌──────────────┐           (plain text)           ┌──────────────┐
  │ Langohr      │──── AMQP credentials + ────────►│ RabbitMQ     │
  │              │     message payloads              │              │
  └──────────────┘     visible to anyone             └──────────────┘
                       sniffing the network

  With TLS (port 5671):

  registry-api pod              network              rabbitmq-0
  ┌──────────────┐           (encrypted)            ┌──────────────┐
  │ Langohr      │──── TLS handshake ──────────────►│ RabbitMQ     │
  │ :ssl true    │◄─── server cert (auto-generated)─│ :5671        │
  │ :port 5671   │──── encrypted AMQP traffic ─────►│              │
  └──────────────┘     credentials + payloads        └──────────────┘
                       unreadable without keys

  What `autoGenerated: true` creates:

  ┌─ K8s Secret: rabbitmq-certs ────────────────────────────────────┐
  │                                                                  │
  │  ca.crt       Self-signed Certificate Authority                 │
  │  tls.crt      Server certificate (signed by CA)                 │
  │  tls.key      Server private key                                │
  │                                                                  │
  │  Created by a Helm Job on install.                              │
  │  Mounted into the RabbitMQ pod at /opt/bitnami/rabbitmq/certs/  │
  └──────────────────────────────────────────────────────────────────┘

  The app pod needs the CA cert to trust the server.
  We mount it via a volume (see "Mount the CA certificate" below).

  failIfNoPeerCert: false  →  server-only TLS (not mutual TLS)
  ─ RabbitMQ presents its cert to the client ✓
  ─ Client does NOT present a cert back
  ─ This is the same model as HTTPS websites
  ─ For mutual TLS (mTLS), set failIfNoPeerCert: true
    and provision client certs (more complex, rarely needed in-cluster)
```

> **Why not just rely on GKE's encryption?** GKE encrypts VM-to-VM traffic at the infrastructure level, but pods on the same node communicate over the local network without that layer. TLS at the application level guarantees encryption regardless of pod placement, and is required by compliance frameworks (PCI-DSS, SOC2, HIPAA).

#### Mount the CA certificate in the app pod

The app needs to trust the auto-generated CA. Add a volume mount to the registry-api deployment:

```yaml
# In helm/registry-api/templates/deployment.yaml:
spec:
  template:
    spec:
      volumes:
        # ... existing volumes ...
        - name: rabbitmq-ca
          secret:
            secretName: rabbitmq-ca     # created by bitnami/rabbitmq TLS auto-generation
            items:
              - key: tls.crt
                path: rabbitmq-ca.crt
      containers:
        - name: registry-api
          volumeMounts:
            # ... existing mounts ...
            - name: rabbitmq-ca
              mountPath: /etc/ssl/rabbitmq
              readOnly: true
```

Then create a JKS truststore at container startup. Add to your Dockerfile or entrypoint script:

```bash
# Convert the CA PEM to a JKS truststore that Java/Langohr can use
keytool -import -alias rabbitmq-ca \
  -file /etc/ssl/rabbitmq/rabbitmq-ca.crt \
  -keystore /tmp/rabbitmq-truststore.jks \
  -storepass changeit -noprompt
```

Or set the JVM system property to trust the CA:

```bash
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/tmp/rabbitmq-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
```

> **Simpler alternative:** If you don't want to manage trust stores, you can create a custom `SSLContext` in Clojure that trusts the mounted CA cert. See the Langohr connection config below.

#### How the memory watermark protects your cluster

```
  Memory high watermark — RabbitMQ's built-in backpressure

  Container memory limit: 1Gi
  ┌─────────────────────────────────────────────────────────────────┐
  │                                                                 │
  │  0 Mi          200 Mi        410 Mi            800 Mi    1 Gi  │
  │  ├──────────────┼─────────────┼──────────────────┼────────┤    │
  │  │              │             │                  │        │    │
  │  │   normal     │   normal    │▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓│ OOM    │    │
  │  │   operation  │   operation │   ALARM ZONE    │ killed │    │
  │  │              │             │                  │        │    │
  │  │              │         watermark = 40%        │        │    │
  │  │              │         of 1Gi = 410Mi         │        │    │
  │  │              │             │                  │        │    │
  │  │              │             │  publishers      │        │    │
  │  │              │             │  BLOCKED         │        │    │
  │  │              │             │  (connections    │        │    │
  │  │              │             │   are paused)    │        │    │
  │  └──────────────┴─────────────┴──────────────────┴────────┘    │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘

  When memory hits 410Mi:
  1. RabbitMQ sets a memory alarm
  2. All publishers are blocked (connections pause, not drop)
  3. Consumers continue draining queues (this frees memory)
  4. Once memory drops below 410Mi, publishers resume

  Why 40% and not higher?
  ─ Erlang VM needs headroom for GC, message routing, management
  ─ A sudden burst of large messages can spike memory fast
  ─ 40% gives ~600Mi of breathing room before OOM killer
  ─ For production with 2Gi limit, watermark = ~820Mi
```

> **Why `rabbitmq_consistent_hash_exchange`?** This plugin lets you create exchanges that distribute messages across queues by hashing a routing key. Useful for parallel consumer scaling — e.g., spreading account events across multiple projection workers by account ID, ensuring all events for the same account go to the same worker (ordering guarantee).

```bash
helm install rabbitmq bitnami/rabbitmq \
  --namespace rabbitmq \
  -f rabbitmq-values.yaml
```

### Verify RabbitMQ is running

```bash
kubectl -n rabbitmq get pods
# Expected:
# NAME         READY   STATUS    RESTARTS   AGE
# rabbitmq-0   1/1     Running   0          2m

kubectl -n rabbitmq get svc
# Expected:
# rabbitmq           ClusterIP   ...   5671/TCP,15672/TCP,4369/TCP,25672/TCP,9419/TCP
# rabbitmq-headless  ClusterIP   None  ...
```

### Retrieve the auto-generated password

```bash
# macOS:
kubectl -n rabbitmq get secret rabbitmq \
  -o jsonpath="{.data.rabbitmq-password}" | base64 -D; echo

# Linux:
kubectl -n rabbitmq get secret rabbitmq \
  -o jsonpath="{.data.rabbitmq-password}" | base64 -d; echo
```

Save this password — you'll store it in Vault in Step 2.

### Access the Management UI (optional, for verification)

```bash
kubectl -n rabbitmq port-forward svc/rabbitmq 15672:15672 &
# Open http://localhost:15672
# Login: registry-api / <password from above>
```

```
  RabbitMQ Management UI — what you'll see

  ┌──────────────────────────────────────────────────────────────┐
  │  RabbitMQ Management                                         │
  │                                                               │
  │  Overview    Connections    Channels    Exchanges    Queues   │
  │  ─────────────────────────────────────────────────────────── │
  │                                                               │
  │  Nodes: rabbit@rabbitmq-0.rabbitmq-headless.rabbitmq         │
  │  Connections: 0 (no app connected yet)                       │
  │  Channels: 0                                                  │
  │  Queues: 0 (we'll create them in Step 3)                     │
  │  Messages: 0                                                  │
  │                                                               │
  │  Memory: 142 MiB / 410 MiB (watermark)                      │
  │  Disk: 2.1 GiB free                                          │
  └──────────────────────────────────────────────────────────────┘
```

---

## 3. Step 2 — Store credentials in Vault

Store the RabbitMQ password in Vault so the app reads it via Vault Agent Injector (same pattern as the DB credentials from Phase 4).

### Write the secret to Vault

```bash
# Port-forward to Vault
kubectl -n vault port-forward svc/vault 8200:8200 &

# Login with your admin token (from Phase 4)
export VAULT_ADDR="https://127.0.0.1:8200"
export VAULT_SKIP_VERIFY=true
vault login <your-admin-token>

# Write RabbitMQ credentials
vault kv put secret/staging/registry-api/rabbitmq \
  username="registry-api" \
  password="<password-from-step-1>"
```

### Update the Vault policy

Add RabbitMQ to the existing `registry-api` policy so the app can read the secret:

```bash
vault policy write registry-api - <<'EOF'
# Existing DB credentials
path "secret/data/staging/registry-api/db" {
  capabilities = ["read"]
}

# RabbitMQ credentials (new)
path "secret/data/staging/registry-api/rabbitmq" {
  capabilities = ["read"]
}
EOF
```

```
  Vault KV v2 path structure — why the /data/ prefix

  What you write:                     What Vault stores internally:
  ────────────────                    ────────────────────────────────

  vault kv put                        secret/data/staging/registry-api/rabbitmq
    secret/staging/registry-api/         │      │
      rabbitmq                           │      └── /data/ inserted by KV v2
    username=registry-api                │          (versioning metadata lives
    password=s3cr3t                      │           at /metadata/ instead)
                                         │
                                         ▼
  What the policy needs:              What the Vault Agent template reads:
  ──────────────────────              ──────────────────────────────────────

  path "secret/data/staging/          {{- with secret "secret/data/staging/
    registry-api/rabbitmq"              registry-api/rabbitmq" -}}
  { capabilities = ["read"] }         {{ .Data.data.username }}
                                              │    │
                                              │    └── actual key-value pairs
                                              └── KV v2 wraps in .Data.data
                                                  (v1 would be just .Data)
```

> **The `/data/` trap:** In Vault KV v2, the CLI `kv put` command hides the `/data/` segment — you write to `secret/staging/...` but the actual API path is `secret/data/staging/...`. Policies and Agent templates use the real API path. This catches everyone at least once (Phase 4 warned about this too).

### Update the deployment annotations

Add a Vault Agent Injector annotation to the Helm deployment template to inject the RabbitMQ secret. In `helm/registry-api/templates/deployment.yaml`, add to the pod annotations:

```yaml
vault.hashicorp.com/agent-inject-secret-rabbitmq: "secret/data/staging/registry-api/rabbitmq"
vault.hashicorp.com/agent-inject-template-rabbitmq: |
  {{- with secret "secret/data/staging/registry-api/rabbitmq" -}}
  export RABBITMQ_USER="{{ .Data.data.username }}"
  export RABBITMQ_PASSWORD="{{ .Data.data.password }}"
  {{- end }}
```

```
  How the secret flows from Vault to the app

  ┌────────────┐     1. Pod starts       ┌──────────────────┐
  │ Vault      │◄────────────────────────│ Vault Agent      │
  │ (vault ns) │     2. Agent fetches    │ (init container) │
  └────────────┘        secret           └────────┬─────────┘
                                                   │
                                          3. Writes to
                                          /vault/secrets/rabbitmq
                                                   │
                                                   ▼
                                         ┌──────────────────┐
                                         │ App container    │
                                         │ reads env vars:  │
                                         │ RABBITMQ_USER    │
                                         │ RABBITMQ_PASSWORD│
                                         └──────────────────┘
```

---

## 4. Step 3 — Set up exchanges, queues, and bindings

RabbitMQ starts empty — you need to declare the topology (exchanges, queues, bindings) that your event sourcing patterns require. There are two approaches:

1. **App declares on startup** (recommended) — your Clojure code declares exchanges/queues when it connects. Idempotent, version-controlled with your app.
2. **Load definitions** — pre-load a JSON definitions file into RabbitMQ. Useful for infrastructure-as-code but harder to keep in sync with app changes.

We'll use approach 1 (app declares), but here's the topology your app should create:

### The complete message topology

```
  Exchange and queue topology

  ┌─────────────────────────────────────────────────────────────────────┐
  │                        Exchanges                                     │
  │                                                                      │
  │  es.outbox (topic)          es.saga (direct)      bank.events (topic)│
  │  ────────────────           ────────────────      ──────────────────  │
  │  routing: event type        routing: saga step    routing: event type │
  │  e.g. account.opened        e.g. transfer.debit   e.g. deposit.*     │
  │         deposit.completed         transfer.credit                     │
  └─────────┬───────────────────────────┬─────────────────────┬──────────┘
            │                           │                     │
            ▼                           ▼                     ▼
  ┌─────────────────────────────────────────────────────────────────────┐
  │                         Queues                                       │
  │                                                                      │
  │  From es.outbox:                                                     │
  │  ├── es.projections.account-balance                                  │
  │  │   binding: account.* (all account events)                        │
  │  ├── es.projections.transaction-log                                  │
  │  │   binding: # (all events)                                        │
  │  └── es.search.indexer                                               │
  │      binding: # (all events → search index)                         │
  │                                                                      │
  │  From es.saga:                                                       │
  │  ├── es.saga.transfer.debit                                          │
  │  │   binding: transfer.debit                                        │
  │  ├── es.saga.transfer.credit                                         │
  │  │   binding: transfer.credit                                       │
  │  └── es.saga.transfer.complete                                       │
  │      binding: transfer.complete                                     │
  │                                                                      │
  │  From bank.events:                                                   │
  │  ├── notification.events                                             │
  │  │   binding: deposit.completed, withdrawal.completed               │
  │  └── audit.events                                                    │
  │      binding: # (all bank events)                                   │
  │                                                                      │
  │  Dead letter:                                                        │
  │  └── es.dlq (bound to es.dlx fanout exchange)                       │
  │      All queues set x-dead-letter-exchange: es.dlx                  │
  └─────────────────────────────────────────────────────────────────────┘
```

### Example Clojure code to declare the topology

This is the code you'd put in your `es.rabbitmq` namespace (or similar):

```clojure
;; es.rabbitmq/topology — declare exchanges, queues, and bindings on startup
;;
;; Requires:
;;   [langohr.exchange :as rmq.exchange]
;;   [langohr.queue    :as rmq.queue]

(defn declare-topology!
  "Declares the full message topology. Idempotent — safe to call on every startup."
  [channel]
  ;; Dead letter exchange (must exist before queues reference it)
  (rmq.exchange/declare channel "es.dlx" "fanout" {:durable true})
  (rmq.queue/declare channel "es.dlq" {:durable true})
  (rmq.queue/bind channel "es.dlq" "es.dlx" {:routing-key ""})

  ;; Outbox exchange — publishes domain events after DB commit
  (rmq.exchange/declare channel "es.outbox" "topic" {:durable true})

  ;; Saga exchange — routes commands to saga step handlers
  (rmq.exchange/declare channel "es.saga" "direct" {:durable true})

  ;; Integration events — cross-module communication
  (rmq.exchange/declare channel "bank.events" "topic" {:durable true})

  ;; Projection queues (consume from es.outbox)
  (let [queue-opts {:durable true
                    :arguments {"x-dead-letter-exchange" "es.dlx"}}]
    (rmq.queue/declare channel "es.projections.account-balance" queue-opts)
    (rmq.queue/bind channel "es.projections.account-balance" "es.outbox"
                     {:routing-key "account.*"})

    (rmq.queue/declare channel "es.projections.transaction-log" queue-opts)
    (rmq.queue/bind channel "es.projections.transaction-log" "es.outbox"
                     {:routing-key "#"})

    (rmq.queue/declare channel "es.search.indexer" queue-opts)
    (rmq.queue/bind channel "es.search.indexer" "es.outbox"
                     {:routing-key "#"}))

  ;; Saga queues (consume from es.saga)
  (let [queue-opts {:durable true
                    :arguments {"x-dead-letter-exchange" "es.dlx"}}]
    (rmq.queue/declare channel "es.saga.transfer.debit" queue-opts)
    (rmq.queue/bind channel "es.saga.transfer.debit" "es.saga"
                     {:routing-key "transfer.debit"})

    (rmq.queue/declare channel "es.saga.transfer.credit" queue-opts)
    (rmq.queue/bind channel "es.saga.transfer.credit" "es.saga"
                     {:routing-key "transfer.credit"})

    (rmq.queue/declare channel "es.saga.transfer.complete" queue-opts)
    (rmq.queue/bind channel "es.saga.transfer.complete" "es.saga"
                     {:routing-key "transfer.complete"}))

  ;; Integration event queues (consume from bank.events)
  (let [queue-opts {:durable true
                    :arguments {"x-dead-letter-exchange" "es.dlx"}}]
    (rmq.queue/declare channel "notification.events" queue-opts)
    (rmq.queue/bind channel "notification.events" "bank.events"
                     {:routing-key "deposit.completed"})
    (rmq.queue/bind channel "notification.events" "bank.events"
                     {:routing-key "withdrawal.completed"})

    (rmq.queue/declare channel "audit.events" queue-opts)
    (rmq.queue/bind channel "audit.events" "bank.events"
                     {:routing-key "#"})))
```

> **Why `x-dead-letter-exchange` on every queue?** When a consumer rejects a message (or it expires), RabbitMQ routes it to the dead letter exchange instead of dropping it. The `es.dlq` queue collects all failed messages so you can inspect and replay them.

> **Why declare in code, not a definitions file?** The topology is part of your application logic — it changes when you add a new projection or saga step. Keeping it in code means it's version-controlled with the app and tested in CI. A definitions file would need to be kept in sync with two separate sources of truth.

> **Note:** You've written the topology code but haven't deployed it yet. You'll verify that exchanges, queues, and bindings are created correctly in Step 5 (deploy and verify).

#### Message acknowledgement — how consumers confirm delivery

Understanding acknowledgement is critical for event sourcing reliability. A message that isn't ACKed stays in the queue — this is how RabbitMQ guarantees at-least-once delivery.

```
  Consumer acknowledgement lifecycle

  ┌──────────────┐  deliver   ┌──────────────────────────────────────────┐
  │              │──────────►│  Consumer receives message                │
  │  Queue       │            │                                          │
  │              │            │  Message is now "unacked"                 │
  │  messages:   │            │  (still in queue, invisible to others)   │
  │  ┌────┐      │            └──────────┬─────────────────┬─────────────┘
  │  │ m1 │ ←──── unacked                │                 │
  │  ├────┤                              │                 │
  │  │ m2 │ ←──── ready                  │                 │
  │  ├────┤                    process   │                 │ process
  │  │ m3 │ ←──── ready       succeeds  │                 │ fails
  │  └────┘      │                       │                 │
  └──────────────┘                       ▼                 ▼
                             ┌────────────────┐  ┌─────────────────────┐
                             │ ACK            │  │ NACK                │
                             │                │  │                     │
                             │ m1 deleted     │  │ requeue: true       │
                             │ from queue     │  │ → m1 back to queue  │
                             │ permanently ✓  │  │   (retry later)     │
                             │                │  │                     │
                             │                │  │ requeue: false      │
                             │                │  │ → m1 to DLX (es.dlx)│
                             │                │  │   (investigate)     │
                             └────────────────┘  └─────────────────────┘

  What if the consumer crashes mid-processing (no ACK or NACK)?

  ┌──────────────┐           ┌──────────────────────────────────────────┐
  │  Queue       │           │  Consumer connection drops               │
  │              │           │  (pod crash, OOM kill, network timeout)  │
  │  m1: unacked │───────────│                                          │
  │              │  timeout  │  RabbitMQ notices the connection is gone  │
  │              │◄──────────│  and re-queues the message automatically │
  │  m1: ready ✓ │           │                                          │
  └──────────────┘           └──────────────────────────────────────────┘

  This is why consumers MUST be idempotent — the same message
  may be delivered more than once (crash → redeliver).
  Use the event's aggregate_id + sequence_number as a
  deduplication key in your projection's read model.
```

---

## 5. Step 4 — Configure your app to connect

### Add RabbitMQ config to `config.edn`

```clojure
;; Add to resources/config.edn:
:rabbitmq {:host #or [#env RABBITMQ_HOST "localhost"]
           :port #long #or [#env RABBITMQ_PORT 5671]
           :username #or [#env RABBITMQ_USER "guest"]
           :password #or [#env RABBITMQ_PASSWORD "guest"]
           :vhost #or [#env RABBITMQ_VHOST "/"]
           :ssl true}
```

### Add RabbitMQ to the Helm ConfigMap

Update `helm/registry-api/templates/configmap.yaml` — add these keys:

```yaml
  RABBITMQ_HOST: {{ .Values.config.rabbitmqHost | quote }}
  RABBITMQ_PORT: {{ .Values.config.rabbitmqPort | quote }}
  RABBITMQ_VHOST: {{ .Values.config.rabbitmqVhost | quote }}
```

> **Note:** `RABBITMQ_USER` and `RABBITMQ_PASSWORD` come from Vault (Step 2), not the ConfigMap. Only non-secret connection config goes in the ConfigMap — same pattern as DB_HOST vs DB_USER.

### Add values to `values-staging.yaml`

```yaml
config:
  # ... existing config ...
  rabbitmqHost: "rabbitmq.rabbitmq.svc"
  rabbitmqPort: "5671"
  rabbitmqVhost: "/"
```

```
  How the config reaches the app

  ┌────────────────────────┐     ┌────────────────────────┐
  │ values-staging.yaml    │     │ Vault                  │
  │                        │     │                        │
  │ rabbitmqHost: rabbitmq │     │ secret/.../rabbitmq    │
  │ .rabbitmq.svc          │     │  username: registry-api│
  │ rabbitmqPort: 5671     │     │  password: ********    │
  └──────────┬─────────────┘     └──────────┬─────────────┘
             │                               │
             ▼                               ▼
  ┌──────────────────────┐     ┌──────────────────────────┐
  │ ConfigMap            │     │ /vault/secrets/rabbitmq   │
  │ RABBITMQ_HOST=...    │     │ RABBITMQ_USER=...        │
  │ RABBITMQ_PORT=5671   │     │ RABBITMQ_PASSWORD=...    │
  └──────────┬───────────┘     └──────────┬───────────────┘
             │                             │
             └──────────┬──────────────────┘
                        ▼
               ┌──────────────────┐
               │ config.edn reads │
               │ #env RABBITMQ_*  │
               │                  │
               │ App connects to  │
               │ RabbitMQ         │
               └──────────────────┘
```

### Add Langohr dependency

Add to `deps.edn`:

```clojure
com.novemberain/langohr {:mvn/version "5.6.0"}
```

> **Langohr** is the standard Clojure client for RabbitMQ. It wraps the official Java AMQP client with idiomatic Clojure APIs for channel management, publishing, consuming, and topology declaration.

#### Langohr TLS connection

With TLS enabled, the Langohr connection must use `:ssl true` and port 5671:

```clojure
;; In your system component or startup code:
(require '[langohr.core :as rmq])

;; Option A: Trust the JVM truststore (set via JAVA_TOOL_OPTIONS)
(def conn (rmq/connect {:host     (System/getenv "RABBITMQ_HOST")
                         :port     (parse-long (System/getenv "RABBITMQ_PORT"))
                         :username (System/getenv "RABBITMQ_USER")
                         :password (System/getenv "RABBITMQ_PASSWORD")
                         :vhost    (System/getenv "RABBITMQ_VHOST")
                         :ssl      true}))

;; Option B: Custom SSLContext (trusts the mounted CA cert directly)
(import '[javax.net.ssl SSLContext TrustManagerFactory]
        '[java.security KeyStore]
        '[java.security.cert CertificateFactory]
        '[java.io FileInputStream])

(defn make-ssl-context
  "Build an SSLContext that trusts the RabbitMQ CA cert."
  [ca-cert-path]
  (let [cf    (CertificateFactory/getInstance "X.509")
        ca    (with-open [fis (FileInputStream. ca-cert-path)]
                (.generateCertificate cf fis))
        ks    (doto (KeyStore/getInstance (KeyStore/getDefaultType))
                (.load nil nil)
                (.setCertificateEntry "rabbitmq-ca" ca))
        tmf   (doto (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
                (.init ks))
        ctx   (doto (SSLContext/getInstance "TLSv1.3")
                (.init nil (.getTrustManagers tmf) nil))]
    ctx))

(def conn (rmq/connect {:host        (System/getenv "RABBITMQ_HOST")
                         :port        5671
                         :username    (System/getenv "RABBITMQ_USER")
                         :password    (System/getenv "RABBITMQ_PASSWORD")
                         :vhost       (System/getenv "RABBITMQ_VHOST")
                         :ssl         true
                         :ssl-context (make-ssl-context "/etc/ssl/rabbitmq/rabbitmq-ca.crt")}))
```

> **Option A vs B:** Option A is simpler — set `JAVA_TOOL_OPTIONS` to point at the JKS truststore and Langohr picks it up automatically. Option B avoids global JVM trust store changes, which matters if you have multiple services with different CAs. For a single RabbitMQ connection, Option A is sufficient.

---

## 6. Step 5 — Deploy and verify

### Deploy the config change

```bash
helm upgrade --install registry-api helm/registry-api \
  -n registry-api \
  -f helm/registry-api/values-staging.yaml \
  --set image.tag="<your-image-tag>"
```

### Verify the connection

```bash
# Check the app logs for RabbitMQ connection
kubectl -n registry-api logs -l app=registry-api --tail=20 | grep -i rabbit
# Look for: "Connected to RabbitMQ" or similar

# Check RabbitMQ has the connection
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_connections user protocol state ssl
# Should show: registry-api  AMQP 0-9-1  running  true
#                                                   ^^^^
#                                                   ssl=true confirms TLS
```

### Verify the topology was created

```bash
# Exchanges
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_exchanges name type
# Expected:
# es.outbox       topic
# es.saga         direct
# bank.events     topic
# es.dlx          fanout

# Queues
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
# Expected: all queues with 0 messages, consumers connected

# Bindings
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_bindings source_name routing_key destination_name
```

### Send a test message

> **Note:** The Bitnami RabbitMQ image does not include `rabbitmqadmin`. Use the Management UI or `rabbitmqctl` to verify. To publish a test message, use the Management API via `curl`:

```bash
# Publish to es.outbox with routing key "account.opened" via the HTTP API
kubectl -n rabbitmq exec rabbitmq-0 -- \
  curl -s -u registry-api:<password> \
  -H "content-type: application/json" \
  -X POST "http://localhost:15672/api/exchanges/%2F/es.outbox/publish" \
  -d '{"properties":{},"routing_key":"account.opened","payload":"{\"event\":\"test\",\"aggregate_id\":\"123\"}","payload_encoding":"string"}'

# Check the message arrived in the projection queue
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_queues name messages
# es.projections.account-balance should show 1 message
# es.projections.transaction-log should show 1 message
```

---

## 7. Step 6 — Security hardening

### NetworkPolicy — restrict access to RabbitMQ

Only allow traffic from the `registry-api` namespace and within the `rabbitmq` namespace itself.

```
  NetworkPolicy — what's allowed and what's blocked

  ┌─ namespace: registry-api ──────┐
  │                                 │
  │  registry-api pod               │
  │  ├── AMQPS :5671 ─────────────────► rabbitmq-0       ✅ allowed (TLS)
  │  └── mgmt :15672 ─────────────────► rabbitmq-0       ✗ blocked
  │                                 │
  └─────────────────────────────────┘

  ┌─ namespace: rabbitmq ──────────────────────────────────────────┐
  │                                                                 │
  │  rabbitmq-0 ←── AMQPS from registry-api        ✅ allowed (TLS)│
  │  rabbitmq-0 ←── clustering from other replicas  ✅ internal     │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘

  ┌─ external / other namespaces ──┐
  │                                 │
  │  anything ─────────────────────────► rabbitmq         ✗ blocked
  │                                 │
  └─────────────────────────────────┘
```

Add to `k8s/network-policies.yaml`:

```yaml
---
# Only allow AMQP ingress to RabbitMQ from registry-api namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: rabbitmq-ingress
  namespace: rabbitmq
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/name: rabbitmq
  policyTypes:
    - Ingress
  ingress:
    # Allow AMQPS from app namespace (TLS-encrypted)
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: registry-api
      ports:
        - protocol: TCP
          port: 5671    # AMQPS (TLS)
    # Allow clustering between RabbitMQ pods (for future multi-replica)
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: rabbitmq
      ports:
        - protocol: TCP
          port: 4369    # epmd (Erlang Port Mapper)
        - protocol: TCP
          port: 25672   # inter-node communication
    # Allow metrics scraping from observability namespace
    - from:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: observability
      ports:
        - protocol: TCP
          port: 9419    # metrics (Prometheus format, scraped by OTel Collector)
```

Apply:

```bash
kubectl label namespace rabbitmq kubernetes.io/metadata.name=rabbitmq --overwrite
kubectl apply -f k8s/network-policies.yaml
```

> **GKE Autopilot note:** GKE Autopilot supports NetworkPolicies natively — no extra CNI plugin needed. The `kubernetes.io/metadata.name` label is automatically set on namespaces in recent K8s versions (1.22+), so the `kubectl label` command above is a safety measure. Same pattern as the Vault and observability NetworkPolicies from Phases 4 and 5.

### Update the registry-api egress policy

Add this rule to the `registry-api-egress` NetworkPolicy:

```yaml
    # Allow traffic to RabbitMQ (TLS)
    - to:
        - namespaceSelector:
            matchLabels:
              kubernetes.io/metadata.name: rabbitmq
      ports:
        - protocol: TCP
          port: 5671
```

### Pod security context

The Bitnami chart already runs with strong defaults out of the box:

```yaml
# Already set by default in bitnami/rabbitmq:
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

> **No changes needed** — the Bitnami chart ships with security best practices enabled by default (non-root, read-only filesystem, drop all capabilities). This is better than some other charts where you have to add these yourself.

---

## 8. Step 7 — Monitoring with Grafana

If you completed Phase 5 (observability), connect RabbitMQ metrics to Grafana.

### How metrics flow

```
  RabbitMQ metrics pipeline

  rabbitmq-0                    OTel Collector              Mimir
  ┌──────────────┐             ┌───────────────┐          ┌──────────┐
  │ /metrics     │◄── scrape ──│ prometheus    │── push ──►│ gateway  │
  │ :9419        │             │ receiver      │  remote   │ :80      │
  └──────────────┘             │ (scrapes      │  write    └──────────┘
                               │  Prom format) │                │
                               └───────────────┘                ▼
                                                          ┌──────────┐
                                                          │ Grafana  │
                                                          │ PromQL   │
                                                          └──────────┘

  No standalone Prometheus server — OTel Collector scrapes the
  Prometheus-format /metrics endpoint and remote-writes to Mimir
  (same pipeline as Phase 5 app metrics).
```

### Option A: Add a scrape config to OTel Collector (recommended with Phase 5)

Phase 5 deployed an OTel Collector that pushes metrics to Mimir via remote-write. RabbitMQ exposes metrics in Prometheus format on port 9419 — add it as a scrape target so OTel Collector pulls these and forwards them to Mimir alongside your app metrics. Update `otel-collector-values.yaml`:

```yaml
config:
  receivers:
    # ... existing receivers ...
    prometheus/rabbitmq:
      config:
        scrape_configs:
          - job_name: rabbitmq
            scrape_interval: 30s
            static_configs:
              - targets: ["rabbitmq.rabbitmq.svc:9419"]

  service:
    pipelines:
      metrics:
        receivers: [otlp, prometheus/rabbitmq]   # add the new receiver
        processors: [memory_limiter, resource, batch]
        exporters: [prometheusremotewrite]
```

### Option B: Use Grafana Alloy to scrape metrics

Alternatively, add a scrape job to Alloy's config. This is simpler if you want to keep OTel Collector focused on app telemetry.

### Key PromQL queries for RabbitMQ (query in Grafana → Mimir datasource)

| Panel | PromQL |
|-------|--------|
| Messages published/sec | `rate(rabbitmq_channel_messages_published_total[5m])` |
| Messages delivered/sec | `rate(rabbitmq_channel_messages_delivered_total[5m])` |
| Queue depth (unacked) | `rabbitmq_queue_messages_unacked` |
| Queue depth (ready) | `rabbitmq_queue_messages_ready` |
| Dead letter queue depth | `rabbitmq_queue_messages_ready{queue="es.dlq"}` |
| Consumer count | `rabbitmq_queue_consumers` |
| Connection count | `rabbitmq_connections` |
| Memory usage | `rabbitmq_process_resident_memory_bytes` |
| Disk usage | `rabbitmq_disk_space_available_bytes` |

### Essential alerts

| Alert | Condition | Severity |
|-------|-----------|----------|
| Dead letter queue growing | `rabbitmq_queue_messages_ready{queue="es.dlq"} > 0` for 5 min | Warning |
| Queue depth too high | `rabbitmq_queue_messages_ready > 10000` for 10 min | Warning |
| No consumers on a queue | `rabbitmq_queue_consumers == 0` for 5 min | Critical |
| Memory near watermark | `rabbitmq_process_resident_memory_bytes / rabbitmq_resident_memory_limit_bytes > 0.8` | Warning |
| RabbitMQ down | `up{job="rabbitmq"} == 0` for 2 min | Critical |

```
  What healthy vs unhealthy looks like in Grafana

  HEALTHY                                UNHEALTHY
  ───────                                ─────────

  Published/sec: ████████  45/s          Published/sec: ████████  45/s
  Delivered/sec: ████████  45/s          Delivered/sec: ██        8/s  ← gap!
                 (rates match)                          (consumers
                                                         falling behind)

  Queue depth:   ██  12                  Queue depth:   █████████████ 14,829
                 (low, stable)                          (growing = problem)

  DLQ depth:     0                       DLQ depth:     ███  47
                 (no failures)                          (messages failing)

  Consumers:     ██  6                   Consumers:     0
                 (all connected)                        (nobody listening!)

  Memory:        ███  180/410 MiB        Memory:        ████████ 395/410 MiB
                 (well under watermark)                 (near watermark!)
```

> **The DLQ alert is the most important one.** A growing dead letter queue means messages are failing and not being processed. Investigate immediately — it usually means a consumer bug or a downstream service is down.

---

## 9. How it works at runtime

```
  Complete message flow — deposit use case

  1. HTTP request: POST /api/accounts/:id/deposit
     │
  2. Command handler:
     │  ├── Load account aggregate from event store
     │  ├── Apply business rules (sufficient balance? account active?)
     │  ├── Append DepositCompleted event to event store
     │  └── Write outbox record to same DB transaction (atomic)
     │
  3. Outbox publisher (background process):
     │  ├── Poll outbox table for unpublished events
     │  ├── Publish to es.outbox exchange with routing key "deposit.completed"
     │  └── Mark outbox record as published
     │
  4. RabbitMQ routes the message:
     │  ├── es.outbox → es.projections.account-balance (matches deposit.*)
     │  ├── es.outbox → es.projections.transaction-log (matches #)
     │  └── es.outbox → es.search.indexer (matches #)
     │
  5. Integration event publisher (separate process):
     │  └── Publish to bank.events exchange with routing key "deposit.completed"
     │
  6. RabbitMQ routes the integration event:
     │  ├── bank.events → notification.events (matches deposit.completed)
     │  └── bank.events → audit.events (matches #)
     │
  7. Consumers process:
        ├── account-balance projection: update read model
        ├── transaction-log projection: append to log
        ├── search indexer: update search index
        ├── notification module: send notification if large deposit
        └── audit: log for compliance

  If any consumer fails:
  ├── Message is nacked → RabbitMQ retries (up to a limit)
  └── After retries exhausted → dead letter exchange → es.dlq
      (you get an alert, inspect the message, fix the bug, replay)
```

#### What happens when a consumer fails — the dead letter flow

```
  Dead letter lifecycle

  1. Normal flow
  ┌──────────┐    ┌────────────────────┐    ┌──────────────┐
  │ Exchange │───►│ es.projections     │───►│ Consumer     │
  │          │    │ .account-balance   │    │ processes    │
  └──────────┘    │ (x-dead-letter-    │    │ + ACKs       │
                  │  exchange: es.dlx) │    └──────────────┘
                  └────────────────────┘         message deleted ✓

  2. Consumer rejects (nack without requeue)
  ┌──────────┐    ┌────────────────────┐    ┌──────────────┐
  │ Exchange │───►│ es.projections     │───►│ Consumer     │
  │          │    │ .account-balance   │    │ throws error │
  └──────────┘    └────────┬───────────┘    │ → NACK       │
                           │                └──────────────┘
                           │ x-dead-letter-exchange: es.dlx
                           ▼
                  ┌────────────────────┐
                  │ es.dlx (fanout)    │  dead letter exchange
                  └────────┬───────────┘
                           │ broadcast to all bound queues
                           ▼
                  ┌────────────────────┐
                  │ es.dlq             │  dead letter queue
                  │                    │
                  │ Message stored     │  ◄── Grafana alert fires:
                  │ with headers:      │      "DLQ depth > 0"
                  │  x-death:          │
                  │   queue: es.prj... │  ◄── tells you WHERE it failed
                  │   reason: rejected │  ◄── tells you WHY
                  │   count: 1         │  ◄── how many times
                  │   time: 2026-...   │
                  └────────────────────┘

  3. Recovery
  ┌─────────────────────────────────────────────────────────────┐
  │  a) Read message from DLQ (inspect via HTTP API)            │
  │  b) Fix the consumer bug                                    │
  │  c) Republish the message to the original exchange          │
  │     (or use a shovel plugin to move DLQ → original queue)   │
  │  d) Message processes successfully this time                │
  └─────────────────────────────────────────────────────────────┘
```

> **Why not just retry forever?** Infinite retries create a "poison message" problem — a malformed message that no amount of retrying will fix spins the consumer in a loop, blocking the queue. Dead lettering moves it aside so the queue keeps flowing while you investigate.

---

## 10. Day-2 operations

#### What happens when RabbitMQ restarts?

```
  Pod restart → what survives and what doesn't

  SURVIVES (persisted to PVC):             LOST (in-memory only):
  ─────────────────────────────            ─────────────────────────

  ✓ Durable exchanges                     ✗ Active connections
    (all of ours are durable)               (consumers reconnect)

  ✓ Durable queues                        ✗ Unconfirmed publishes
    (all of ours are durable)               (outbox republishes)

  ✓ Persistent messages                   ✗ Channels
    (delivery_mode=2, Langohr              (recreated on reconnect)
     default for durable queues)

  ✓ Bindings                              ✗ Transient messages
    (exchange→queue routing rules)          (delivery_mode=1, not
                                             used in our setup)
  ✓ Users + permissions
    (registry-api user + vhost)

  ✓ Erlang cookie
    (cluster authentication)

  After restart, RabbitMQ reads its Mnesia database from the PVC,
  recreates all exchanges/queues/bindings, and makes persistent
  messages available again. Your app's Langohr connection will
  detect the disconnect and should reconnect automatically.
```

### Access the Management UI

```bash
kubectl -n rabbitmq port-forward svc/rabbitmq 15672:15672 &
# Open http://localhost:15672
```

### Check queue depths

```bash
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_queues name messages consumers
```

### Purge a queue (clear all messages)

```bash
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl purge_queue <queue-name>
```

### Inspect dead letter queue

```bash
# Count messages in DLQ
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_queues name messages | grep dlq

# Get a message from the DLQ (peek without consuming) via the HTTP API
kubectl -n rabbitmq exec rabbitmq-0 -- \
  curl -s -u registry-api:<password> \
  -H "content-type: application/json" \
  -X POST "http://localhost:15672/api/queues/%2F/es.dlq/get" \
  -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}'
```

### Scale to multiple replicas (production)

For high availability, run a 3-node RabbitMQ cluster:

```yaml
# In rabbitmq-values.yaml for production:
replicaCount: 3
clustering:
  enabled: true
  partitionHandling: pause_minority   # safest for data consistency
```

```
  Production 3-node cluster

  ┌─ namespace: rabbitmq ──────────────────────────────────────────────────┐
  │                                                                         │
  │  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐             │
  │  │ rabbitmq-0   │    │ rabbitmq-1   │    │ rabbitmq-2   │             │
  │  │              │◄──►│              │◄──►│              │             │
  │  │  leader for  │    │  follower    │    │  follower    │             │
  │  │  some queues │    │  for those   │    │  for those   │             │
  │  └──────┬───────┘    └──────┬───────┘    └──────┬───────┘             │
  │         │                   │                   │                      │
  │         ▼                   ▼                   ▼                      │
  │  ┌────────────┐      ┌────────────┐      ┌────────────┐              │
  │  │  PVC-0     │      │  PVC-1     │      │  PVC-2     │              │
  │  │  8Gi       │      │  8Gi       │      │  8Gi       │              │
  │  └────────────┘      └────────────┘      └────────────┘              │
  │                                                                         │
  │  Erlang clustering ports:                                               │
  │  ├── 4369   epmd (port mapper — nodes discover each other)             │
  │  └── 25672  inter-node communication (message replication)             │
  │                                                                         │
  │  Service (load balanced across all 3 nodes):                           │
  │  └── rabbitmq:5671 → round-robin to rabbitmq-0, -1, -2 (TLS)         │
  └─────────────────────────────────────────────────────────────────────────┘

  Network partition handling — pause_minority:

  Normal:  [0, 1, 2]  all in one group       → all active ✓

  Split:   [0, 1] | [2]  partition occurs
           majority │  minority
           (2/3)    │  (1/3)
           active ✓ │  pauses itself ✗
                    │  (refuses connections
                    │   until partition heals)

  Why pause? Node 2 might have stale data. If it kept serving,
  a producer could write to node 2 while node 0 has a different
  version → split-brain. Pausing prevents this. When the network
  heals, node 2 rejoins and syncs from the majority.
```

> **`pause_minority`** vs **`autoheal`**: With `pause_minority`, if a network partition splits your 3-node cluster into groups of 2 and 1, the minority (1 node) pauses itself. This prevents split-brain but means that node is unavailable. `autoheal` (the staging default) automatically picks a winner, which risks message loss in rare edge cases. For production with the event sourcing pattern, `pause_minority` is safer — you'd rather have a brief pause than lose messages.

### Rotate credentials

```
  Credential rotation — order matters

  ┌─────────────────────────────────────────────────────────────────┐
  │  Step 1: Generate new password                                   │
  │  openssl rand -base64 24                                        │
  └──────────────────────────────────┬──────────────────────────────┘
                                     │
                                     ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  Step 2: Update in RabbitMQ FIRST                               │
  │  rabbitmqctl change_password registry-api "$NEW_PASSWORD"       │
  │                                                                  │
  │  RabbitMQ now accepts BOTH old and new password?  NO.            │
  │  It accepts ONLY the new one immediately.                        │
  │  Existing connections stay open (already authenticated).         │
  │  New connections must use the new password.                      │
  └──────────────────────────────────┬──────────────────────────────┘
                                     │
                                     ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  Step 3: Update Vault                                            │
  │  vault kv put secret/.../rabbitmq password="$NEW_PASSWORD"      │
  │                                                                  │
  │  Vault now has the new password. But the app pods still have     │
  │  the old one cached from their last Vault Agent injection.       │
  └──────────────────────────────────┬──────────────────────────────┘
                                     │
                                     ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  Step 4: Rolling restart app pods                                │
  │  kubectl -n registry-api rollout restart deployment/registry-api│
  │                                                                  │
  │  New pods start → Vault Agent injects new password →             │
  │  app connects to RabbitMQ with new credentials ✓                │
  │                                                                  │
  │  Brief window: old pods closing, new pods connecting.           │
  │  Messages in-flight are safe (already in queues).               │
  └─────────────────────────────────────────────────────────────────┘

  Why this order? If you updated Vault first and restarted pods
  before updating RabbitMQ, the new pods would try to connect
  with a password RabbitMQ doesn't know yet → connection refused.
```

```bash
# 1. Generate a new password
NEW_PASSWORD=$(openssl rand -base64 24)

# 2. Update in RabbitMQ FIRST
kubectl -n rabbitmq exec rabbitmq-0 -- \
  rabbitmqctl change_password registry-api "$NEW_PASSWORD"

# 3. Update in Vault
vault kv put secret/staging/registry-api/rabbitmq \
  username="registry-api" \
  password="$NEW_PASSWORD"

# 4. Restart app pods to pick up new secret
kubectl -n registry-api rollout restart deployment/registry-api
```

---

## 11. Troubleshooting

```
  Quick diagnosis — connection issues

  App can't connect to RabbitMQ?
  │
  ├── DNS resolution? ──► Can the app resolve the hostname?
  │   kubectl -n registry-api exec <pod> -- nslookup rabbitmq.rabbitmq.svc
  │
  ├── Port reachable? ──► Is AMQPS port open?
  │   kubectl -n registry-api exec <pod> -- nc -zv rabbitmq.rabbitmq.svc 5671
  │
  ├── Credentials? ──► Is Vault injecting the right password?
  │   kubectl -n registry-api exec <pod> -- cat /vault/secrets/rabbitmq
  │
  ├── NetworkPolicy? ──► Is egress to rabbitmq namespace allowed?
  │   kubectl get networkpolicy -n registry-api -o yaml | grep rabbitmq
  │
  └── RabbitMQ healthy? ──► Is the pod running and ready?
      kubectl -n rabbitmq get pods
      kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl status
```

### App can't connect to RabbitMQ

Follow the decision tree above. Here are the copy-pasteable commands:

```bash
# Check RabbitMQ is running and ready
kubectl -n rabbitmq get pods
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl status

# Check DNS resolves from the app namespace
kubectl -n registry-api run dns-test --image=busybox --rm -it -- nslookup rabbitmq.rabbitmq.svc

# Check AMQPS port is reachable (TLS)
kubectl -n registry-api run port-test --image=busybox --rm -it -- nc -zv rabbitmq.rabbitmq.svc 5671
```

### Messages stuck in queue (not being consumed)

```bash
# Check consumer count
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_queues name consumers
# If consumers = 0, the app isn't subscribed

# Check app logs for consumer errors
kubectl -n registry-api logs -l app=registry-api --tail=50 | grep -i "consumer\|channel\|rabbit"
```

### Dead letter queue growing

```bash
# Check DLQ depth
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl list_queues name messages | grep dlq

# Inspect a failed message via the HTTP API
kubectl -n rabbitmq exec rabbitmq-0 -- \
  curl -s -u registry-api:<password> \
  -H "content-type: application/json" \
  -X POST "http://localhost:15672/api/queues/%2F/es.dlq/get" \
  -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}'

# The message headers contain x-death with the original queue and reason
```

### RabbitMQ running out of memory

```bash
# Check memory usage vs watermark
kubectl -n rabbitmq exec rabbitmq-0 -- rabbitmqctl status | grep -A5 "Memory"

# If memory alarm is triggered, RabbitMQ blocks publishers
# Increase the limit in rabbitmq-values.yaml:
# resources.limits.memory: 2Gi
# memoryHighWatermark.value: 0.4  (still 40%, but of 2Gi = ~820Mi)
```

### RabbitMQ pod won't start after crash

```bash
# Check pod events
kubectl -n rabbitmq describe pod rabbitmq-0

# Check logs
kubectl -n rabbitmq logs rabbitmq-0 --previous

# If Mnesia database is corrupted, force boot (last resort — may lose messages):
# In rabbitmq-values.yaml: clustering.forceBoot: true
# helm upgrade, then set it back to false
```

---

## 12. Tear down (if needed)

```
  Tear-down order

  Step 1: Disconnect app
  ┌───────────────┐
  │ registry-api  │  Remove RABBITMQ_* from ConfigMap, Vault annotations,
  └───────┬───────┘  :rabbitmq from config.edn, Langohr from deps.edn.
          │          Redeploy.
          ▼
  Step 2: Drain queues (optional — skip if data loss is OK)
  ┌───────────────┐
  │ rabbitmq      │  Wait for queues to empty or purge
  └───────┬───────┘
          ▼
  Step 3: Remove NetworkPolicies (before namespace deletion)
  ┌───────────────┐
  │ k8s policies  │  Delete rabbitmq-ingress, remove egress rule
  └───────┬───────┘
          ▼
  Step 4: Uninstall RabbitMQ + namespace
  ┌───────────────┐
  │ helm, PVC, ns │  Remove everything
  └───────┬───────┘
          ▼
  Step 5: Clean up Vault + OTel
  ┌───────────────┐
  │ vault, otel   │  Remove secret, revert policy, revert OTel config
  └───────────────┘
```

```bash
# 1. Remove RabbitMQ config from app and redeploy
#    - Remove RABBITMQ_* from helm/registry-api/templates/configmap.yaml
#    - Remove Vault annotations for rabbitmq from deployment.yaml
#    - Remove :rabbitmq section from resources/config.edn
#    - Remove com.novemberain/langohr from deps.edn
#    - Remove rabbitmq egress rule from registry-api-egress NetworkPolicy
#    Redeploy: helm upgrade --install registry-api ...

# 2. Delete NetworkPolicy (while namespace still exists)
kubectl delete networkpolicy rabbitmq-ingress -n rabbitmq

# 3. Uninstall RabbitMQ
helm uninstall rabbitmq --namespace rabbitmq

# 4. Delete PVCs (message data is permanently lost)
kubectl delete pvc --all -n rabbitmq

# 5. Delete the namespace
kubectl delete namespace rabbitmq

# 6. Remove Vault secret and revert policy
vault kv delete secret/staging/registry-api/rabbitmq
# Re-write the Vault policy WITHOUT the rabbitmq path:
vault policy write registry-api - <<'EOF'
path "secret/data/staging/registry-api/db" {
  capabilities = ["read"]
}
EOF

# 7. Revert OTel Collector config (if Phase 5)
#    Remove the prometheus/rabbitmq receiver from otel-collector-values.yaml
#    Remove it from the metrics pipeline receivers list
#    helm upgrade otel-collector ... -f otel-collector-values.yaml
```

---

## 13. Checklist — Phase 6 complete

```
Infrastructure:
  [ ] rabbitmq namespace created
  [ ] RabbitMQ pod running (rabbitmq-0)
  [ ] AMQP port accessible from app namespace
  [ ] Management UI accessible via port-forward

Credentials:
  [ ] RabbitMQ password stored in Vault
  [ ] Vault policy updated to include rabbitmq path
  [ ] Vault Agent annotation added to deployment template

Topology:
  [ ] Exchanges created: es.outbox, es.saga, bank.events, es.dlx
  [ ] Queues created with dead-letter-exchange set to es.dlx
  [ ] Bindings match the routing patterns for each queue

App configuration:
  [ ] config.edn has :rabbitmq section
  [ ] ConfigMap has RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_VHOST
  [ ] values-staging.yaml has rabbitmq connection config
  [ ] Langohr dependency added to deps.edn
  [ ] App connects to RabbitMQ on startup
  [ ] Topology declared on startup (exchanges, queues, bindings)

Security:
  [ ] TLS enabled (auth.tls.enabled: true, autoGenerated: true)
  [ ] App connects on port 5671 (AMQPS), not 5672
  [ ] Connections show ssl=true in rabbitmqctl list_connections
  [ ] CA cert mounted in app pod (/etc/ssl/rabbitmq/rabbitmq-ca.crt)
  [ ] NetworkPolicy restricts RabbitMQ ingress to registry-api namespace (port 5671)
  [ ] registry-api egress policy allows traffic to rabbitmq namespace
  [ ] Metrics scraping allowed from observability namespace
  [ ] RabbitMQ runs as non-root (Bitnami default)

Monitoring (if Phase 5 completed):
  [ ] Metrics endpoint (:9419) enabled
  [ ] otel-collector-values.yaml updated with prometheus/rabbitmq receiver
  [ ] OTel Collector helm-upgraded with new config
  [ ] Grafana dashboard with queue depth, publish/consume rates
  [ ] Alerts configured: DLQ growth, queue depth, no consumers, memory, down
```

**You now have async messaging.** Your app can publish domain events, run sagas across aggregates, and send integration events between modules — all through RabbitMQ with reliable delivery, dead-letter handling, and Vault-managed credentials.

---

## Files created in this phase

```
New files:
  rabbitmq-values.yaml                       # RabbitMQ Helm install config (project root,
                                             #   same level as vault-values.yaml from Phase 4)

Modified:
  helm/registry-api/values-staging.yaml      # rabbitmqHost, rabbitmqPort, rabbitmqVhost added
  helm/registry-api/templates/configmap.yaml  # RABBITMQ_HOST, RABBITMQ_PORT, RABBITMQ_VHOST
  helm/registry-api/templates/deployment.yaml # Vault annotation for rabbitmq secret
                                             # + rabbitmq-ca volume mount for TLS
  resources/config.edn                       # :rabbitmq section (with :ssl true)
  deps.edn                                  # com.novemberain/langohr dependency
  k8s/network-policies.yaml                 # rabbitmq-ingress NetworkPolicy (new)
                                             # registry-api-egress rule update (modified)
  otel-collector-values.yaml                 # prometheus/rabbitmq scrape config (if Phase 5)
```

**Next:** [Phase 7 — Redis caching & idempotency](K8s-phase7.md)
