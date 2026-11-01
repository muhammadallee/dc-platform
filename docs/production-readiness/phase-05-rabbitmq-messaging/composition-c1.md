# Phase 5 — Composition checkpoint C1 (Gate G)

| Item | Value |
|---|---|
| App | archetype-generated `hardening-outbox`: Core + Observability + Oracle + Outbox + RabbitMQ, `prod` profile |
| Code | `5cafcf3` |
| Result | **106/106** (`evidence/phase-04-regression-c1`), and the Phase 5 consumer's AC1 and AC5 |

| C1 scenario | Evidence | Result |
|---|---|---|
| HTTP → DB write + outbox → drain → Rabbit → consumer → DB write, one trace | Phase 5 AC1: the handler's logging context holds the inbound trace id; it writes its row | pass |
| DB slow, broker healthy | 500 ms per Oracle response: a request took 5 s, the drainer stayed within its 2 connections, application pool pending 0, 100 of 100, no duplicates | pass |
| Broker down, DB healthy | 30 requests all 200, pending 30, lag 22 s, nothing parked; 30 of 30 on recovery, no duplicates | pass |
| App killed mid-flow | `SIGKILL` with another instance alive: 300 of 300, 15 duplicates. Only instance, then a new one: 200 of 200, 18 duplicates. Limit 50 | pass |
| Drainer and requests under load do not exhaust the pool | 20 parallel requests with a slow broker: drainer capped at 2, application pool pending 0. Two instances, 500 events | pass |
| Database lost mid-drain (added in Phase 4) | 200 of 200, 22 duplicates, recovery without a restart | pass |

**Limit:** "under load" is these bursts, not a sustained load test. The fault scenarios run in the Phase 4 scenario, on an app of exactly C1's capability set, rather than in a separate third app.

No composition defect was found at this gate.
