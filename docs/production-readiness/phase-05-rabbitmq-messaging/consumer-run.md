# Phase 5 — Gate F consumer run (RabbitMQ)

| Item | Value |
|---|---|
| Harness | `tooling/hardening/scenarios/phase-05-rabbit.sh` (probe and migration in `phase-05/`) |
| Consumer | archetype `-Dfeatures=data,messaging -Ddatabase=oracle`, `prod` profile, with the Rabbit and outbox starters, plus the probe |
| Infrastructure | Oracle Free 23ai in 19 mode, `rabbitmq:3.13-management-alpine` with the shovel plugins, Toxiproxy 2.9.0 in front of RabbitMQ; digests in each `run-identity.txt` |
| Settings | Platform defaults: 3 attempts, 1 s backoff, prefetch 10, quorum queues |
| Closing code | `5cafcf3` — three runs, **63/63 each** (`evidence/gate-f-closing-{1,2,3}`) |
| Regressions on the same code | Phase 1 **92/92**, Phase 2 **75/75**, Phase 3 **92/92**, Phase 4 **106/106** (also C1's fault scenarios) |
| Committed evidence | Assertions, HTTP bodies, run identity; not raw logs (kept under `~/dc-hardening-runs/`). Scanned for tokens, credentials, PII and the planted payload marker |

## Acceptance criteria (CONSUMER level)

Identical in all three closing runs.

| AC | What the run asserts | Result |
|---|---|---|
| AC1 | Through the outbox and directly: the handler's logging context holds the request's trace id, and the message names its producer | pass |
| AC2 | A failing handler: **exactly 3 attempts**, then the DLQ; the main queue empty; the outcome counted | pass (baseline: 9) |
| AC3 | An unreadable payload from outside the platform reaches the DLQ; other messages flow; the planted marker is in no log | pass |
| AC5 | Broker down: direct publish fails with a 5xx, 5 outbox requests succeed, the consumer reconnects by itself and all arrive | pass |
| AC7 | Broker down at startup: the app is ready, an outbox request succeeds, and it consumes once the broker arrives | pass |
| AC9 | The same event id delivered twice: the naive consumer applied it twice, the documented pattern once | pass |
| AC10 | An unroutable direct publish surfaces as a 5xx | pass |
| AC11 | `SIGTERM` with 6 slow messages: exit 143, the 2 in flight finish, nothing dead-lettered; the restart delivers the rest, 6 of 6, no duplicates | pass |
| AC12 | DLQ inspected by the script, the message left in place, the handler fixed, replayed: handled under the same event id; DLQ empty | pass |
| AC13 | A newer version reaches the DLQ unhandled; an unknown field is tolerated | pass |
| AC14 | A credential-named header is refused, and nothing is sent | pass |
| AC15 | Main and dead-letter queues are quorum | pass |

## Limits of this evidence

- **AC4** at CONTAINER level uses repeated requeues as a stand-in for a killed consumer (amendment A2).
- **AC6** (slow broker) is carried by the Phase 4 consumer, run on this code.
- **AC8** (a broker that lost its definitions) is at CONTAINER level.
- **AC13's replay after an upgrade** was not run end to end. The version rule and the replay are each tested; their combination is not.
- Single broker node: quorum failover between nodes is not exercised.
- Handler-span continuity is read from the logging context. Span export is not asserted here.

## Trial history

The first trial passed 63/63 on the code Codex then rejected with 18 findings, none on paths the run exercised. It is why the closing
runs are on `5cafcf3`, after two review rounds and two verification passes. Harness defect fixed on the way: a run needs `mvn clean`
after a revert, because a stale `jacoco.exec` fails the repository build (HI-0003).
