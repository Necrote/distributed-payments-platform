# Performance tests (Gatling) — Phase 8

Nothing here has been run yet. Everything below is a **test plan**; every number on this page is a
**target**, not a result. Measured results live in [`docs/performance/`](../../docs/performance/) and
nowhere else — and nothing reaches the README or a resume until it has been measured, dated and
written up there.

Gatling rather than JMeter or k6 for one honest reason: it is what was used professionally, so the
skill is transferable in both directions and the scenarios can be written quickly enough to iterate.

## Scenarios

| Scenario | Target load | Duration | Question it answers |
|---|---|---|---|
| `SmokeSimulation` | 5 TPS | 60 s | Does the system work at all before a real run? Cheap enough for CI. |
| `BaselineSimulation` | 50 TPS | 5 min | What does a healthy system look like? Everything else is compared to this. |
| `NormalLoadSimulation` | 200 TPS | 10 min | Are latency and error rate stable at expected load? |
| `PeakLoadSimulation` | 500 TPS | 10 min | Does it hold at peak, and which resource gets closest to its limit? |
| `StressSimulation` | ramp until SLO breaks | until failure | **Where is the actual bottleneck?** The most valuable run of the set. |
| `SpikeSimulation` | 100 → 500 TPS in 30 s | 10 min | Can it absorb a sudden 5× increase, and does HPA react in time to matter? |
| `SoakSimulation` | 150 TPS | 30–60 min | Is there a leak, a slow degradation, or unbounded table growth? |

Request mix, unless a scenario says otherwise: **80% `POST /payments`** (each with a unique
`Idempotency-Key`) and **20% `GET /payments/{id}`** against payments created earlier in the run.

## What is recorded for every run

```text
achieved TPS (not offered TPS - the difference IS the finding)
p50 / p90 / p95 / p99 / max latency
error rate, by status code
Hikari: active / idle / pending
PostgreSQL: CPU, active connections, slowest statements
app: CPU, heap, GC pause time
Kafka: producer error rate, consumer lag
Redis: hit ratio
pod count (Phase 7 onwards)
```


## Deliberate experiments

Two runs are not really performance tests — they are demonstrations with a conclusion, and each gets a
document in `docs/performance/` plus a postmortem in `docs/incidents/`:

- **HikariCP exhaustion** (`hikaricp-experiment.md`) — a small pool, a slow query and high
  concurrency, which recreates in miniature a production incident.
  Watch `hikaricp_connections_pending` rise before p95 does: that ordering is the diagnosis.
- **HPA scaling** (`hpa-scaling.md`) — scale replicas up under load and show that beyond a point
  **more pods make it worse**, because each new pod opens its own connections to the same finite
  database. The lesson — that horizontal scaling moves a bottleneck rather than removing one — is the
  most useful thing in this entire directory.
