# Phase 4 — Gate F consumer run (Oracle + RabbitMQ)

| Item | Value |
|---|---|
| Harness | `tooling/hardening/scenarios/phase-04-outbox.sh` (probe and migration in `phase-04/`); final at `6bbf91c` |
| Consumer | archetype `-Dfeatures=data,messaging -Ddatabase=oracle`, `prod` profile; the outbox adopted as `outbox.md` says (starters added, in-memory transport swapped for RabbitMQ, DDL copied as `V2`), plus the probe and a `V3` migration |
| Infrastructure | `gvenzl/oracle-free:23-slim-faststart` (Hibernate pinned to 19, HD13), `rabbitmq:3.13-management-alpine`, Toxiproxy 2.9.0 in front of both; digests in each `run-identity.txt` |
| Users | schema owner `HH_OWNER` runs Flyway; runtime `HH_RT` holds only role `HH_RW` (DML) |
| Settings | Drainer timings shortened through documented properties (`app-env.txt`): poll 200 ms, backoff 200 ms to 2 s, `max-rejections` 2. Batch size, pool size and confirm timeout at their defaults |
| Closing code | `03dee13` — three runs, **106/106 each** (`evidence/gate-f-closing-{1,2,3}`). Run 1 predates the harness commit `6bbf91c` but has the same content, so its identity lists two more dirty paths |
| Regressions on the same code | Phase 1 **92/92**, Phase 2 **75/75**, Phase 3 **92/92** (`evidence/phase-0{1,2,3}-regression-gate-f`) |
| Committed evidence | assertions, HTTP bodies, run identity, pool samples; not the raw app logs or full scrapes (kept under `~/dc-hardening-runs/phase-04/`); scanned for tokens, credentials and PII: no hits (`WWW-Authenticate: Bearer` response headers aside) |

## Acceptance criteria (CONSUMER level)

Delivery is counted from a table the handler writes, one row per delivery. Ranges are across the three closing runs.

| AC | What the run asserts | Result |
|---|---|---|
| AC1 | Commit delivers; rollback leaves no business row, no outbox row, no delivery | pass |
| AC5 | RabbitMQ down: 30 requests all 200; pending metric 30, lag 23–29 s, nothing parked; recovery delivers all | 30 of 30, 0 duplicates |
| AC6 | RabbitMQ at 8 s latency (confirm timeout 5 s): 20 parallel requests all 200 in under 5 s; nothing parked | slowest 0.05–0.22 s; 20 of 20, 1 duplicate each run |
| AC7 | `SIGKILL` of one of two instances mid-drain; then of the only instance, followed by a new one | 300 of 300, 17–23 duplicates; 200 of 200, 23–30 duplicates (limit 50) |
| AC8 | Two instances, 500 events | 500 of 500, 0 duplicates, 250 + 250 |
| AC9 | Unroutable destination parks after `max-rejections`, metric reads 1, WARN logged, other rows flow, the documented replay sends it under the same id | pass |
| AC10 + §7.4 | Database lost mid-drain: request 503 within 12 s, liveness 200, drainer running, staleness metric 34–35 s, requests and drainer recover without a restart, nothing parked | 200 of 200, 15–19 duplicates (limit 50) |
| AC10 | Database slow (500 ms per response): a request completes in 3 s, drainer at most 2 connections, application pool pending 0 | 100 of 100, 0 duplicates |
| AC10 | RabbitMQ slow: drainer pool capped at 2, application pool pending 0 | pass |
| AC11 | `jvm_`, Hikari and outbox meters present with the common tags | pass |
| AC12 | Inbound trace and subject on the message; no token in a row; credential-named header refused and the business write rolled back | pass |
| AC13 | Disabled: WARN, publish after commit, no row. No table: startup fails naming the DDL | pass |
| AC14 | `SIGTERM` mid-batch: exit 143, restart delivers the rest | 150 of 150, 0 duplicates |
| AC15 | V2 applied by the owner; the runtime user's `DROP` refused with ORA-01031 | pass |
| AC16 | A relayed domain event is delivered and has an outbox row | pass |

Limits of this evidence: AC6's nack case and a blocked publish are below consumer level (`RabbitEventTransport` unit and container tests). AC12's "drainer log lines on the original trace" holds for failure logs only (round 1 F17: no tracing scope is opened). AC16's crash-after-commit case is carried by AC7, since the relay writes the same row.

## Findings from the runs

| ID | Found by | Disposition |
|---|---|---|
| HI-0083 | trial: the Rabbit starter lacked `spring-boot-amqp`, so no `RabbitTemplate` under Boot 4 | FIXED `3e5820c` |
| HI-0084 | trial: a non-static `eventHandlerRegistrar` cost a messaging service all its metrics and common tags | FIXED `26f8fbe` |
| HI-0085 | trial: the parked counter collided with the parked gauge in Prometheus | FIXED `26f8fbe` (`platform.outbox.parkings`) |

Harness defect fixed on the way (not a platform defect): `rabbitmq-diagnostics` run during broker boot created the Erlang cookie as root and crashed the broker; the harness now waits for the log line.

## Trial history

An earlier set of three runs on `03dee13` passed 79/79 each but connected to Oracle directly, so the drainer never met a database fault at this level. That gap was found while drafting the closure; the scenario was added (`6bbf91c`) with no platform change, and all three closing runs were repeated. Those earlier runs remain under `~/dc-hardening-runs/phase-04/` (`outbox-20261101-002456`, `-004426`, `-010653`).

Two trials were stopped by Claude Code's host-memory check before the app scenarios ran (one aborted when Oracle's container exited during startup; cause not established). The closing runs were run in the foreground, one at a time. `nice_galileo` was stopped during consumer runs.
