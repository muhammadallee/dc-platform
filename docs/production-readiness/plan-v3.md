# DC Platform — Production Capability Hardening Execution Plan (v3)

**Repository:** `muhammadallee/dc-platform`
**Starting branch:** `main` — record HEAD SHA as the **baseline commit** before any edit.
**Implementer:** Claude Code. **Independent reviewer:** Codex.
**Objective:** Production-harden a deliberately limited set of existing chassis capabilities, prove each one in a freshly generated consumer application, prove they work **together**, and produce an evidence-backed release decision. Preserve the existing api / spi / provider / autoconfigure / starter architecture. Do not expand to the full capability catalog.

> **Read this whole document before starting.** Section 4 (execution mechanics) contains tool constraints that will otherwise cause silent failures. Section 6 (Phase 0) ends in a **human decision gate**: do not start Phase 1 until it is answered.

---

## 1. Scope

### In scope (certified by this initiative)

| # | Capability | Notes |
|---|---|---|
| 1 | Core + Error Handling | Request context, correlation, ProblemDetail error contract |
| 2 | Logging + Observability | Structured logs, MDC, tracing, metrics, health groups |
| 3 | Database / Data Access | JDBC/JPA, pool, transactions, migrations, PostgreSQL + Oracle |
| 4 | Transactional Outbox | Prerequisite for guaranteed publish alongside a DB change. Audit routing is **out of scope** (D1) |
| 5 | Messaging — **RabbitMQ only** | Publish via outbox, consume, retry, DLQ, replay |
| 6 | REST Client + Resilience | Deadlines, retry classification, breaker, bulkhead |
| 7 | Security | JWT/Keycloak resource server, permissions, actuator protection |

### Explicitly out of scope (must not be claimed as certified)

- **Kafka** — retain code; must still compile and pass its existing unit tests (see §3.9). Never certify, never add to archetype samples.
- **Audit** (D1) — the audit capability and all its sinks are not certified. Services with regulated audit requirements must not rely on the platform audit capability for compliance until it is hardened.
- **Idempotency** (D2) — not certified. No durable shared store was chosen; a log file cannot provide the atomic claim idempotency requires. Consequence for consumers: the platform provides **no** server-side protection against duplicate execution of non-idempotent requests. Services that need it must implement it themselves (e.g. a unique business key / request-id constraint in their own schema).
- Cache (Caffeine/Redis), Redis module, locking, rate limiting, multi-tenancy data isolation, storage, files, flags, secrets, native image, schema-per-tenant.
- Any capability not in the table above.

The final release assessment must contain a **"Not certified"** section listing every out-of-scope capability that ships in the BOM, so no consumer mistakes "the platform passed hardening" for "every starter is production-ready."

---

## 2. Phase order

| Phase | Name | Depends on | Composition checkpoint |
|---|---|---|---|
| 0 | Baseline, tooling, decisions | — | — |
| 1 | Core + Error Handling | 0 | — |
| 2 | Logging + Observability | 1 | — |
| 3 | Database / Data Access | 1, 2 | — |
| 4 | Transactional Outbox | 3 | — |
| 5 | RabbitMQ Messaging | 4 | **C1: Core + Obs + DB + Outbox + RabbitMQ** |
| 6 | REST Client + Resilience | 1, 2 | — |
| 7 | Security | 1, 2 | — |
| 7 | ↳ composition | — | **C2: Security + REST + DB + Obs** (run at end of Phase 7) |
| 8 | Final full-project verification | 1–7 | Full golden path |

**Why this order (do not reorder without a recorded human decision):**
- Outbox precedes RabbitMQ because "guaranteed publish alongside a DB update" is impossible without it. v1 made it optional; for a customs platform it is not.
- Idempotency was removed (D2). Two consequences are binding for the remaining phases:
  - **REST client (Phase 6) never auto-retries non-idempotent methods** (POST, PATCH, non-idempotent custom verbs). There is no server-side store to make such retries safe.
  - **RabbitMQ consumers (Phase 5) receive duplicates** — the outbox is at-least-once. The platform guarantees a stable event id on every message; deduplication is the consuming service's responsibility, shown as a documented pattern (§9, Phase 5), not a certified capability.

---

## 3. Binding execution constraints

1. **Supervised execution, truthful status.** Every build, test, harness run, and Codex call must be awaited to completion with its real exit code captured. No `nohup`, no detached `&`, no fire-and-forget, no CI results substituted for local evidence. A service that must run while scenarios execute is started, exercised, stopped, and awaited by **one harness script or test** that returns a truthful exit status. (See §4.1 for how to handle commands that exceed the tool's foreground timeout.)
2. **No full-reactor test runs before Phase 8.** "Full-reactor test run" = `mvn verify`/`install` with tests at the repository root. Allowed in Phases 1–7: targeted `-pl … -am` builds with tests, targeted `install -DskipTests` **after** the same modules passed tests, archetype consumer builds, and the **downstream compile check** in §3.9.
3. **Sequential gates.** A phase starts only when the previous phase is `PASS` or `PASS WITH HUMAN-APPROVED EXCEPTIONS`. Never skip a gate silently.
4. **Two structured Claude–Codex rounds per phase** (Gates C and D), plus a bounded **verification pass** for material late changes (§5.5). Never fabricate, paraphrase-as-quote, or summarize-away Codex output. If Codex is unavailable, the phase is `BLOCKED`.
5. **No auto-waiver of Critical/High findings.** They block the phase unless a human approves a narrowly documented exception (§4.6). Disagreement is not resolution.
6. **Preserve architecture.** Existing BOM, parent POMs, api/spi boundaries, conditional autoconfiguration, kill switches, and `@ConditionalOnMissingBean` back-off stay. Prefer Spring / provider features over custom frameworks. A new module requires a recorded human decision.
7. **Evidence before status.** Every PASS/works/secure claim cites: command, exit code, evidence file path, and evidence level (§5.3). "Mocked" never gets reported as "real."
8. **No scope creep.** Shared foundations touched as dependencies are not counted as certified. Do not add starters to samples beyond §3 policy.
9. **Downstream breakage is caught early, not in Phase 8.** After any change to a module that other modules depend on, run a compile-only downstream check:
   `mvn -q -pl <changed-modules> -amd -DskipTests test-compile`
   This includes Kafka and other out-of-scope modules. A downstream compile failure is a defect in the current phase, not something to discover in Phase 8.
10. **Certified-capability regression.** If Phase N modifies a module certified in an earlier Phase M, re-run Phase M's targeted test suite and its archetype harness, and record the re-run in the issue register. If the change alters Phase M's contract, it is a material change (§5.5).
11. **Flakiness is failure.** Any concurrency, timing, or container test must pass **3 consecutive runs** (`-Dsurefire.rerunFailingTestsCount=0`). A test that passes on retry is a defect to be fixed or quarantined with an OPEN finding — never "passed."

---

## 4. Execution mechanics (read before Phase 0)

### 4.1 Long-running commands and the foreground timeout

Claude Code's foreground shell tool has a maximum timeout (default cap is believed to be ~10 minutes, configurable via the `BASH_MAX_TIMEOUT_MS` / `BASH_DEFAULT_TIMEOUT_MS` settings — **verify in Phase 0**). Container-backed suites, Oracle startup, and the Phase 8 reactor will exceed 10 minutes.

- **Before Phase 1**, confirm the effective maximum. If it is below 45 minutes, ask the human to raise it in Claude Code settings and stop until they confirm.
- Split long work into independently verifiable commands (per module, per test class group) rather than one monolith.
- If a command still cannot fit: run it under `run_in_background` **and** actively wait on it (Monitor / poll for the exit-code file) in the same session, writing stdout/stderr to a file and capturing the exit code via `; echo $? > <file>.exit`. This preserves the intent of constraint 3.1 (supervised, truthful exit status). Record in the evidence that this mode was used. Never end a turn while such a command is running unobserved.

### 4.2 Codex invocation

- Phase 0 must establish the exact invocation (expected: `codex exec` non-interactive; **verify**), its version, and model, and record them in `docs/production-readiness/README.md`.
- **Independence rule:** Codex's first-pass prompt in each round receives the phase `scope.md` (requirements + acceptance criteria), the diff against the phase start commit, and file paths to read. It does **not** receive Claude's self-assessment, test summaries, or conclusions. Claude's evidence goes in only at the rebuttal step.
- Save every Codex prompt and raw response verbatim under `docs/production-readiness/phase-XX-<slug>/codex/` (`r1-review.prompt.md`, `r1-review.out.md`, `r1-reassess.prompt.md`, …). The `review-round-N.md` files are the human-readable record and must link to these.
- Require from Codex per finding: severity (§5.1), file:line, concrete failure scenario, proof or reasoning, proposed fix.

### 4.3 Consuming the platform from a generated app

The archetype app resolves platform artifacts from the local Maven repository. Per phase:

1. Run the targeted tests for the changed modules (they must pass).
2. Install the minimum set needed by the consumer: parent POMs, BOM, the archetype, and the starters under test plus their upstream modules:
   `mvn -pl <bom>,<parents>,<archetype>,<starters> -am install -DskipTests`
3. Confirm the installed POMs are flattened correctly (no unresolved `${revision}`) — inspect one installed POM.
4. Generate **outside the repo**, e.g. `$HOME/dc-hardening-runs/phase-XX/<timestamp>/` using `mvn archetype:generate -DarchetypeCatalog=local …` with the archetype's real coordinates.
5. Use a dedicated local repository for consumer runs if the default one contains stale platform artifacts (`-Dmaven.repo.local=…`); record which was used.

### 4.4 Branches, commits, tags

- Working branch: `hardening/v1` off the baseline commit.
- One commit per gate at minimum (`phase-03: gate B — …`). Never squash away review history.
- Tag each closed phase: `hardening/phase-03-closed`. Phase 8 runs on the commit that carries the `phase-07-closed` tag plus any Phase 8 fixes.

### 4.5 Resumability

Maintain `docs/production-readiness/STATE.md`, updated at every gate transition: current phase, current gate, last commit, open blockers, pending human decisions, next action. On any restart or context loss, read STATE.md first and resume from it. Never infer state from memory.

### 4.6 Human interaction points

Stop and wait for a human (do not continue to other work in the meantime) when:
- Phase 0 decision record is complete (§6, step 9);
- a Critical/High finding cannot be fixed within the phase;
- a requested exception is needed (write it to `exceptions.md` as `PENDING`, with risk, blast radius, mitigation, expiry);
- a provider/environment is unavailable and the phase would become `BLOCKED`;
- ~~each phase closure~~ — disabled (D8 = false). Post the end-of-phase report (§12) and continue.

### 4.7 Evidence hygiene

- Raw logs go to `docs/production-readiness/phase-XX-<slug>/evidence/` (trimmed to relevant sections, max ~2 MB per file; full raw logs to a git-ignored `evidence-raw/` directory, referenced by path and SHA-256).
- Before committing any evidence, scan for secrets/tokens/JWTs/passwords and PII (`grep -E` for `eyJ`, `Bearer `, `password`, `secret`, connection strings). Redact and note the redaction.

---

## 5. Definitions

### 5.1 Severity

| Severity | Definition | Release effect |
|---|---|---|
| **Critical** | Data loss/corruption, silent audit loss, security bypass, cross-caller data exposure, duplicate execution of a non-idempotent business operation, or a defect that takes down all replicas | Blocks phase; human exception only |
| **High** | Incorrect behaviour under a realistic failure (outage, slowness, restart, concurrency), leaked internals in responses/logs, broken back-off/override, misleading health signal | Blocks phase; human exception only |
| **Medium** | Degraded operability or DX with a workaround; missing observability on a failure path | May be OPEN with owner + target date |
| **Low** | Cosmetic, docs, naming | May be OPEN |

### 5.2 Finding statuses
`FIXED` (code + test evidence) · `ACCEPTED` (disproved or non-applicable, with evidence) · `OPEN` (tracked: owner, mitigation, target) · `BLOCKED` (unverifiable: tool/environment/dependency). A Critical/High finding may be `OPEN` only with an `APPROVED` exception.

### 5.3 Evidence levels
`UNIT` · `SLICE` (Spring slice / ContextRunner) · `CONTAINER` (Testcontainers real provider) · `CONSUMER` (archetype-generated app, real run) · `ENVIRONMENT` (real OpenShift/real RAC/real Keycloak). Every claim states its level. Production-readiness claims for a provider require at least `CONTAINER` + `CONSUMER`.

### 5.4 Degradation states (used by every infrastructure phase)
For each backing dependency, test four states — not just "down":
1. **Healthy**
2. **Slow** — responds, but p99 latency ×20 (inject with Toxiproxy: `org.testcontainers:toxiproxy`)
3. **Erroring** — connection reset / error responses
4. **Unavailable** — refused / unreachable
…plus **recovery** from 2–4 back to 1. "Slow" is mandatory: slowness exhausts pools and is the state most likely to cascade.

### 5.5 Material change
A change after Round 2 is **material** if it touches `src/main` beyond trivial fixes, alters a public API/SPI, changes a configuration default, changes a posture, or changes a contract recorded in `scope.md`. Material changes get a **Codex verification pass** (single prompt: diff + the finding it addresses; Codex states accept/reject with reasons). This is not a third round and does not erase Rounds 1–2.

---

## 6. Phase 0 — Baseline, tooling, decisions

No production code changes in Phase 0 except the tracking docs and harness scaffolding.

1. **Repository baseline.** Record HEAD SHA, Java/Maven/Docker versions, module list (count), parent/BOM structure, archetype location and coordinates, existing test tags (`docker`, etc.), and current gates (enforcer, ArchUnit, japicmp config incl. `ignoreMissingOldVersion`, checkstyle, JaCoCo).
2. **Tooling verification** (record each with exit code):
   - JDK 25, Maven, Docker daemon reachable, Testcontainers can start a trivial container.
   - Images pullable: PostgreSQL, Oracle Free (`gvenzl/oracle-free`), RabbitMQ (management variant), Keycloak, Toxiproxy.
   - Codex invocation works and returns output (§4.2).
   - Foreground timeout limit (§4.1).
   - Archetype can be installed and can generate a project that compiles **at baseline** (no changes). If baseline generation fails, that is the first finding.
3. **Compatibility baseline.** Install the baseline commit's artifacts into a separate local repository (e.g. `~/.m2-baseline`) so japicmp and the config-default snapshot (§7.2) can compare against it in Phase 8. There is likely no published release; the baseline commit **is** the reference. Record this limitation.
4. **Capability inventory** for the 8 in-scope capabilities: modules (api/spi/provider/autoconfigure/starter), `@AutoConfiguration` classes, `@ConfigurationProperties` prefixes and defaults, kill-switch properties, existing tests/TCKs, docs, known defects.
5. **Downstream map.** For each in-scope module, list dependent modules (`mvn -pl X -amd` dry listing) so §3.9 checks are known in advance.
6. **Gap inventory** per phase against §7 and §8 criteria, each item tagged P0 (blocks production) / P1 / Deferred.
7. **Harness scaffolding.** Create a reusable foreground harness (script or JUnit-based) that: starts infra containers, starts the generated app, waits for readiness with a timeout, runs scenarios, captures responses/logs, stops the app (SIGTERM, then waits), verifies exit, tears down containers, and returns a single truthful exit code.
8. **Initialize tracking docs** (§10) and `STATE.md`.
9. **Decision record — STOP for human input.** Write `docs/production-readiness/decisions.md` with a recommendation for each item and wait for the human to answer:

| ID | Decision | Recommended default |
|---|---|---|
| D1 | Audit in scope? | **ANSWERED: No.** Audit not certified; listed in §1 out-of-scope and the final "Not certified" section. |
| D2 | Idempotency store | **ANSWERED: none → idempotency removed from scope.** A log file was considered and rejected: no atomic insert-if-absent, not shared across pods, so concurrent duplicates would both execute. |
| D3 | Officially supported databases | **ANSWERED: Oracle + PostgreSQL.** Both certified at CONTAINER + CONSUMER level. |
| D4 | Oracle RAC | **ANSWERED: production uses RAC; no non-production RAC environment exists.** Certify single-instance Oracle only (CONTAINER + CONSUMER). RAC failover behaviour (FCF/ONS, Application Continuity, UCP vs Hikari) is `BLOCKED` and must appear in the final assessment as a **release-relevant gap**: first exercise of node failover will be in production. |
| D5 | Correlation ID policy | **ANSWERED: W3C `traceparent` only.** The trace ID is the sole correlation ID. No `X-Correlation-Id` header inbound or outbound. Error ProblemDetail bodies include a `traceId` field (default — human may veto). |
| D6 | Readiness policy | **ANSWERED: recommended policy.** Readiness = this pod can serve (own state only). Shared dependencies (DB, RabbitMQ, JWKS, downstream APIs) excluded from readiness and exposed in a separate health group for dashboards. Liveness never checks anything external. |
| D7 | OpenShift environment for Phase 8 step 7 | **ANSWERED: Yes.** Deployment charts and pipeline are generated by the team's **existing Python tool** — use it; do not hand-write Helm charts or pipelines. **PENDING (asked at Phase 8 step 7):** tool location/repo, invocation, and target namespace/cluster access. |
| D8 | `PAUSE_AFTER_EACH_PHASE` | **ANSWERED: false.** Continue to the next phase automatically. The mandatory stops in §4.6 (Critical/High unfixable, exception needed, environment unavailable) **still apply**. |
| D9 | Principal claim for idempotency scope | **N/A** — idempotency removed. (Note for later: the standard `sub` claim would suffice; no custom claim needed.) |
| D10 | Named owner | **ANSWERED: the requesting human (repository owner) owns all exceptions and all in-scope capabilities.** Record as `Owner: repository owner` in every register entry. |

Phase 0 is complete when items 1–8 are recorded. **All decisions are pre-answered** except: D4 RAC environment availability (treated as unavailable → `BLOCKED` until supplied) and D7 chart-tool details (supplied by the human when Phase 8 step 7 is reached). Do **not** stop at the end of Phase 0 for these; record them in `decisions.md` and continue to Phase 1.

---

## 7. Cross-cutting contracts (every phase must satisfy these for its capability)

These come from failure modes that occur in compositions, not single capabilities. Each phase records how its capability satisfies each applicable contract, with tests.

1. **Real override, not annotation presence.** For every platform `@Bean`, a test in which a consumer configuration supplies its own bean and the platform's backs off. Fail if the platform class is `final`/has a package-private constructor in a way that blocks reasonable extension, or if autoconfiguration ordering makes the consumer's bean silently lose.
2. **Configuration-default snapshot.** A test that snapshots every `@ConfigurationProperties` default for the capability. A changed default fails the test until the snapshot is consciously updated and the change is recorded in the phase changelog. (japicmp cannot see default changes.)
3. **Kill-switch tier.** Classify the capability: `MANDATORY` (disabling it is a compliance violation — e.g. a regulated audit capability — none is in scope for this initiative, so expect no capability to qualify; disabling must fail startup with a `FailureAnalyzer` message unless an explicit, logged break-glass property is set), `DEFAULT_ON`, or `OPT_IN`. Test the `enabled=false` behaviour.
4. **Outage posture.** Declare and test the capability's behaviour in every §5.4 degradation state: fail-open / fail-closed / fail-durable. Record what the **request** returns, not just what the component does.
5. **Health semantics.** Liveness never depends on an external system. Readiness answers "can this pod serve" and does not include a shared dependency whose outage would mark **all replicas** unready simultaneously (per D6). Shared-dependency health is exposed for dashboards via a separate health group.
6. **Context propagation.** Correlation/trace ID, MDC, and security principal survive every thread or process boundary the capability introduces (executors, listener containers, retries, scheduled drains). MDC is cleared after each unit of work. Test with concurrency to detect bleed across requests. Where a boundary has no inbound context (e.g. a background drainer), the behaviour is explicit (context restored from the persisted record, or a defined system context) — never "whatever is left on the thread."
7. **Error surface.** All errors map to RFC 9457 ProblemDetail with a stable error code. No exception message, SQL, stack trace, or upstream body reaches a response unless explicitly marked safe.
8. **Redaction.** Logs, span attributes, and stored payloads (outbox rows, DLQ messages, captured error bodies) must not contain tokens, credentials, or listed PII fields. Allow-list approach where the platform controls field emission.
9. **Observability.** Every capability's own work emits an Observation (span + timer) with low-cardinality tags only. A test asserts no tag uses an unbounded value (IDs, keys, raw paths, user identifiers).
10. **Graceful shutdown.** On SIGTERM the capability stops accepting new work, completes or safely abandons in-flight work within the configured lifecycle timeout, and releases resources (pools, channels, consumers). Verified in the consumer harness, not only in unit tests.
11. **Startup under adversity.** The consumer app starts within budget with healthy infra **and** with the capability's dependency in the "slow" state. Record both times. A dependency outage at startup must produce a clear `FailureAnalyzer` message or a defined degraded start — not a hang.

---

## 8. Standard workflow for Phases 1–7

### Gate A — Inspect, baseline, scope
- Record phase start commit. Re-read Phase 0 inventory for this capability.
- Write `scope.md`: requirements, explicit exclusions, behaviour contracts, applicable §7 contracts, acceptance criteria (each testable), P0/P1/Deferred, environment prerequisites, how to reproduce current behaviour.
- Run the **current** targeted tests and record baseline results (including skipped counts) before changing anything.

### Gate B — Implement and test (targeted)
- Minimal cohesive changes; regression + negative + concurrency + degradation tests.
- Targeted runs only: `mvn -pl <modules> -am verify` (enable container-tagged tests where relevant).
- Downstream compile check (§3.9). Certified-capability regression (§3.10). Flakiness rule (§3.11).
- Record exact commands, exit codes, test counts, skipped counts.

### Gate C — Round 1 (Codex → Claude → Codex)
1. Codex independent review per §4.2 (no Claude conclusions in the prompt).
2. Claude responds to every substantive finding: agree / partly agree / disagree — with code references and test evidence. Challenge weak findings with evidence, not assertion.
3. Codex reassesses each: accept / reject / maintain concern.
4. Implement warranted fixes; targeted tests. Write `review-round-1.md`.

### Gate D — Round 2 (fresh review of revised implementation)
1. Codex reviews the **revised** code and new tests from the phase start commit, confirms Round 1 dispositions, hunts regressions.
2. Claude rebuts point by point with evidence.
3. Codex re-evaluates. Record unresolved disputes verbatim.
4. Fix substantiated defects; targeted tests; Codex verification pass for material changes (§5.5). Write `review-round-2.md`.

### Gate E — Reconcile findings
Update `issue-register.md`: ID, phase, severity, location, reproduction, Codex claim, Claude reply, evidence path, disposition, owner, mitigation, tracker link, fix commit, test reference. Critical/High not `FIXED`/`ACCEPTED` → stop for human (§4.6).

### Gate F — Isolated archetype consumer run
- Generate a fresh app (§4.3). Direct platform starters = capability under test + logging + data baseline (fixture-only where not yet certified: in Phase 1 logging and DB are fixtures; in Phase 2 DB is a fixture). Nothing else.
- The consumer uses only public APIs and documented configuration. No internal dependencies, no sample-specific workarounds. If a workaround is needed, that is a chassis/archetype defect: fix it, add a regression test, Codex-verify, re-run.
- Run the harness in the foreground: happy, negative, every §5.4 degradation state applicable, recovery, concurrency, graceful shutdown. Assert meaningful behaviour (response bodies, DB state, queue state, log fields), not only HTTP 200 or `UP`.
- Capture generation command, coordinates, dependency list (`mvn dependency:tree` of the consumer), run commands, responses, assertions, logs, exit codes, cleanup confirmation (`docker ps` shows no leftovers).

### Gate G — Composition checkpoint (Phases 5 and 7 only)
Generate a **second** app containing the checkpoint's full capability set (§2) and run cross-capability scenarios (§9, C1/C2). Composition failures found here are defects of the current phase.

### Gate H — Documentation and closure
Create `docs/samples/<slug>/`:
- `README.md` — what it solves, when to use / not use, minimal real setup, walkthrough.
- `commands.md` — exact, reproducible generate / setup / run / test / cleanup commands.
- `verification.md` — expected **and actual** results with evidence levels; limitations.
- `troubleshooting.md` — errors and fixes, each labelled **observed** (seen during this phase) or **anticipated**.
- `open-issues.md` — open items with impact, owner, mitigation, links.
- `operations.md` — **new:** posture table for this capability, health groups, key metrics/alerts to set, kill-switch tier and break-glass, graceful-shutdown behaviour, "how to replace bean X".

Plain, friendly developer language: what to type, what should happen, what to do if it doesn't. Never present an unexecuted command as tested.

Write `docs/production-readiness/phase-XX-<slug>/closure.md` with verdict `PASS` / `PASS WITH HUMAN-APPROVED EXCEPTIONS` / `BLOCKED`, update `capability-status.md` and `STATE.md`, tag the commit, then continue (D8 = false).

A phase is **complete** only when: built, tested (incl. degradation + flakiness rule), debated twice, findings dispositioned with approvals, isolated consumer run passed, composition checkpoint passed (5 and 7), samples + operations doc written.

---

## 9. Capability-specific criteria

### Phase 1 — Core + Error Handling
**Harden:** immutable request context (record; mutation = replace); correlation creation/propagation/cleanup per D5 (see below); inbound correlation/traceparent validation (format, length cap, reject CR/LF and control characters — header/log injection); stable error codes with build-time uniqueness; RFC 9457 ProblemDetail with `type` URIs; validation errors with field paths but no internal detail; generic exception handler that never leaks messages; `retryable`/`retryAfter` where applicable.
**Correlation contract (D5) — implement and test exactly:**
- The W3C trace ID is the only correlation identifier. Inbound: valid `traceparent` → continue that trace; missing or invalid → start a new trace (invalid value is not logged verbatim; log a sanitized, length-capped marker that it was rejected).
- Outbound (REST client, RabbitMQ headers via the outbox): propagate `traceparent` (and `tracestate` if present). No other correlation header.
- Logs: MDC `traceId` and `spanId` on every line.
- Error responses: ProblemDetail includes a `traceId` property so callers and support can quote it. No custom response header.
- **Existing behaviour change:** if the current codebase reads, writes, or propagates any other correlation header (e.g. `X-Correlation-Id`) or mints its own correlation ID, remove that path. This is a consumer-visible change: record it in the phase changelog and the final assessment, and add an OpenRewrite note if consumers reference the old header/type. If a `CorrelationId` type exists in public API, keep the type but source its value from the trace ID (do not break the API in this phase unless Codex and the human agree).

**Demonstrate:** success; malformed JSON; validation error; unknown exception (sanitized body, full detail only in logs); correlation: valid `traceparent` (trace continued); missing (new trace); malformed / oversized / CR-LF injection (new trace, rejected value never logged raw); a stray `X-Correlation-Id` sent by a caller is ignored — assert MDC fields, ProblemDetail `traceId`, and outbound `traceparent` for each; concurrent requests (≥200 parallel) with zero context bleed; `@Async` and executor propagation; MDC empty after each request. Logging and DB are **fixtures only**.

### Phase 2 — Logging + Observability
**Harden:** JSON structured logs with trace/span IDs; MDC lifecycle; redaction (allow-list for platform-emitted fields; tests for JWT, `Authorization`, password, listed PII); log injection neutralisation; Observation API as the instrumentation point; OTLP export conventions; Prometheus endpoint; metric tag-cardinality guard (§7.9); sampling policy that always records errored requests (or documents why not and marks OPEN); health groups per §7.5; actuator exposure minimal by default (health/info/prometheus only; others off). Note: actuator **authentication** is finalized in Phase 7 — record the interim posture.
**Demonstrate:** log ↔ request correlation; trace ID on inbound and an outbound test call; metrics present with expected tags and none unbounded; liveness/readiness behaviour; redaction proof; concurrent MDC cleanup; clean shutdown flushes logs. DB is **fixture-only**.

### Phase 3 — Database / Data Access
**Harden:** DataSource/pool defaults (Hikari: max pool, connection timeout, `leakDetectionThreshold` in non-prod, validation); per-statement and transaction timeouts; `spring.jpa.open-in-view=false`; read-only transactions on query paths; exception translation without leaking SQL; Flyway: `cleanDisabled=true`, `validateOnMigrate=true`, out-of-order forbidden outside dev, migration naming conventions; dialect-specific behaviour for PostgreSQL and Oracle (identifier length, sequences/identity, boolean mapping, timestamp/time zone, `LIMIT/OFFSET` vs `FETCH`); readiness per D6; documented separation of DDL role vs runtime role (verify runtime role cannot `ALTER`/`DROP` in the containers).
**Demonstrate (CONTAINER + CONSUMER, both databases):** CRUD with constraints; commit; rollback on exception; constraint violation → ProblemDetail without SQL; pool exhaustion (requests time out cleanly, pool recovers); all §5.4 states via Toxiproxy incl. **slow DB** (pool saturation behaviour, no thread pile-up beyond bounds); recovery; migration validation failure blocks startup with a clear message; graceful shutdown closes the pool. **H2 evidence never counts as PostgreSQL/Oracle certification.** RAC per D4.

### Phase 4 — Transactional Outbox
**Harden:** outbox write in the **same transaction and DataSource** as the business write; schema with event id (stable, generated once), type, version, payload, headers incl. correlation/trace and principal, created time, attempts, status; drainer as `SmartLifecycle` with bounded batch, `SELECT … FOR UPDATE SKIP LOCKED` (PostgreSQL and Oracle verified), backoff, max attempts → parked/dead state; **separate, bounded connection budget** for the drainer so it cannot starve request threads; at-least-once delivery with consumer-side dedupe by event id documented; retention/cleanup strategy that does not create unbounded table bloat; drain lag and parked-count metrics; context restored from the row when draining (§7.6); kill-switch tier `DEFAULT_ON` — disabling it must log a startup WARN stating publishes are no longer guaranteed. Outbox is a messaging-durability mechanism here, **not** an audit mechanism.
**Demonstrate:** business write + outbox row commit atomically; business rollback leaves no row; sink unavailable → rows accumulate, lag metric rises, no request failure, drain on recovery; **pod killed mid-drain** → no loss, duplicates bounded and dedupable by event id; concurrent drainers (2 app instances) don't double-claim; slow DB state; `enabled=false` behaviour per tier.

### Phase 5 — RabbitMQ Messaging (Kafka excluded)
**Harden:** topology conventions (quorum queues by default unless decided otherwise, durable exchanges, DLX per queue, naming); publishing **via outbox** for any publish tied to a DB change, direct publish only for documented non-guaranteed cases; publisher confirms (`correlated`) and returns (`mandatory=true`) handled — unroutable is an error, not silent; consumer acks (manual or container-managed, documented); bounded retry with backoff that does **not** block the consumer indefinitely; poison message → DLQ after limit (quorum `x-delivery-limit` or retry interceptor + `RejectAndDontRequeueRecoverer`, decide and document); prefetch defaults; structured headers (event id, type, version, correlation/traceparent, producer); context propagation publisher → consumer; schema version compatibility rule (consumer tolerates unknown fields; version header checked); consumer deduplication is **the consumer's responsibility** — the platform guarantees a stable, unique event id header on every message and documents the recommended pattern: record the event id in a processed-events table (unique constraint) in the **same transaction** as the consumer's business write, and skip on constraint violation. This pattern is demonstrated in the sample, not certified as a platform capability; DLQ replay procedure + tooling; broker connection recovery; graceful consumer stop (finish in-flight, no redelivery storm).
**Demonstrate (real RabbitMQ, CONTAINER + CONSUMER):** publish/consume end to end with trace continuity; handler exception → retries → DLQ; poison message; broker outage and **slow broker** (Toxiproxy) → publisher behaviour (outbox absorbs), consumer reconnect; duplicate delivery: (a) a naive consumer is shown to apply the effect twice — documented as the expected risk, and (b) the sample consumer using the documented pattern applies it once; unroutable publish surfaced; orderly stop/restart with in-flight messages; DLQ inspection and replay. Kafka modules untouched beyond §3.9 compile check.
**Composition checkpoint C1 (Gate G):** app with Core + Obs + DB + Outbox + RabbitMQ. Scenarios: HTTP request → DB write + outbox → drain → Rabbit → consumer → DB write, with one trace across all hops; DB slow while broker healthy; broker down while DB healthy; app killed mid-flow → no loss, bounded duplicates; drainer and request threads under load don't exhaust the pool.

### Phase 6 — REST Client + Resilience
**Harden:** named clients (`RestClient`), connect/read timeouts and an overall deadline; deadline propagation if implemented (`timeout = deadline − elapsed`) with a tested **exhausted-deadline** path; bounded error-body capture with redaction; correlation/trace propagation; pooling where justified; retry classification: idempotent methods retryable on transient errors; **non-idempotent methods are never auto-retried** (idempotency is out of scope — D2); a caller-supplied `Idempotency-Key` header is passed through unchanged if present but does **not** enable retries; exponential backoff with jitter; honour `Retry-After`; per-client circuit breaker with documented fallback and health/metrics; bulkhead — semaphore-based when virtual threads are enabled (reject thread-pool bulkhead with a startup failure message); documented and tested resilience aspect order.
**Demonstrate (WireMock or MockServer + Toxiproxy):** normal call; slow and no response; DNS/connection failure; 4xx not retried, 5xx retried within budget; large error body bounded; retries exhausted → ProblemDetail with `retryable`; breaker closed → open → half-open → closed with metrics; bulkhead saturation under concurrency; POST is never retried on transient failure (assert downstream received exactly one request), including when an `Idempotency-Key` header is present.

### Phase 7 — Security
**Harden:** resource server JWT validation: signature, `iss`, `aud`, `exp`, `nbf`, bounded clock skew; algorithm allow-list (reject `none` and algorithm confusion); JWKS fetch failure behaviour (cached keys used; unknown `kid` → reject; never fail open); key rotation; Keycloak role/permission claim mapping; path and method security; consistent 401 vs 403 as ProblemDetail without leaking reasons; actuator: health probes reachable by the orchestrator, everything else authenticated or disabled; Swagger/OpenAPI disabled or protected by default; consumer-provided `SecurityFilterChain` backs off the platform's (and the platform fails **closed** if misconfigured — no "security disabled because a bean was missing"); principal propagated into request context and async boundaries (§7.6); no token in logs/spans.
**Demonstrate (CONTAINER with Keycloak, or signed test JWTs + WireMock JWKS — state which):** valid token; expired; not-yet-valid; wrong issuer; wrong audience; bad signature; `alg=none`; unknown `kid`; JWKS endpoint down (cached keys vs fresh `kid`); key rotation; no token; insufficient permission; protected vs explicitly public endpoints; custom chain override; actuator exposure matrix; token absent from logs.
**Composition checkpoint C2 (Gate G, end of Phase 7):** app with Security + REST client + DB + Obs. Scenarios: authenticated request → DB write → outbound REST call, one trace across the chain with principal in context (not in logs); downstream returns 401/403/5xx → mapped to ProblemDetail without leaking downstream body or token; downstream 401 is **not** retried; breaker open on downstream while DB healthy; DB slow while downstream healthy; concurrent authenticated requests from two principals with zero principal bleed in context or MDC; graceful shutdown with in-flight outbound calls.

---

## 10. Phase 8 — Final full-project verification

Start only when Phases 1–7 are `PASS` or `PASS WITH HUMAN-APPROVED EXCEPTIONS`. All runs supervised per §3.1/§4.1.

1. Clean full-reactor `verify` with all normal gates (enforcer, ArchUnit, checkstyle, JaCoCo, japicmp). Record duration, test counts, skipped counts.
2. Full container suite for **in-scope providers only** (PostgreSQL, Oracle, RabbitMQ, Keycloak/JWKS, Toxiproxy). Audit and idempotency tests: run as part of the normal reactor only; outcome reported as "not certified". Kafka tests: run only if an existing build contract requires them; report outcome as "not certified" regardless.
3. Golden path + **fresh** archetype app with all 7 certified capabilities + cross-capability smoke matrix (C1 + C2 scenarios combined).
4. Security/dependency/license/secrets scans (e.g. OWASP dependency-check or OSV, CycloneDX SBOM, a secrets scanner), each finding dispositioned.
5. Compatibility against the Phase 0 baseline: japicmp on `*-api`/`*-spi`; config-default snapshot diff; BOM/parent resolution parity (platform chain vs `platform-service-parent` chain resolve the same versions for a pinned set of key artifacts — Spring Boot, Hibernate, Jackson, Lettuce, Spring AMQP, Resilience4j, Flyway, drivers). Every difference is listed and justified.
6. Real-infrastructure cross-capability workflows: RabbitMQ + DB via outbox; REST resilience + security; observability continuity across all boundaries.
7. OpenShift (per D7) — generate the deployment chart and pipeline with the team's Python chart tool, record its version and exact invocation, and treat any change needed in the generated output as a finding against the tool or the chassis, not a manual edit: readiness/liveness under dependency degradation (verify replicas are **not** all marked unready by a shared-dependency failure); graceful termination within `terminationGracePeriodSeconds`; connection draining; resource limits; restart; startup under slow dependencies. If no environment: `BLOCKED`, not skipped.
8. Release rehearsal: reproducible build check (two builds, compare artifact hashes), provenance, SBOM attached, version/tag procedure.

On failure: attribute, patch narrowly, Codex verification pass for material changes, re-run affected targeted + archetype runs, then re-run the failed Phase 8 step **and** step 1. Repeat until pass or report a blocker.

Write `run-results.md`, `release-decision.md`, and `final-release-assessment.md` (certified capabilities with evidence levels; exceptions; open issues; **Not certified** list; known limitations; recommended first-adopter conditions).

---

## 11. Deliverables layout

```text
docs/
  production-readiness/
    README.md                 # tooling versions, Codex invocation, how to read this folder
    STATE.md                  # live execution state (§4.5)
    decisions.md              # D1–D10 (D1, D2, D9 pre-answered)
    capability-status.md
    issue-register.md
    exceptions.md
    final-release-assessment.md
    phase-00-baseline/
      inventory.md
      gap-inventory.md
      tooling.md
    phase-01-core-errors/
      scope.md
      review-round-1.md
      review-round-2.md
      closure.md
      codex/                  # verbatim prompts + outputs
      evidence/               # trimmed, redacted logs
    phase-02-logging-observability/ ...
    phase-03-database/ ...
    phase-04-outbox/ ...
    phase-05-rabbitmq-messaging/ ...   # includes composition-c1.md
    phase-06-rest-resilience/ ...
    phase-07-security/ ...             # includes composition-c2.md
    phase-08-full-verification/
      run-results.md
      release-decision.md
  samples/
    core-errors/
    logging-observability/
    database/
    outbox/
    rabbitmq-messaging/
    rest-resilience/
    security/
    # each: README.md, commands.md, verification.md, troubleshooting.md,
    #       open-issues.md, operations.md
```
`evidence-raw/` directories are git-ignored.

---

## 12. End-of-phase report (posted to the human at each closure)

- Baseline and final commits, tag, modules changed and why.
- Targeted commands with exit codes, test/skip counts, flakiness reruns; downstream compile check result; certified-capability regressions re-run.
- Round 1: Codex findings, Claude responses, Codex reassessment (links to verbatim files).
- Round 2: same; verification passes for material changes.
- Issue table; unresolved disputes; human approvals needed.
- §7 contract checklist with evidence per item.
- Archetype generation command, coordinates, dependency tree summary.
- Harness scenarios incl. degradation states, actual outputs, shutdown and cleanup confirmation.
- Composition checkpoint results (Phases 5 and 7).
- Paths to docs and samples.
- Verdict: **PASS / PASS WITH HUMAN-APPROVED EXCEPTIONS / BLOCKED**, plus follow-up limitations and release blockers.

---

## 13. Start instruction for Claude Code

1. Read this document fully. Create `hardening/v1` from `main`; record baseline SHA.
2. Execute Phase 0 steps 1–8. Write `decisions.md` with D1/D2/D9 pre-filled as recorded in §6 and **stop** for the human to answer the remaining decisions.
3. After answers: update `STATE.md`, then execute Phase 1 through all gates.
4. Continue sequentially. Respect pause points (§4.6). Never begin Phase 8 before Phases 1–7 qualify.
5. If anything in this plan conflicts with what you find in the repository (module names, existing behaviour, tooling), do not improvise silently: record the conflict in `STATE.md`, propose a resolution, and ask.

**Success:** seven capabilities hardened, each proven in an isolated archetype app and in composition, reviewed in two independent Claude–Codex rounds, documented for developers and operators, followed by a full verification and an evidence-backed release decision that is explicit about what is **not** certified — including audit and idempotency.

---

## Appendix A — Changes from v1 (for the human reviewer)

| # | v1 gap | v2 change |
|---|---|---|
| 1 | Isolation-only archetype runs: no two hardened capabilities met before final verification, where composition failures are most expensive | Composition checkpoints C1 (after RabbitMQ) and C2 (after Security); cross-cutting contracts §7 in every phase |
| 2 | Outbox optional ("if required") | Dedicated Phase 4, mandatory for publish-with-DB-update |
| 3 | Audit absent entirely | D1 = out of scope; explicitly listed as not certified |
| 4 | Idempotency key scope undefined; cross-caller replay possible | Idempotency removed (D2); REST never retries non-idempotent methods; consumer dedupe documented as consumer responsibility |
| 5 | Outage testing = down/up only | Four degradation states incl. **slow** via Toxiproxy |
| 6 | Targeted `-am` builds can't detect breaking downstream modules (incl. Kafka) until Phase 8 | Compile-only `-amd` downstream check after every change |
| 7 | Later phases could silently regress earlier certified capabilities | Certified-capability regression rule |
| 8 | Claude Code foreground timeout would make long Testcontainers/Oracle/reactor runs impossible under the strict foreground rule | §4.1 timeout handling with supervised-wait fallback |
| 9 | Archetype consumption assumed artifacts exist; installing them wasn't addressed | §4.3 targeted install + flatten check + isolated local repo |
| 10 | Codex independence and record-keeping unspecified | §4.2 independence rule, verbatim prompt/output storage |
| 11 | Severity undefined | §5.1 definitions tied to release effect |
| 12 | Oracle RAC claimed certifiable; it can't be reproduced locally | D4: single-instance certified, RAC `BLOCKED` without real env |
| 13 | No compatibility baseline exists for "real release baseline" in Phase 8 | Phase 0 builds baseline into separate local repo; config-default snapshots |
| 14 | Health/readiness could convert shared-dependency outage into all-replica outage | §7.5 + D6 + Phase 8 step 7 check |
| 15 | Kill switches uniform; regulated capabilities could be disabled silently | Kill-switch tiers §7.3 |
| 16 | No flakiness policy for concurrency tests | 3 consecutive passes; retry-pass = defect |
| 17 | Long autonomous run with no resume mechanism or pause points | `STATE.md`, `PAUSE_AFTER_EACH_PHASE`, explicit stop points |
| 18 | Graceful shutdown deferred to Phase 8 environment | Per-capability shutdown verified in each consumer harness |
| 19 | No operator-facing docs | `operations.md` per capability |
| 20 | Evidence could leak tokens/PII into git | §4.7 redaction scan; raw logs git-ignored |


## Appendix B — Changes from v2 to v3 (decisions applied)

| Decision | Effect |
|---|---|
| D1 Audit out of scope | Phase 4 is outbox-only; outbox tier `DEFAULT_ON` not `MANDATORY`; audit listed as not certified with an explicit compliance warning |
| D2 No durable store → idempotency removed | Former Phase 8 deleted; final verification renumbered to Phase 8; REST client never retries POST/PATCH; RabbitMQ consumer dedupe is consumer responsibility with a documented, demonstrated pattern |
| D9 N/A | Recorded; `sub` noted as sufficient for future idempotency work |
| C2 composition | Moved to end of Phase 7: Security + REST + DB + Obs |
| D4 RAC | Production uses RAC; no test environment exists → RAC failover `BLOCKED`, reported as release gap |
| D5 | `traceparent` only; trace ID in ProblemDetail errors; any legacy correlation header removed as a recorded behaviour change |
| D6 | Readiness = pod-local only; shared deps on a separate dashboard health group |
| D8 | No pause between phases; mandatory stops remain |
| D10 | Repository owner owns everything |
