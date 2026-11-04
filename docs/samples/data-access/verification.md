# Verification

Evidence levels (plan §5.3): UNIT, SLICE, CONTAINER (Testcontainers Oracle Free 23ai with Hibernate pinned to
19), CONSUMER (archetype-generated service in `prod` against Oracle). Real Oracle 19c and RAC were not
available: 19c behaviour is approximated (HD13) and RAC failover is `BLOCKED` (HD4).

Final code `29c6ed2`: Oracle consumer three runs **92/92** each; Phase 1 consumer 92/92 and Phase 2 consumer
75/75 on the same code; errors 77 tests, data-jpa 59 unit/slice + 44 Oracle container tests; CI green.
Details: [`consumer-run.md`](../../production-readiness/phase-03-data-access/consumer-run.md).

| Claim | Expected | Actual (consumer, three runs) | Level |
|---|---|---|---|
| Oracle starter | driver + Flyway Oracle module on the classpath; `prod` boots and migrates as the owner | dependency tree has both; `flyway_schema_history` V1, V2 by `HH_OWNER`; ready in 68–82 s | CONTAINER + CONSUMER |
| Constraint violations | 409 `DC-DATA-0409`, `retryable: false`, on the trace, no ORA-/table/column/constraint names, one platform WARN with the detail | 5 kinds × repository and `EntityManager`: all as expected; duplicate not stored | SLICE + CONTAINER + CONSUMER |
| Pool exhausted | requests beyond the pool get 503 within the 5 s connection timeout | 20 parallel 8 s transactions, pool 10: exactly 10 × 200, 10 × 503 `DC-DATA-0503` within 10 s; pending back to 0 | CONTAINER + CONSUMER |
| Oracle slow | bounded waits, 503s, no thread pile-up, recovery | 40 s latency: every request 200 or 503 within 75 s, ≥ 10 fast 503s; live threads +10; recovery < 90 s | CONSUMER |
| Oracle down | DB requests 503 fast, others 200; readiness/liveness UP; `dependencies` DOWN; recovery without restart | as expected; DB 503 in ≤ 12 s; `dependencies` UP again < 60 s | CONSUMER |
| Oracle silent mid-transaction | ended by the driver read timeout, retryable 503 | 503 `DC-DATA-0503` after 63 s (60 s read timeout + 3 s hold); recovery < 60 s | CONSUMER |
| Runtime user cannot change the schema | `ALTER`/`DROP`/`CREATE` refused; app works through `CURRENT_SCHEMA` | ORA-01031 three times; app as `HH_RT` on schema `HH_OWNER` | CONSUMER |
| Meters | Hikari and repository meters, bounded tags | present; no reference value or SQL in any label | SLICE + CONSUMER |
| SIGTERM | in-flight transaction completes and commits, pool shut down, exit 143 | as expected (shutdown 4–9 s) | CONSUMER |
| Startup | slow: within budget; down: clear, bounded failure | slow (200 ms/answer) 24–28 s; down: exit 1 in 8–11 s naming the connection error; silent peer: exit 1 in 608–610 s (`ORA-17002 … Socket read timed out`) | CONSUMER |
| Flyway posture | out-of-order refused outside `local`; misnamed file, checksum mismatch, `clean` refused; long identifier names the file | as expected | SLICE + CONTAINER |
| Timeouts | transaction, query and read timeouts reach the driver; the transaction's remaining time wins inside it | measured per path (`OracleTimeoutDefaultsIT`); two Hibernate paths take the query default (HI-0065) | SLICE + CONTAINER |
| Dialect | `Instant`/`OffsetDateTime` round-trip across JVM and session zones; `''` is NULL; 19c-safe SQL | as expected (`OracleDialectIT`) | CONTAINER |

Limitations: see [`open-issues.md`](open-issues.md). Not verified: Oracle 19c itself (23ai Free in 19 mode), RAC
failover (HD4), the PL/SQL transaction-cancel miss (HI-0066; seen once, not reproduced).
