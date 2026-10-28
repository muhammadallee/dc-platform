# Hardening decisions

IDs are `HD<n>` (the plan's `D<n>`), kept separate from the platform decision log (`D1`–`D88`).
Owner of every decision, exception and in-scope capability: **repository owner** (HD10).

| ID | Decision | Answer | Status |
|---|---|---|---|
| HD1 | Audit in scope? | **No.** Audit and all its sinks are not certified and appear in the final "Not certified" list with a compliance warning. | ANSWERED |
| HD2 | Idempotency store | **None → idempotency removed from scope.** A log file cannot provide an atomic insert-if-absent shared across pods. The REST client never auto-retries non-idempotent methods; RabbitMQ consumers dedupe themselves (documented pattern, not certified). | ANSWERED |
| HD3 | Supported databases | **Oracle** certified at CONTAINER + CONSUMER. Amended by the owner 2026-10-28: PostgreSQL is **out for now** (not certified; added later separately). Was: Oracle + PostgreSQL. | ANSWERED (amended 2026-10-28) |
| HD4 | Oracle RAC | Production uses RAC; **no non-production RAC exists**. Single-instance Oracle certified; RAC failover (FCF/ONS, Application Continuity, UCP vs Hikari) is `BLOCKED` and a release-relevant gap. | ANSWERED — RAC `BLOCKED` |
| HD5 | Correlation ID policy | **W3C `traceparent` only.** Trace ID is the sole correlation ID; no `X-Correlation-Id` inbound or outbound. ProblemDetail error bodies carry `traceId`. | ANSWERED |
| HD6 | Readiness policy | Readiness = pod-local state only. DB, RabbitMQ, JWKS, downstream APIs move to a separate dashboard health group. Liveness checks nothing external. | ANSWERED |
| HD7 | OpenShift for Phase 8 step 7 | **Yes**, via the team's existing Python chart/pipeline tool. Tool location, invocation and namespace access are asked when Phase 8 step 7 is reached. | ANSWERED — details `PENDING` |
| HD8 | Pause after each phase | **No.** Mandatory stops (§4.6) still apply. | ANSWERED |
| HD9 | Principal claim for idempotency | **N/A** (idempotency removed). `sub` would suffice later. | ANSWERED |
| HD10 | Owner | Repository owner owns all exceptions and in-scope capabilities. | ANSWERED |
| HD11 | Transactional outbox implementation (new module, plan §3.6) | **Deferred to Phase 4 Gate A**: present a design sketch (platform JDBC outbox module vs Spring Modulith event publication registry) and ask then. | DEFERRED (owner, 2026-10-26) |
| HD12 | Database driver + Flyway module for generated services (HI-0041) | **Per-database POM-only starters**; only `platform-starter-data-jpa-oracle` (JDBC driver + `flyway-database-oracle`) in Phase 3. Other databases are added later, separately. | ANSWERED (owner, 2026-10-28) | | HD13 | Oracle version | **Production runs 19c.** Container certification runs on Oracle 23ai Free (no free 19c image) with the platform restricted to 19c features (Hibernate told the database is major version 19); 19c itself is recorded as not container-verified. | ANSWERED (owner, 2026-10-28) |

## Conventions agreed with the owner (2026-10-26)

Where the plan conflicts with repository rules (`CLAUDE.md`), these resolutions apply (plan §13.5):

| ID | Conflict | Resolution |
|---|---|---|
| HC1 | Plan decision IDs `D1`–`D10` collide with the platform decision log | Renamed `HD1`–`HD10` (this file). |
| HC2 | Plan commit format `phase-03: gate B — …` vs CLAUDE.md conventional commits | Conventional commits with a gate trailer, e.g. `feat(core): … [hardening phase-01 gate B]`. |
| HC3 | CLAUDE.md "root `mvn -T1C verify` after every commit" vs plan §3.2 (no full-reactor test runs before Phase 8) | Claude runs targeted `-pl … -am` builds only; the owner runs root `mvn -T1 verify` when wanted; CI runs it on every PR. Phase 8 runs it under supervision. |
| HC4 | Compatibility baseline repository | `~/.m2-baseline` populated by the baseline golden-path run (`GP_MAVEN_REPO=~/.m2-baseline`). Deviation from the agreed "seed by copying `~/.m2`": a cold repository resolved from Maven Central was used instead, which also proves the baseline resolves without stale local artifacts. |
| HC5 | Graceful-shutdown evidence on Windows (`Process.destroy()` is a hard kill, no SIGTERM) | The consumer harness runs the generated app in a Linux container; `docker stop` delivers a real SIGTERM and waits the grace period (matches OpenShift). |
| HC6 | Merging PRs | Merge without asking once the task is complete and CI is green (owner, 2026-10-26). |
