# Phase 3 — Gate F consumer run (Oracle)

| Item | Value |
|---|---|
| Harness | `tooling/hardening/scenarios/phase-03-data-access.sh` (probe, migration and checks in `phase-03/`) |
| Consumer | archetype `-Dfeatures=data -Ddatabase=oracle`, `prod` profile, unedited generated project plus the probe sources and one migration |
| Infrastructure | `gvenzl/oracle-free:23-slim-faststart` (Hibernate pinned to 19, HD13), Toxiproxy 2.9.0, a busybox `nc` silent peer; digests in each `run-identity.txt` |
| Users | schema owner `HH_OWNER` runs Flyway (`spring.flyway.user`); runtime `HH_RT` holds only role `HH_RW` (DML grants from the migration) and reaches the owner's tables through `CURRENT_SCHEMA` |
| Timeouts | all platform defaults (connection 5 s, transaction 30 s, query 30 s, Oracle read 60 s on the pool and 10 min on Flyway's connection, connect 10 s) |
| Closing code | `29c6ed2` — three runs, **92/92 each** (`evidence/gate-f-closing-{1,2,3}`) |
| Regressions on the same code | Phase 1 consumer **92/92** (`evidence/phase-01-regression-gate-f`), Phase 2 consumer **75/75** with the DB-down check tightened to a retryable `503 DC-DATA-0503` (`evidence/phase-02-regression-gate-f`) |
| Committed evidence | assertions, checks, HTTP bodies, run identity; not the raw app logs (kept under `~/dc-hardening-runs/phase-03/`); scanned for credentials, tokens and planted PII |

## Acceptance criteria (CONSUMER level)

| AC | What the run asserts | Result (three closing runs) |
|---|---|---|
| AC1 | Dependency tree has the Oracle starter, ojdbc11 and `flyway-database-oracle`; boots in `prod`; `flyway_schema_history` has V1 and V2 succeeded, installed by the owner | pass; ready in 68–82 s |
| AC3 | Unique, foreign-key, NOT NULL, check and empty-string violations through the repository and the `EntityManager`: 409 `DC-DATA-0409` on the inbound trace, `retryable: false`, no ORA-, table, column or constraint name; duplicate not stored; exactly one platform WARN per trace with the ORA- detail (Hibernate's two statement lines aside, scope A2) | pass, 10 cases |
| AC4 | 20 parallel transactions holding a connection 8 s against a pool of 10: exactly 10 × 200, 10 × 503 `DC-DATA-0503` within 10 s; pending connections back to 0; next request 200 | pass |
| AC5 | 40 s latency on every Oracle response: 20 parallel requests, all 200 or 503 `DC-DATA-0503` within 75 s, at least 10 fast 503s (≤ 12 s); live threads grow < 50; recovery within 90 s | pass |
| AC6 | Proxy disabled: DB request 503 `DC-DATA-0503`, retryable, on the trace, ≤ 12 s; non-DB request 200; readiness and liveness 200; `dependencies` DOWN; recovery without restart within 60 s | pass |
| AC9 | Service connected as `HH_RT` with current schema `HH_OWNER`; as `HH_RT`, `ALTER`, `DROP` and `CREATE TABLE` all refused with ORA-01031 | pass |
| AC11 | Prometheus (token) has Hikari and Spring Data repository meters; no probe reference or SQL in any label value; anonymous scrape 401 | pass |
| AC13 | SIGTERM during an 8 s transaction: the request completes with 200, its row is committed, Hikari logs its shutdown, exit 143 | pass |
| AC14 | Slow Oracle (200 ms per response) at startup: ready in 24–28 s (recorded). Oracle down: non-zero exit in 8–11 s naming the connection error. Silent peer (TCP accepted, never answered) with the separate Flyway user: non-zero exit in 608–610 s, `ORA-17002 … Socket read timed out` (Flyway's 10 min read timeout; the connect timeout covers only the TCP connect) | pass |
| HI-0066 backstop | Oracle goes silent mid-transaction: the 60 s pool read timeout ends it, 503 `DC-DATA-0503` after 63 s, recovery within 60 s. Proves the mitigation; the PL/SQL cancel miss itself was not reproduced | pass |

## Findings from the runs

| ID | Found by | Disposition |
|---|---|---|
| HI-0068 | trial 2: silent Oracle mid-transaction gave a non-retryable 500 (Spring rethrew the failed rollback over Hikari's closed connection) | FIXED (`06d835a`), narrowed and a commit veto added after verification (`a5d6ae4`, `0bb109e`) |
| HI-0069 | trial 3: three WARN lines per constraint violation | default reverted after verification; scope amendment A2 (owner, Gate H) |
| HI-0070 | closing-code trial: a 60 s+ `CREATE TABLE` on a slow disk failed startup through the 60 s read timeout | FIXED (`aae722f`): owner decision HD14, configurable pool and Flyway read timeouts |
| HI-0071 | verification pass 3 rejected the stack-frame commit veto | OPEN, owner decision at Gate H |

Harness defects fixed on the way (not platform defects): an environment variable name that bound Hikari's
`connection` getter (`CONNECTION_INIT_SQL` must be `CONNECTIONINITSQL`; troubleshooting entry for Gate H), quoted
lowercase Flyway history columns, `read` at EOF under `set -e`, and the silent-start case: through Toxiproxy,
Oracle's listener closes an unfinished handshake after about 60 s, so a peer that never closes was needed to
measure the client bound.

Trial history: `evidence/gate-f-run1` is the first clean run on `764dc14` (before the verification changes).
One trial was stopped by host memory pressure before its app started.

Environment note: the reusable test Oracle (`nice_galileo`) was stopped during consumer runs for memory and
restarted for the container suite.
