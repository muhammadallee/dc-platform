# Phase 3 — Database / Data Access (Oracle): closure

**Verdict: PENDING owner acceptance** of the items in *Owner acceptance* below. Earlier owner decisions:
Oracle only (HD3 amended), per-database starters (HD12, 2026-10-28), prod on 19c (HD13, 2026-10-28),
configurable read timeouts (HD14, 2026-10-30).

| Item | Value |
|---|---|
| Phase start / final code commit | `60afe86` / `29c6ed2` (consumer 3×, Phase 1 and Phase 2 regressions, golden path and downstream compile on `29c6ed2`) |
| Modules changed | data-jpa-autoconfigure, new `platform-starter-data-jpa-oracle`, errors-autoconfigure (data error mapping), BOM, archetype (`-Ddatabase`), golden path, hardening harness, docs |

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A scope + baseline | done; owner decisions HD3 (Oracle only), HD12, HD13; amendments A1 (AC10), A2 (R4 logging, pending) | [`scope.md`](scope.md) |
| B implement + targeted tests | Oracle starter, archetype `database`, data errors 409/503 (D111), bounded waits (D110), Flyway pins + out-of-order guard (D112), `Instant` = TSTZ (D114) | `evidence-raw/gateB-errors-data-verify.log` |
| C round 1 | 6 findings, all fixed (F4 at Medium, F2 with the `{vendor}` residual); AC8 conceded and fixed | [`review-round-1.md`](review-round-1.md) |
| D round 2 | R2-F1 fixed (started and suspended pools); R2-F2, R2-F3 Medium residuals | [`review-round-2.md`](review-round-2.md) |
| E register | HI-0051 … HI-0067; HI-0041 fixed | [`../issue-register.md`](../issue-register.md) |
| F consumer | three closing runs **92/92** each on `29c6ed2`; trials found HI-0068, HI-0069, HI-0070; verification passes 1–3 on the fixes (HI-0071) | [`consumer-run.md`](consumer-run.md), [`evidence/gate-f-closing-1..3`](evidence/) |
| F regressions | Phase 1 consumer **92/92**, Phase 2 consumer **75/75** (DB-down now `503 DC-DATA-0503`) on `29c6ed2` | [`evidence/phase-01-regression-gate-f`](evidence/phase-01-regression-gate-f/), [`evidence/phase-02-regression-gate-f`](evidence/phase-02-regression-gate-f/) |
| Targeted tests | errors 77; data-jpa 59 unit/slice + **44 Oracle container** tests; 0 failures, 0 skipped, JaCoCo gates met | `evidence-raw/f-verify2-green.log`, `evidence-raw/hi0070-docker.log` |
| Downstream (§3.9) | `-amd test-compile` of errors-autoconfigure and data-jpa-autoconfigure: 12 modules (every dependent), exit 0, sha256 `1bd34237…` | `evidence-raw/downstream-compile-29c6ed2.log` |
| Golden path | **9/9** on `29c6ed2` (65–95 s per scenario; the run's wall-clock total included a host sleep), `data-oracle` included | [`evidence/golden-path-summary-29c6ed2.tsv`](evidence/golden-path-summary-29c6ed2.tsv) |
| G composition | N/A (checkpoints at Phases 5 and 7) | — |
| H samples + operations | written | [`../../samples/data-access/`](../../samples/data-access/) |
| CI (PR #2) | green on `29c6ed2` (run 41037411881) and `3eda647` (run 41050159057); re-confirmed on the closure commit before tagging | GitHub Actions |

## §7 contracts

| § | Result |
|---|---|
| 7.1 override | by name: `platformOracleConnectionDefaults`, `platformOracleFlywayConnectionDefaults`, `platformFlywayOutOfOrderGuard`, `platformAuditorAware`, `dataJpaCapabilityDescriptor` (GX-04 guard, HI-0058); by type: `FlywayPresenceCheck`; an application `@ExceptionHandler` wins over the data mapping |
| 7.2 default snapshot | `PlatformDataJpaEnvironmentPostProcessorTest.configDefaultSnapshot`, `PlatformDataJpaAutoConfigurationTest.propertiesDefaultSnapshot` |
| 7.3 kill switch | `dc.platform.data.jpa.enabled`: `DEFAULT_ON`, tested (all platform data beans absent) |
| 7.4 outage posture | down: fast 503, other requests unaffected, recovery without restart; slow: bounded waits, no pile-up; silent: read-timeout 503; pool exhaustion: 503 after 5 s |
| 7.5 health | readiness/liveness pod-local; database in `dependencies` (DOWN while Oracle is down) |
| 7.6 propagation | problem bodies and the single platform WARN carry the inbound `traceId` |
| 7.7 error surface | 409 `DC-DATA-0409` / `DC-DATA-0410`, 503 `DC-DATA-0503` (retryable); fixed detail, no ORA-/table/column/constraint names in bodies; lost connection while committing → non-retryable 500 |
| 7.8 redaction | data error detail is fixed text; the WARN goes through the Phase 2 masks; no database password in evidence |
| 7.9 observability | Hikari and Spring Data repository meters; no reference values or SQL in label values |
| 7.10 graceful shutdown | in-flight transaction completes and commits, pool shut down, exit 143 |
| 7.11 startup | 68–87 s to ready (cold image start included); slow Oracle 24–28 s; down: exit in 8–11 s; silent peer: exit in 608–610 s (Flyway's 10 min read timeout) |

## Consumer-visible changes

See CHANGELOG `## [Unreleased]`, phase 3:
- data errors are 409/503 problems instead of a generic 500;
- bounded waits: 5 s pool wait, 30 s transactions and out-of-transaction queries, Oracle read timeout 60 s on
  the pool and 10 min on Flyway's own connection, connect timeout 10 s — all configurable;
- Flyway naming is validated and out-of-order migrations fail outside `local`;
- `platform-starter-data-jpa-oracle` and the archetype's `-Ddatabase=oracle`.

## Owner acceptance (PENDING)

1. **Scope amendment A2** (HI-0069): "logged once on the request's trace" means the platform's own WARN;
   Hibernate's two statement lines stay on by default.
2. **HI-0064**: a `{vendor}` Flyway location with a separate Flyway user, and a `Flyway` bean built from a URL,
   get no Oracle driver bounds by default (JVM system properties are the workaround).
3. **HI-0065**: Hibernate `SelectionQuery` and raw-delegate queries take the 30 s query default instead of the
   remaining transaction time (`.setTimeout(…)` is the workaround).
4. **HI-0066**: the PL/SQL transaction-cancel miss (seen once, not reproduced); the read timeout bounds it.
5. **HI-0071**: the commit veto recognises the commit by a stack frame (Codex rejects); the supported
   `JpaTransactionManager` path is covered; alternative: explicit outcome evidence through a
   transaction-manager hook, possibly with Phase 4's outbox work.

## Open (non-blocking)

- HI-0057: Oracle RAC failover `BLOCKED` (HD4).
- HD13: certified on 23ai Free in 19 mode, not on 19c itself.
- D110: Phase 4's outbox drainer inherits the 30 s transaction default unless it sets its own.
- HI-0004 (book narrative).
