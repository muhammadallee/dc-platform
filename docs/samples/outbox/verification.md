# Verification

Evidence levels (plan §5.3): UNIT, SLICE, CONTAINER (Testcontainers Oracle Free 23ai in 19 mode, real RabbitMQ), CONSUMER
(archetype-generated service in `prod` against real Oracle and RabbitMQ). Oracle 19c itself and RAC were not available (HD13,
HD4).

Final code `03dee13`: consumer three runs **106/106** each; Phase 1 consumer 92/92, Phase 2 75/75, Phase 3 92/92 on the same code.
The duplicates below are the range across the three runs.

| Claim | Expected | Actual (consumer) | Level |
|---|---|---|---|
| Atomic with the business write | commit delivers; rollback leaves no business row, no outbox row, no message | as expected; a deferred constraint failing at commit leaves no row | SLICE + CONTAINER + CONSUMER |
| No transaction | `publish` refuses | also for another database's transaction, a connection only primed there, and anywhere under `afterCommit` | SLICE |
| Service keeps its pool and transaction manager | no extra `DataSource` or transaction manager bean | one of each, `JpaTransactionManager` | SLICE |
| Concurrent claiming | two claimers each get a full batch of different rows | batch sizes 1, 7, 10, with 200 KB payloads, as a runtime user | CONTAINER |
| RabbitMQ down | requests unaffected, backlog visible, recovery delivers all, nothing parked | 30 requests all 200; pending 30, lag 23–29 s; 30 of 30 delivered, 0 duplicates, 0 parked | CONSUMER |
| RabbitMQ slower than the confirm timeout | requests unaffected, all delivered | 20 parallel requests all 200 in under 5 s; 20 of 20, 1 duplicate (a timed-out send that arrived anyway) | CONSUMER |
| Unroutable destination | parked after `max-rejections`, metric, WARN; others flow; replay keeps the id | as expected | UNIT + CONTAINER + CONSUMER |
| Two instances | none lost, none twice, work shared | 500 of 500, 0 duplicates, 250 + 250 | CONSUMER |
| `SIGKILL`, another instance alive | none lost, duplicates ≤ one batch (50) | 300 of 300, 17–23 duplicates | CONSUMER |
| `SIGKILL` of the only instance, then a new one | none lost, duplicates ≤ 50 | 200 of 200, 23–30 duplicates | CONSUMER |
| `SIGTERM` mid-batch | batch ends after the send in flight, exit 143, none lost or repeated | 150 of 150, 0 duplicates | CONSUMER |
| Database lost mid-drain | requests refused fast, liveness up, drainer survives, recovery without a restart, none lost, duplicates ≤ 50, nothing parked | 503 within 12 s; 200 of 200, 15–19 duplicates; backlog figures report 34–35 s stale | CONSUMER |
| Database slow (500 ms per response) | requests bounded, drainer on its own pool, none lost | a request took 3 s; drainer at most 2 connections, application pool pending 0; 100 of 100, 0 duplicates | CONSUMER |
| Drainer isolated from requests | own pool, capped; no request waits on the application pool | `platform-outbox` max 2; application pool pending 0 | SLICE + CONSUMER |
| Retention | deletes old sent rows only, in bounded batches | on a no-auto-commit source too | CONTAINER |
| Trace and principal | on the message; no token stored | inbound trace on the message; subject id, not a token; credential-named header refused, and the business write rolls back | SLICE + CONSUMER |
| Runtime user | DML only; Flyway applies the DDL as the owner | `DROP` refused with ORA-01031; V2 by the owner | CONTAINER + CONSUMER |
| Disabled | WARN, no drainer, publish after commit | as expected, no row written | SLICE + CONSUMER |
| Missing table | clear startup failure naming the DDL | as expected | SLICE + CONSUMER |
| Relay | writes through the outbox inside the transaction | delivered, with an outbox row | SLICE + CONSUMER |
| Rabbit confirms | nack, unroutable, timeout each fail the send | real broker: unroutable, missing exchange; unit: nack classes, blocked publish, timeout | UNIT + CONTAINER |
| Metrics | backlog meters, common tags, bounded labels | `jvm_`, pool and outbox meters all with `service=`; outbox meters carry no IDs | UNIT + CONSUMER |

Not verified: RabbitMQ under a real resource alarm (a blocked publish is unit-tested with a stub); Oracle 19c itself; RAC. See
[`open-issues.md`](open-issues.md).
