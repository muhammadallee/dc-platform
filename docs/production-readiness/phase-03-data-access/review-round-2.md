# Phase 3 — Review round 2 (Gate D)

| Item | Value |
|---|---|
| Reviewed commit | `db83fae` (whole phase diff from `60afe86`, `docs/production-readiness` excluded; isolated worktree) |
| Verbatim I/O | [`codex/r2-review.prompt.md`](codex/r2-review.prompt.md) → [`codex/r2-review.out.md`](codex/r2-review.out.md); response [`codex/r2-response.md`](codex/r2-response.md); re-evaluation [`codex/r2-reassess.prompt.md`](codex/r2-reassess.prompt.md) → [`codex/r2-reassess.out.md`](codex/r2-reassess.out.md) |
| Codex scope note | Static review (no JVM in Codex's sandbox). Claude's response is backed by Oracle container measurements (`evidence-raw/r2-native-session*.log`, `evidence-raw/r2-f1-red.log`) |
| First attempt | Stopped by the Codex usage limit with no output (`evidence-raw/r2-review.err` was overwritten by the successful rerun) |

## Round-1 dispositions (Codex)

F3, F4, F5 and F6 are confirmed. The AC2, AC3, AC7, AC8, AC9, AC10 (Boot-created sources), AC11, AC12, AC15 and AC16 dispositions are confirmed too. F1 and F2 are partial, because of R2-F2 and R2-F3. The F2 `{vendor}` concern is **not fixed**: correct, it was carried as a residual for Gate E in round 1.

## Findings

| # | Finding | Severity | Claude | Re-evaluation | Disposition |
|---|---|---|---|---|---|
| R2-F1 | Oracle driver defaults crash a pool the application started itself (`new HikariDataSource(config)`) | High | Agree. Reproduced: `IllegalStateException: The configuration of the pool is sealed once started` fails startup | maintain concern: a **suspended** pool reports `isRunning()=false` but is still sealed | FIXED — skip any pool whose `getHikariPoolMXBean()` is set (running, suspended) or that is closed. Unit tests for the started and suspended cases, plus a context test showing startup succeeds with the application's value unchanged. `data.md`: set both properties on your own config |
| R2-F2 | The global query default can override an explicit transaction timeout | High → **Medium** | Partly agree. Measured per path: repositories, the injected `EntityManager`, and `unwrap(Session.class)` (which returns Spring's shared proxy) with `createQuery`/`createNativeQuery` keep the transaction time. `Session.createSelectionQuery` and raw `getDelegate()` queries take the query default (5.0 s in both the 1 s and the 6 s transaction) | accept, Medium; the measurements fit the Spring and Hibernate source | RESIDUAL (Gate E). The statement stays bounded (30 s default, 60 s read timeout). Documented in `data.md`, the CHANGELOG and the D110 round-2 addendum, with `.setTimeout(…)` on the query as the workaround (a fresh budget, not the remainder). Pinned in `OracleTimeoutDefaultsIT`, with the exception chain and a timing window for every path |
| R2-F3 | A `Flyway` bean the application builds from a URL gets no Oracle network bounds | High → **Medium** | Agree on the gap. That bean makes Boot's Flyway back off, and its data source is a Flyway-internal `DriverDataSource` | accept, Medium | RESIDUAL (Gate E). Documented: set the two JVM system properties; `OracleFlywayPostureIT` verifies they bound a driver connection |
| test | `OracleTimeoutDefaultsIT` did not pin every row of the measurement table | — | Agree. An edit had dropped the 6 s timing assertion | (raised in re-evaluation) | FIXED — exception chain plus a distinguishing timing window per outcome; the misleading "native session" name corrected |

## Acceptance criteria after round 2

| AC | Status |
|---|---|
| AC10 / R9 | Pools started by the application (running, suspended or closed) are left alone and tested. Flyway-created data sources are a documented exclusion |
| AC14 / R10.2 | Codex maintains: two connections are not bounded by default. One is the `{vendor}` detection connection with a separate Flyway user, which Boot creates. The other is a URL-built `Flyway` bean of the application's own. Both are accepted residuals with the JVM system-property workaround (verified), registered at Gate E. The default bound otherwise holds for the pool and for Boot's separate Flyway connection |
| R2.3 / §7.4 | Codex maintains: the transaction budget is not inherited by `SelectionQuery` or raw-delegate queries. This is an accepted Medium residual, documented and pinned, registered at Gate E. Every Spring Data and standard JPA `EntityManager` path keeps the budget (measured) |
| Others | Unchanged from round 1 (Gate F items pending) |

## Targeted tests after the fixes

| Command | Result |
|---|---|
| `mvn -o -T1 -Pdocker -pl errors/platform-errors-autoconfigure,data/platform-data-api,data/platform-data-jpa-autoconfigure,data/platform-starter-data-jpa-oracle -am verify` | BUILD SUCCESS: errors 73, data-api 11, data-jpa 55 unit/slice + 44 Oracle container tests (`evidence-raw/r2-errors-data-verify.log`). The first attempt was stopped by host memory pressure and rerun in the foreground |
| `mvn -o -T1 -pl data/platform-data-jpa-autoconfigure -amd test-compile` | BUILD SUCCESS, 9 modules (`evidence-raw/r2-downstream-compile.log`) |
| After the re-evaluation (suspended pool, tightened windows): `mvn -o -T1 -Pdocker -pl data/platform-data-jpa-autoconfigure verify` | BUILD SUCCESS: 56 unit/slice + 44 Oracle container tests, coverage gate on (`evidence-raw/r2-reassess-fixes.log`) |

## Carried to Gate E

- R2-F2: Hibernate `SelectionQuery` and raw-delegate queries take the query default instead of the remaining transaction time.
- R2-F3: a URL-built application `Flyway` bean gets no Oracle driver bounds.
- From round 1: the `{vendor}` Flyway location residual, and the PL/SQL cancel miss.
