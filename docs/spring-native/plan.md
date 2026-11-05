# DC Platform — Scope Cut and Spring-Native Realignment Execution Plan (v1)

**Repository:** `muhammadallee/dc-platform`
**Starting point:** `main` at or after the merge of PR #3. Record HEAD SHA as the **baseline commit** before any edit.
**Implementer:** Claude Code. **Independent reviewer:** Codex.
**Predecessor:** the hardening initiative, [`../production-readiness/plan-v3.md`](../production-readiness/plan-v3.md), closed
2026-11-04 (tag `hardening/phase-08-closed`). This plan inherits its discipline. Where this document says "as v3 §N", that
section applies unchanged except where this plan states otherwise.

**Objective:**
1. Cut the chassis down to the certified capabilities plus a small, named set to be hardened next.
2. Remove the platform's own programming API wherever Spring Boot 4 / Spring Framework 7 / Spring Security 7 already has one,
   so that a service codes against **Spring types only**. The platform becomes auto-configuration: it supplies beans,
   customizers and property defaults, and backs off whenever the service supplies its own.
3. Keep every guarantee the hardening initiative proved. A guarantee may move (from an API type into auto-configuration),
   and may be retired only with evidence that Spring itself provides it.
4. Harden caching (Caffeine), scheduling and JDBC locking, Spring-native from the start, and give validation and OpenAPI a
   light certification.
5. Re-verify everything and produce a new, evidence-backed release decision.

> **Read this whole document before starting.** Section 4 holds the execution mechanics that caused silent failures in the
> hardening initiative. Phase 0 ends in a **human decision gate** (§6, step 8): nothing in this plan is approved until the
> owner answers it. Do not start Phase 1 before that.

---

## 1. Scope

### 1.1 Kept and re-certified (the v3 seven)

| # | Capability | v3 verdict | What changes here |
|---|---|---|---|
| 1 | Core + Error Handling | PASS | Platform API removed; Spring `ErrorResponse` / `ProblemDetail`, Micrometer `Tracer` |
| 2 | Logging + Observability | PASS | `Kv`, `LogSanitizer` removed as API; masking stays inside the logging configuration |
| 3 | Data Access (Oracle) | PASS, RAC `BLOCKED` | Already Spring Data JPA; `EntityId` / `Money` per ND7 |
| 4 | Transactional Outbox | PASS with parked issues | Kept as is **or** replaced by Spring Modulith, per the trial (Phase 6) and ND5 |
| 5 | RabbitMQ Messaging | PASS | Platform API removed; Spring AMQP `RabbitTemplate` / `@RabbitListener` with platform beans |
| 6 | REST Client + Resilience | PASS | Platform factory removed; Boot `RestClient.Builder`, `RestClientCustomizer`, HTTP interface clients |
| 7 | Security | PASS | `CurrentUser*`, `SecurityCustomizer` removed; `@RequiresPermission` per ND4 |

### 1.2 Kept and newly certified

| Capability | Modules kept | Modules dropped |
|---|---|---|
| Caching | `platform-cache-autoconfigure`, `platform-starter-cache-caffeine` (`platform-cache-api` per §9 Phase 8) | `platform-starter-cache-redis` |
| Scheduling | `platform-scheduling-autoconfigure`, `platform-starter-scheduling` | — |
| Locking (scheduling depends on it) | `platform-locking-api`, `-spi`, `-autoconfigure`, `-jdbc`, `platform-starter-locking-jdbc` | `platform-locking-redis`, `platform-starter-locking-redis` |
| Validation | all (the archetype includes it in every service) | — |
| OpenAPI | all (the archetype includes it in every service) | — |
| In-process events | none after Phase 2: replaced by Spring application events | `events/*` |
| Test support | per ND6 | — |

### 1.3 Cut (removed from the reactor, the BOM, the archetype, the docs and the site)

Kafka messaging, audit, idempotency, Redis (`redis/*` and every Redis starter), rate limiting, storage, files, feature flags,
secrets, multi-tenancy data isolation, schema-per-tenant, native image. Also the repository-root leftovers `scratch/` and
`tmpviol/`, after the owner confirms they are not needed (§6 step 8, ND2).

**v3 §3.9 no longer covers Kafka.** Its "keep Kafka compiling" rule ends when Kafka leaves the reactor.

### 1.4 Explicitly out of scope

- New capabilities beyond §1.2.
- Re-adding any cut capability. Each one comes back later only through a hardening phase of its own.
- PostgreSQL certification (stays out, HD3). Oracle RAC stays `BLOCKED` (HD4) unless an environment is supplied.
- Changing Spring Boot's major or minor version. Patch upgrades are allowed when a CVE requires one (as D133).

---

## 2. Phase order

| Phase | Name | Depends on | Composition checkpoint |
|---|---|---|---|
| 0 | Baseline, framework spikes, decisions | — | — |
| 1 | Scope cut | 0 | Golden path + all seven v3 consumer runs, unchanged |
| 2 | Core + Errors + in-process events → Spring-native | 1 | — |
| 3 | Logging + Observability → Spring-native | 2 | — |
| 4 | REST client + Resilience → Spring-native | 2, 3 | — |
| 5 | Security + Authorization → Spring-native | 2, 3 | **C2** (Security + REST + DB + Obs) |
| 6 | Outbox trial: Spring Modulith vs the platform outbox | 1 | — (**ND5 go/no-go gate** at the end) |
| 7 | Messaging → Spring AMQP, with the outbox ND5 chose | 6 | **C1** (Core + Obs + DB + Outbox + RabbitMQ) |
| 8 | Caching (Caffeine) | 2, 3 | — |
| 9 | Scheduling + JDBC locking | 2, 3 | — |
| 10 | Validation + OpenAPI (light certification) | 2, 5 | — |
| 11 | Final full-project verification and release decision | 1–10 | All capabilities in one app |

**Why this order (do not reorder without a recorded decision):**
- The cut comes first so every later phase works on a smaller reactor and nothing is ported only to be deleted.
- Core and errors come before everything else because every other capability's errors flow through them.
- The outbox trial is separate from the messaging rewrite because its result decides what Phase 7 builds. If Modulith
  fails, Phase 7 keeps the platform outbox and only the messaging API moves.
- Caching and scheduling follow the rewrite so they are written Spring-native once, not ported.
- Data access has no phase of its own: it is already Spring Data JPA. Its API remnants (`EntityId`, `Money`) are handled in
  Phase 2 per ND7, and the v3 Phase 3 consumer run is re-run as a regression in every phase that touches its modules.

---

## 3. Binding execution constraints

v3 §3 applies in full, with these changes and additions:

1. **v3 §3.2 (no full-reactor test run before the final phase)** is replaced. CI runs the full reactor on every push; locally,
   a full `verify` is allowed at the end of each phase, because the cut and the API removals touch many modules at once. Run it
   in the foreground, `-T1`, with `-l` (§4.1).
2. **v3 §3.6 (preserve architecture)** is amended by ND3/ADR-011: API modules are removed where Spring has the type. The BOM,
   parents, conditional auto-configuration, kill switches and `@ConditionalOnMissingBean` back-off all stay.
3. **The bean-provision rule (new, binding).** When the platform needs to give a service behaviour, it uses the **first** of
   these that works and records which one in `scope.md`:
   1. a property default in a lowest-precedence `EnvironmentPostProcessor` property source (the pattern already used by
      logging and observability), visible in `/actuator/env` and overridable by any user value;
   2. a Spring Boot customizer bean (`RestClientCustomizer`, `JsonMapperBuilderCustomizer` or its Boot 4 equivalent,
      `RabbitTemplateCustomizer`, `Customizer<HttpSecurity>` if the Phase 0 spike confirms it, …);
   3. a platform bean of a **Spring** type, `@ConditionalOnMissingBean`, with a kill switch `dc.platform.<cap>.enabled`;
   4. a platform type in the service's code: only with a recorded decision (ND-numbered) explaining why no Spring type fits.
   A `BeanPostProcessor` that rewrites a user's bean is not on the list: the user's bean wins, unchanged.
4. **Guarantee carry-over (new, binding).** No hardened behaviour is lost silently. Every phase that rewrites a capability builds
   the carry-over table (§8, Gate A) and proves every row with a test before closure.
5. **Consumer counts are the regression floor (new, binding).** The v3 consumer runs passed with these assertion counts:

   | v3 run | Count |
   |---|---|
   | Phase 1 Core + Errors | 92 |
   | Phase 2 Logging + Observability | 76 |
   | Phase 3 Data Access | 92 |
   | Phase 4 Outbox | 106 |
   | Phase 5 RabbitMQ | 63 |
   | Phase 6 REST + Resilience | 182 |
   | Phase 7 Security | 170 |
   | C1 composition (v3 Phase 5) | 106 (the Phase 4 harness run as C1) plus the Phase 5 consumer's AC1 and AC5 |
   | C2 composition | 61 |
   | Phase 8 all capabilities | 63 |

   A ported run keeps **at least** its v3 count, or `scope.md` lists every assertion dropped or changed and why (for example,
   one that asserted a platform type now removed, replaced by an assertion on the Spring type).
6. **Main stays green at every merge.** Each phase merges to `main` on closure (§4.4). Between phases `main` holds a mix of old
   and new APIs; the golden path, CI and every consumer run already ported must still pass on each merge.
7. **Zero-config acceptance (new, binding).** Every capability ends its phase with a test in which an app containing only the
   starter (plus the declared fail-closed settings) starts and meets the capability's contract. The deliberate fail-closed
   requirements stay and are listed in the capability's doc page:
   - security: `issuer-uri`, `jwk-set-uri`, `audiences`;
   - data: `spring.datasource.*` and Flyway migrations;
   - messaging: a reachable broker;
   - outbox: its table in the service's migrations (or Modulith's, per ND5).

---

## 4. Execution mechanics (read before Phase 0)

Everything here was learned the hard way in the hardening initiative.

### 4.1 Builds and long commands

- Before **every** Maven call (Git Bash):
  `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
  The shell's default JDK is 21.
- Invocation: `rtk proxy mvn -o -T1 -B -ntp -l <log> …`. Full builds use `-T1`, never `-T1C` (native-thread limit on this
  machine). CycloneDX and `deploy` refuse offline mode: run those without `-o`.
- Per-module builds: `-DforkCount=0 -Djacoco.skip=true` where coverage is not the point.
- Run full builds and consumer scenarios **in the foreground**. Background runs that sit idle get stopped for memory. If a
  command cannot fit the tool timeout, split it (per module, per test-class group); v3 §4.1's supervised-wait fallback applies
  only as a last resort, with the exit code captured.
- After a module changes, a stale `~/.m2` copy can cause `NoClassDefFoundError` downstream: install the upstream modules with
  `install -DskipTests` before running a dependent module's tests.
- Configuration-processor metadata merges need a clean build of the module (`clean` before `verify`).

### 4.2 Shell and file hygiene

- Do **not** write files or commit messages with heredocs or long quoted one-liners: they break in this shell. Write the file
  with the Write tool (scratchpad for temporary files) and pass its path (`git commit -F <file>`,
  `gh pr create --body-file <file>`, `python -I -X utf8 <script>`).
- Python output redirected into a committed file is cp1252 here, and `-I` ignores `PYTHONIOENCODING`: always use
  `python -I -X utf8`, or open the file with `encoding="utf-8"`. Before committing generated text, check it decodes as UTF-8;
  `DocsLinkCheckTest` reads every tracked `.md` as UTF-8 and fails CI otherwise.
- Never stage: `.serena/`, `dc-platform-chassis-review-fix-prompt.md`, `*.stackdump`, `properdocs.yml`. Stage files by name,
  never `git add -A`.
- Tell the owner before deleting anything outside the plan's own changes. `~/dc-hardening-runs` is **not** approved for
  deletion.

### 4.3 Codex

- Invocation exactly as [`../production-readiness/README.md`](../production-readiness/README.md) documents it
  (`codex exec --skip-git-repo-check -s read-only - < prompt > out`). Any wrapper script lives in the session scratchpad and does
  not survive the session: recreate it from the README.
- Independence rule as v3 §4.2: the first prompt in each round gets `scope.md`, the diff against the phase start commit, and
  paths to read; never Claude's conclusions.
- Prompts and raw outputs are saved verbatim under `docs/spring-native/phase-XX-<slug>/codex/`.
- On a usage limit: wait for the reset and retry. Never buy credits. The phase is `BLOCKED` until Codex answers.
- Each prompt names the base commit, and the runner checks that the commit resolves and that the diff is non-empty before
  sending. A review that read nothing is not a review.

### 4.4 Branches, commits, tags, PRs

- One branch per phase off `main`: `native/phase-XX-<slug>`. One PR per phase. Merge without asking once the phase is closed and
  CI is green (HC6). Tag the merge commit `native/phase-XX-closed`.
- Conventional commits, one logical change each, with a trailer `[native phase-XX gate-Y]`, ending with:

  ```text
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: <the current session URL>
  ```

  The model name in the trailer must be the model actually in use.
- `CHANGELOG.md` under `## [Unreleased]`: every removed type and its Spring replacement.

### 4.5 Resumability

`docs/spring-native/STATE.md`, updated at every gate transition (current phase, gate, last commit, blockers, pending decisions,
next action). On any restart, read it first. Never infer state from memory.

### 4.6 Human interaction points

Stop and wait when:
- the Phase 0 decision record is complete (§6 step 8);
- the outbox trial ends (ND5 is confirmed against the trial's evidence);
- a Critical/High finding cannot be fixed within the phase;
- an exception is needed (`exceptions.md`, `PENDING`);
- an environment is unavailable and the phase would become `BLOCKED`;
- a guarantee in the carry-over table can only be kept with a platform type (bean-provision rule step 4).

Do **not** stop between phases otherwise (HD8). Do not ask about steps this plan already determines.

### 4.7 Evidence hygiene

As v3 §4.7. Before every commit that adds evidence, scan it for `eyJ`, `Bearer `, `password`, `secret`, connection strings
and PII. Raw logs stay in the git-ignored `evidence-raw/`.

### 4.8 Generated documentation

- `docs/reference/*`, `llms-full.txt` and the skill resources are regenerated from a **full** build only (the generators read
  other modules' `target/`). Never commit them from an isolated `-pl` build.
- Gate H runs `python -m mkdocs build --strict` and `DocsLinkCheckTest`. Both must pass. `mkdocs.yml`'s nav must not name a
  page that no longer exists.

### 4.9 Consumer runs

As v3 §4.3, with the existing harness:
- scenarios: `tooling/hardening/scenarios/<scenario>.sh`, probe code under `tooling/hardening/scenarios/phase-0X/`;
- local repository per phase: `bash tooling/hardening/phase-repo.sh <phase> <modules>`, which verifies the baseline manifest and
  copies it to `~/.m2-hardening/<phase>`;
- **never install into `~/.m2-baseline`**;
- runs land in `~/dc-hardening-runs/native-<phase>/`.

Each rewrite phase **ports** its v3 scenario: the probe app's code moves to Spring types; the infrastructure, faults and
assertions stay. §3.5 applies.

---

## 5. Definitions

v3 §5 applies unchanged: severity, finding statuses, evidence levels, the four degradation states, material change.

Added:
- **Carried guarantee:** a behaviour the hardening initiative proved (an issue-register entry `FIXED` or `ACCEPTED`, or a v3 §7
  contract) that must still hold after the rewrite.
- **Retired guarantee:** one that disappears with a removed type and is now Spring's job. Retiring one needs a test showing
  Spring provides it, or a recorded decision accepting the gap.
- **New risk:** a failure mode the Spring-native design introduces, which the old API prevented.

---

## 6. Phase 0 — Baseline, framework spikes, decisions

No production code changes in Phase 0 except tracking docs, spike tests (in a scratch module outside the reactor or under
`src/test`) and harness scaffolding.

1. **Baseline.** Record HEAD SHA, module count, BOM contents (artifact list), the archetype's starters, and the v3 consumer counts
   (§3.5). Tag the baseline `pre-spring-native` and push the tag.
2. **Compatibility baseline.** The v3 baseline repository (`~/.m2-baseline`, commit `7b248e9`) stays untouched. Build a second
   baseline from `pre-spring-native` into `~/.m2-native-baseline`. Expected API removals are compared against it (step 6).
3. **Framework spikes.** Each claim below came from a design discussion, not from verification. Check each on Boot 4.1.2
   (Context7 for the docs, then a small test that proves it), and record the result, the version and the evidence path in
   `docs/spring-native/phase-00-baseline/spikes.md`:

   | ID | Claim to verify | Decides |
   |---|---|---|
   | S1 | A Spring Modulith release supports Boot 4.1.2; its event-publication registry works on **Oracle** (JDBC or JPA); it can forward events to RabbitMQ with publisher confirms | Whether the outbox trial (Phase 6) is possible at all. **Do this one first.** |
   | S2 | Spring Security 7 applies `Customizer<HttpSecurity>` beans to Boot's default chain, and how a user chain interacts with them | Phase 5 customizing route |
   | S3 | Boot 4 HTTP service clients (`@HttpExchange` groups): property names for base URL and timeouts per group, and how a `RestClientCustomizer` reaches them | Phase 4 client model |
   | S4 | Spring Framework 7 `@Retryable` / `@ConcurrencyLimit`: what enables them, and how they combine with Resilience4j annotations | Phase 4 resilience model |
   | S5 | SLF4J fluent `addKeyValue` renders in Boot's structured JSON logging; how the platform's masking can apply to Boot's structured formats | Phase 3 logging model |
   | S6 | Method security can be enabled by default from auto-configuration without the service adding `@EnableMethodSecurity`, and a startup check can enumerate `@PreAuthorize` methods | Phase 5, ND4 |
   | S7 | Spring's `ErrorResponseException` / `ProblemDetail` handling lets a platform `@RestControllerAdvice` add `code`, `traceId`, `timestamp`, `retryable` without the service using platform types | Phase 2 error model |

   A spike that fails changes the plan for its phase: record the fallback (keep the platform type, with an ND decision) and put
   it into the decision record.
4. **Removal map.** For every public type in every `*-api` module that stays in the reactor, record its Spring replacement, the
   modules and tests that use it, and the phase that removes it. The current inventory:

   | Module | Types | Replacement (subject to spikes) | Phase |
   |---|---|---|---|
   | `platform-core-api` | `CorrelationId`, `RequestContext`, `ErrorCode`, `PlatformException`, `CapabilityDescriptor`, `PlatformApi`, `PlatformInternal` | Micrometer `Tracer`, `RequestContextHolder`, `ProblemDetail` properties; the two annotations may stay as internal markers | 2 |
   | `platform-errors-api` | `BusinessException`, `NotFoundException`, `ConflictException`, `HttpStatusHint`, `ProblemDetailCustomizer` | `ErrorResponseException` and subclasses, `@ResponseStatus`, the service's own `@RestControllerAdvice` | 2 |
   | `platform-events-api` | `DomainEvent`, `DomainEventPublisher`, `DomainEventHandler` | `ApplicationEventPublisher`, `@EventListener`, `@TransactionalEventListener` | 2 |
   | `platform-data-api` | `EntityId`, `Money`, `PersistenceConventions` | per ND7 | 2 |
   | `platform-logging-api` | `Kv`, `LogSanitizer` | SLF4J fluent API; masking internal | 3 |
   | `platform-restclient-api` | `PlatformRestClientFactory`, `PlatformRestClientCustomizer`, `RemoteCallException` | `RestClient.Builder`, `RestClientCustomizer`, `RestClientResponseException` / `ResourceAccessException` | 4 |
   | `platform-resilience-api` | `RetryableOperation`, `ResilienceDefaults` | Resilience4j annotations / registries, or Spring `@Retryable` (S4) | 4 |
   | `platform-security-api` | `CurrentUser`, `CurrentUserAccessor`, `SecurityCustomizer` | `@AuthenticationPrincipal`, `SecurityContextHolder`, `Customizer<HttpSecurity>` / a user `SecurityFilterChain` | 5 |
   | `platform-security-authz-api` | `RequiresPermission` | `@PreAuthorize` per ND4 | 5 |
   | `platform-messaging-api` | `EventPublisher`, `OutboxPublisher`, `EventHandler`, `EventType`, `EventEnvelope`, `EventHeaders`, `EventPublishException`, `EventSerializationException` | `RabbitTemplate`, `@RabbitListener`, Spring AMQP `Message` / headers; outbox per ND5 | 7 |
   | `platform-cache-api` | `CacheKeyConvention`, `CacheNames` | Spring `@Cacheable` + `KeyGenerator` bean | 8 |
   | `platform-locking-api` | `LockManager`, `LockException` | decided in Phase 9 (see §9) | 9 |
   | `platform-validation-api` | `@NotBlankTrimmed`, `@SafeText`, `@Ulid`, `@FutureInstant` + validators | **kept**: custom Jakarta constraints are the standard mechanism | — |
   | `platform-test-api` | `@PlatformTest`, `@PlatformWebTest`, `TestJwtIssuer`, `Containers`, … | per ND6 | 2–10 |

   Confirm the list against the code (`find . -path '*-api/src/main/java' …`): this table may be stale.
5. **Downstream and dependency map.** For each module that stays, its dependents (`mvn -pl X -amd` listing). Confirm that no kept
   module depends on a cut one. Only a partial check exists (2026-11-04): a search of the seven certified capabilities' POMs for
   `platform-*` artifacts of cut modules found none, and scheduling's POM depends on locking-api, locking-autoconfigure and
   locking-jdbc. Not yet checked: validation, OpenAPI, cache and locking POMs, the archetype's transitive tree, and optional
   third-party dependencies (for example Redis client code in `platform-cache-autoconfigure` or `platform-locking-autoconfigure`).
6. **Expected-removals list.** japicmp against `~/.m2-native-baseline` will fail by design. Write
   `docs/spring-native/expected-removals.md` (one line per removed type, with its phase) and make the final compatibility check
   compare japicmp's output against it: an unlisted break is a finding. This also covers HI-0081.
7. **Tracking docs.** Create `docs/spring-native/`: `STATE.md`, `decisions.md`, `capability-status.md`, `exceptions.md`, and
   `phase-00-baseline/` (`inventory.md`, `spikes.md`, `removal-map.md`). Issues continue in the **existing**
   [`../production-readiness/issue-register.md`](../production-readiness/issue-register.md), numbering from **HI-0109**. Decisions
   that change the platform go into `docs/decisions/decision-log.md` from **D135** on; plan decisions use the prefix **ND**
   (not HD/HC, not D) in `docs/spring-native/decisions.md`.
8. **Decision record — STOP for the owner.** Write `docs/spring-native/decisions.md` with a recommendation for each decision
   below, the spike results that bear on it, and wait for answers:

   | ID | Decision | Recommended default |
   |---|---|---|
   | ND1 | Timing: realign **before** `1.0.0-RC1`, or ship RC1 on the current API and realign in 2.0 | **Before RC1.** There are no users (HC7); removing an API now costs nothing; after RC1 it is a breaking change for adopters |
   | ND2 | The cut list (§1.3), including `scratch/` and `tmpviol/` | As §1.3. The Redis cache starter and the Redis lock starter go; validation and OpenAPI stay |
   | ND3 | Constitution change: service code uses Spring types; API modules exist only where no Spring type fits | **Yes**, recorded as ADR-011 (refines ADR-009 "No God Framework"). `CLAUDE.md` rules 4, 5, 9 and the enforcer/ArchUnit rules are updated in Phase 1 |
   | ND4 | Authorization: `@PreAuthorize("hasAuthority('…')")` or keep `@RequiresPermission` | **`@PreAuthorize`**, with the platform's deny-by-default and open-path startup checks extended to it, and a startup check that rejects an unknown authority string where the permission set is declared. Fallback if S6 fails: keep `@RequiresPermission` as the one platform annotation |
   | ND5 | Outbox: Spring Modulith or the platform outbox | **Decided after the Phase 6 trial**, not now. Pre-agreed rule: Modulith wins only if it passes every v3 Phase 4 scenario and every carried outbox guarantee, or each gap has an accepted mitigation. Otherwise the platform outbox stays, exposed through Spring types where it can be |
   | ND6 | `platform-test-api` | **Keep, slimmed** to what Boot's test slices and Testcontainers `@ServiceConnection` do not give: `TestJwtIssuer`, the problem-detail assertions, the usage rules. Drop the `@Platform*Test` meta-annotations if Boot's slices cover them |
   | ND7 | `EntityId`, `Money`, `PersistenceConventions` | **Drop.** Not used by the certified data contract; a service that wants value types writes its own |
   | ND8 | How cut code is preserved | **Delete it from `main`**, with the `pre-spring-native` tag as the restore point. No archive branch to maintain |
   | ND9 | Locking API (Phase 9) | **Decide in Phase 9 Gate A** between keeping `LockManager` (no Spring type exists for an explicit lock) and adopting Spring Integration's `LockRegistry` or ShedLock. Recommendation recorded then |
   | ND10 | Kafka portability is given up for messaging | **Accept.** Kafka is cut; messaging becomes RabbitMQ-specific Spring AMQP. A future Kafka capability is Spring Kafka, with its own phase |

   The plan continues with the defaults only for decisions the owner answers. An unanswered decision blocks the phase that needs
   it, not the whole plan.

Phase 0 is complete when steps 1–7 are recorded and step 8 is answered. Close Phase 0 with a single Codex round over the spikes
and the removal map (are the replacements real; is anything missing), then commit, PR, merge on green, tag
`native/phase-00-closed`.

---

## 7. Cross-cutting contracts

v3 §7 applies to every capability, unchanged: real override, config-default snapshot, kill-switch tier, outage posture, health
semantics, context propagation, error surface, redaction, observability, graceful shutdown, startup under adversity.

Added for this plan:

12. **Spring types only in service code.** The consumer probe app for the capability compiles with **no** platform type on its
    import list, except where a recorded ND decision allows one. A test (ArchUnit in the probe, or a `grep` in the harness) fails
    the run otherwise.
13. **Back-off to the user's Spring bean.** For every Spring type the platform supplies (a `RestClient.Builder`, a
    `SecurityFilterChain`, a `RabbitTemplate`, a `CacheManager`, a `TaskScheduler`, …), a test in which the service supplies its
    own: the platform's backs off, the service's bean is used unchanged, and any guarantee the platform can no longer give (for
    example, timeouts on a user-built `RestClient`) is documented on the capability's page.
14. **Zero-config acceptance** (§3.7).

---

## 8. Standard workflow for Phases 2–10

v3 §8 Gates A–H apply, with these additions.

### Gate A — Inspect, baseline, scope, carry-over

In addition to v3 Gate A:

1. **Guarantee carry-over table** in `scope.md`. For the capability, list every issue-register entry that is `FIXED` or
   `ACCEPTED` and every applicable v3 §7 contract. For each row:

   | Column | Content |
   |---|---|
   | Guarantee | The behaviour, in one line |
   | Source | HI-ID or §7 contract number |
   | Lives today in | API type / auto-config / filter / … (file path) |
   | Lives after in | Auto-config bean / customizer / property default / Spring itself (file path, once written) |
   | Proof | The test (class and method) that shows it still holds after the rewrite |
   | Kind | `CARRIED` / `RETIRED` (Spring provides it; the proof is a test against Spring's behaviour) / `NEW RISK` |

   Every `NEW RISK` row gets a mitigation (a startup check, a test, a documented limitation) and an HI entry.
2. **Removal list** for the phase, taken from the Phase 0 removal map: the types to delete and every call site.
3. **Port plan** for the phase's consumer scenario (§4.9) with the v3 count as the floor (§3.5).

### Gate B — Implement and test

In addition to v3 Gate B: delete the API types and their module (or slim it), move the behaviour into auto-configuration
following the bean-provision rule (§3.3), port the probe app, update the archetype if it referenced a removed type, and update
the OpenRewrite recipe `tooling/platform-migrations/.../adopt-platform.yml` if it names a removed type. Run the downstream
compile check (v3 §3.9) and the certified-capability regression (v3 §3.10) for every kept capability whose modules or consumer
code changed.

### Gates C, D, E

As v3. Two Codex rounds. Codex's Round 1 prompt includes the carry-over table as a **requirement** (it is part of `scope.md`),
and asks specifically: is any guarantee lost; is any `RETIRED` row not really covered by Spring; what new risk is missing.

### Gate F — Consumer run

As v3 Gate F with the ported scenario, three consecutive passes (flakiness rule), at or above the v3 count.

### Gate G — Composition

C2 at the end of Phase 5, C1 at the end of Phase 7, as v3 §9 defines them, ported.

### Gate H — Docs and closure

As v3 Gate H, plus:
- the capability's `docs/modules/<cap>.md` is rewritten around Spring types: what the platform configures, which Spring
  extension point to use to change it, and the zero-config table (what works with only the starter, what must be set);
- the book chapter for the capability is updated or marked for a later pass with an HI entry (HI-0004 shows what happens
  otherwise);
- the samples in `docs/samples/<slug>/` are updated to Spring types;
- `mkdocs build --strict` and `DocsLinkCheckTest` pass (§4.8);
- closure verdict in `docs/spring-native/phase-XX-<slug>/closure.md`, `STATE.md` and `capability-status.md` updated, PR merged
  on green, tag `native/phase-XX-closed`.

---

## 9. Phase-specific criteria

### Phase 1 — Scope cut

No behaviour change in what stays. One Codex round (the question: does anything kept depend on, document or ship anything
removed), plus a verification pass if fixes follow.

1. Remove the cut modules (§1.3) from the reactor `pom.xml`s, from `build/platform-bom` and `build/platform-dependencies`
   (only the entries that exist for cut modules; third-party versions a kept module still needs stay), and delete their
   directories.
2. Remove third-party version properties and managed dependencies that only cut modules used (Kafka clients, Redis/Lettuce if
   nothing kept needs them, …). Confirm with a dependency tree of every kept module.
3. Archetype: confirm it references no cut starter. It pulls validation and OpenAPI, which stay.
4. `CLAUDE.md`, the enforcer and the ArchUnit rules: apply ND3 (ADR-011). Remove rules that only concerned cut modules.
5. Docs: delete the cut capabilities' `docs/modules/*.md`, their book chapters or sections (chapters 9 to 13 are affected; keep
   what covers caching and scheduling), their samples, their reference entries; update `mkdocs.yml`'s nav; regenerate
   `docs/reference/*`, `llms-full.txt` and the skill resources from a full build; update the compatibility appendix. **Do not
   edit the closed hardening record** (`docs/production-readiness/final-release-assessment.md`, `release-decision.md`, the
   phase folders): it is the evidence of tag `hardening/phase-08-closed`. Add only a one-line banner at the top of the
   assessment and the release decision: "Superseded by [`docs/spring-native/`](../spring-native/plan.md), ND1." The new
   shipped and not-certified lists live in `docs/spring-native/capability-status.md`. If ND1 is "before RC1", v3's GO for
   `1.0.0-RC1` is superseded too: say so in that banner.
6. `tooling/platform-migrations/.../adopt-platform.yml` and the MCP server's resources: remove cut starters.
7. Verify:
   - full `verify` (foreground, `-T1`): every remaining test passes, none newly skipped;
   - golden path 9/9 (or the new count, if a golden-path step covered a cut module: justify it);
   - **all seven v3 consumer runs and C2, unchanged, at their v3 counts**;
   - `mkdocs build --strict` and `DocsLinkCheckTest`;
   - Trivy on a generated service: still no Critical or High (the cut should only remove findings; HI-0106 shrinks, as the Kafka
     compression libraries go).

### Phase 2 — Core + Errors + in-process events → Spring-native

- Errors: the platform `@RestControllerAdvice` handles Spring's `ErrorResponse` types and `@ResponseStatus`, adding `code`,
  `traceId`, `timestamp`, `retryable` to the `ProblemDetail` (S7). How a service sets its error code without a platform type
  is decided at Gate A (candidates: a `ProblemDetail` property set by the thrower; the `type` URI; an error-code resolver bean).
  Build-time uniqueness of error codes (v3 Phase 1) is a carried guarantee: keep it.
- Core: the correlation filter, context propagation through executors and the banner are already auto-configuration; remove
  the API types per the removal map.
- Events: delete `events/*`. Document the Spring replacement (`ApplicationEventPublisher`, `@TransactionalEventListener`) on
  the core page. Not a certified capability before or after; no consumer run.
- Data: apply ND7. Re-run the v3 Phase 3 consumer run as a regression.
- Carried from v3 Phase 1 (examples to verify at Gate A, not an exhaustive list): `traceparent`-only correlation (HD5); header
  injection rejected; no exception message, SQL or stack trace in a response; MDC empty after each request; ≥200 parallel
  requests with no context bleed; framework exceptions keep their statuses.

### Phase 3 — Logging + Observability → Spring-native

- Decide at Gate A, from S5, between keeping the platform's Logback configuration and moving to Boot's structured logging with
  the masking applied as a Logback component. Masking (v3 Phase 2 redaction: JWTs, `Authorization`, passwords, PII) is a carried
  guarantee either way, including the cases Codex found in v3 (the regex boundary cases in the Phase 2 review files).
- Observability is already configuration-only; verify nothing in it depends on a removed type.
- The tag-cardinality guard, the health groups (HD6) and the actuator exposure stay.

### Phase 4 — REST client + Resilience → Spring-native

- Services inject Boot's `RestClient.Builder` (or declare `@HttpExchange` interfaces, per S3). The platform contributes
  `RestClientCustomizer` beans for timeouts, the overall deadline, tracing, bounded and redacted error-body capture, and
  logging.
- The retry classification is a carried guarantee: **POST and PATCH are never retried** (HD2), with or without an
  `Idempotency-Key` header; 4xx not retried; `Retry-After` honoured. It must hold whichever retry mechanism S4 selects.
- Errors surface as Spring's `RestClientResponseException` / `ResourceAccessException`; the errors advice maps them to
  `ProblemDetail` with `retryable`, and never leaks the downstream body.
- §7.13 matters most here: a service that builds its own `RestClient` without the builder loses the platform's timeouts.
  Document it, and consider a startup WARN.

### Phase 5 — Security + Authorization → Spring-native

- Principal: `@AuthenticationPrincipal Jwt` / `SecurityContextHolder`. The claim-to-authority mapping stays in
  auto-configuration (a `JwtAuthenticationConverter` bean).
- Customizing: per S2, a `Customizer<HttpSecurity>` bean or a service `SecurityFilterChain`. The platform still fails closed
  when misconfigured (v3 Phase 7).
- Authorization per ND4. Carried guarantees to prove: deny by default; the open-path startup check (HI-0099); a rule that returns
  no decision cannot open a path (the Phase 7 `AnonymousAccessProbe` fix); 401 vs 403 as `ProblemDetail`. The Phase 7
  interface-proxy fixes are `RETIRED` only if a test shows Spring's method security handles a permission on an implementing
  method behind an interface proxy.
- `NEW RISK` rows to mitigate: authority typos in expression strings; methods with no annotation.
- C2 at the end of the phase (ported, ≥ 61).

### Phase 6 — Outbox trial

A time-boxed comparison, not a rewrite. If S1 failed (no Boot 4.1.2 support or no Oracle registry), record that, set ND5 to
"platform outbox", and close the phase without a trial.

1. Build a trial branch of the Phase 4 consumer app that uses Spring Modulith's event publication registry and AMQP
   externalisation instead of the platform outbox, on Oracle and RabbitMQ.
2. Run the v3 Phase 4 scenarios (106 assertions) against it unchanged where possible, and against the platform outbox again on
   the same commit for a side-by-side.
3. Compare, row by row, the carried outbox guarantees: same transaction and DataSource as the business write; a stable event id
   generated once; bounded batch; `FOR UPDATE SKIP LOCKED` or equivalent across two instances; a separate bounded connection
   budget for the drainer; rejected or unroutable messages parked, not retried forever; lag and parked metrics; trace context
   restored on drain; retention; the stop timeout and the shutdown order; the parked issues HI-0071, HI-0079 and HI-0086 (does
   Modulith have them, or different ones).
4. Write `docs/spring-native/phase-06-outbox-trial/comparison.md`, run one Codex round on it, and **stop for the owner** to
   confirm ND5.

### Phase 7 — Messaging → Spring AMQP

- Services publish with `RabbitTemplate` (or through the outbox ND5 chose) and consume with `@RabbitListener`. The platform
  supplies a message converter, publisher confirms and returns (an unroutable message is an error), header post-processors
  (event id, type, version, `traceparent`), a listener container factory with bounded retry, backoff and dead-lettering, quorum
  queues by default, and the queue/exchange declarations.
- How a service declares a subscription without `@EventHandler`'s destination/eventType model is decided at Gate A (plain
  `Declarables` beans, or a property-driven topology).
- Carried from v3 Phase 5: one handler per message (two handlers that could receive the same message fail startup); an unreadable
  payload or a newer version goes to the DLQ without quoting the payload; the DLQ replay tool (`tooling/scripts/rabbit-dlq-replay.py`)
  still works; the shutdown order; the consumer-side dedupe pattern in the sample.
- C1 at the end of the phase (ported).
- Re-evaluate HI-0071, HI-0079, HI-0086 under the chosen outbox and update their entries.

### Phase 8 — Caching (Caffeine)

Full v3 workflow (Gates A–H), Spring-native from the start: services use `@Cacheable`, `@CacheEvict`, `CacheManager`. The
platform supplies a Caffeine `CacheManager` with bounded sizes and TTLs by default (no unbounded cache), metrics, a key
convention as a `KeyGenerator` bean if one is needed, and the kill switch. Contracts to prove: no cross-user or cross-tenant
leakage through keys (a key generator that ignores the principal is a `NEW RISK` to document); eviction under memory pressure;
behaviour when the cached method throws; cache-stampede behaviour (`sync = true` documented); a service `CacheManager` backs the
platform's off. Consumer scenario written new (no v3 count; the floor is what Gate A specifies).

### Phase 9 — Scheduling + JDBC locking

Full v3 workflow. Services use `@Scheduled`. The platform supplies a `TaskScheduler` with context propagation and graceful
shutdown, and a lock so that one replica runs each job (ND9). Contracts to prove on Oracle, with two app instances: exactly one
instance runs a locked job per tick; a crash while holding the lock (the lock expires; the next tick runs); a job that outlives
its lock; clock skew between instances; the database slow or down during a tick (the job skips, logs and counts it; it never
runs twice); shutdown waits for a running job within the grace period; trace context per execution; MDC cleared. Consumer
scenario written new.

### Phase 10 — Validation + OpenAPI (light)

One Codex round, one consumer run, no composition.
- Validation: the custom constraints stay (§6 step 4); prove `@Valid` / `@Validated` failures become `ProblemDetail` 400s with
  field paths and no internal detail; `@SafeText` against injection-shaped input.
- OpenAPI: the document and UI exposure posture (v3 Phase 7 required it disabled or protected by default); a user
  configuration backs the platform's off; no secret or internal path in the generated document.

### Phase 11 — Final full-project verification

As v3 §10 steps 1–8, on the realigned platform:
1. Clean full `verify` (foreground, `-T1`).
2. Container suites for the kept providers (Oracle, RabbitMQ, Keycloak/JWKS, Toxiproxy).
3. Golden path, and one generated app with **every** kept capability running the combined C1 + C2 scenarios, the caching and
   scheduling scenarios, and the request → outbox → broker → handler → outbound chain on one trace (≥ 63, the v3 all-capability
   count, plus the new capabilities' scenarios).
4. Scans: Trivy, CycloneDX SBOM, secrets (including the git history, as the repository is public).
5. Compatibility: japicmp output against `expected-removals.md` (§6 step 6); config-default snapshot diff; BOM parity between
   `platform-service-parent` and a plain `platform-bom` import.
6. Re-run each phase's ported consumer run on the final commit.
7. OpenShift with the team's chart tool: `BLOCKED` unless the owner supplies the HD7 details.
8. Release rehearsal: reproducible build, and close **HI-0108** (SBOM and provenance in `release.yml`; `release.sh` without a
   hard-coded `-T1C`).

Then write `run-results.md`, `final-release-assessment.md` and `release-decision.md` under `docs/spring-native/phase-11-final/`
and `docs/spring-native/`, with the certified list, the open issues, the known limitations and the first-adopter conditions.
Carry forward explicitly: HI-0071, HI-0079, HI-0086 (or their Modulith equivalents), HI-0106, HI-0107, Oracle RAC (`BLOCKED`),
OpenShift (`BLOCKED` unless run).

---

## 10. Deliverables layout

```text
docs/
  spring-native/
    plan.md                    # this file
    STATE.md
    decisions.md               # ND1–ND10
    capability-status.md
    exceptions.md
    expected-removals.md
    final-release-assessment.md
    release-decision.md
    phase-00-baseline/         # inventory.md, spikes.md, removal-map.md, codex/, evidence/
    phase-01-scope-cut/
    phase-02-core-errors/
    phase-03-logging-observability/
    phase-04-rest-resilience/
    phase-05-security/         # includes composition-c2.md
    phase-06-outbox-trial/     # includes comparison.md
    phase-07-messaging/        # includes composition-c1.md
    phase-08-caching/
    phase-09-scheduling-locking/
    phase-10-validation-openapi/
    phase-11-final/            # run-results.md
    # each phase: scope.md, review-round-1.md, review-round-2.md, closure.md, codex/, evidence/
  production-readiness/
    issue-register.md          # continues from HI-0109
  decisions/
    decision-log.md            # continues from D135
    adr-011.md                 # Spring types in service code (ND3)
  samples/<slug>/              # updated per phase; new: caching/, scheduling/
```

`evidence-raw/` directories are git-ignored.

---

## 11. End-of-phase report

As v3 §12, plus: the carry-over table with each row's proof, the removed types, and the ported consumer count against its v3
floor.

---

## 12. Start instruction for Claude Code

1. Read this document fully, then [`../production-readiness/plan-v3.md`](../production-readiness/plan-v3.md) §3, §5, §7 and §8,
   which this plan inherits, and [`../production-readiness/STATE.md`](../production-readiness/STATE.md) for where the hardening
   initiative ended.
2. Set up the environment per §4.1. Check `git status`: never stage the files §4.2 lists.
3. Create `native/phase-00-baseline` from `main`. Record the baseline SHA, tag `pre-spring-native`.
4. Execute Phase 0 steps 1–7, starting with spike S1. Write `decisions.md` (step 8) and **stop** for the owner's answers.
5. After the answers: update `STATE.md`, run the Phase 0 Codex round, merge on green, tag, then execute Phase 1 through all of
   its steps.
6. Continue phase by phase. Respect the stops in §4.6. Never begin Phase 7 before the owner has confirmed ND5 on the Phase 6
   evidence; never begin Phase 11 before Phases 1–10 qualify.
7. If anything here conflicts with the repository (module names, existing behaviour, tooling), do not improvise silently: record
   the conflict in `STATE.md`, propose a resolution, and ask.

**Success:** a smaller chassis that a Spring developer can use without learning a platform API; every guarantee the hardening
initiative proved still holds, each one with a test, or was retired with evidence; caching and scheduling hardened; a new
release decision that says exactly what is certified and what is not.
