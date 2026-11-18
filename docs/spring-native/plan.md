# DC Platform 1.0.0 — Spring-native plan (v2)

**Repository:** `muhammadallee/dc-platform`. **Branch:** `develop` (a parallel stream; the owner alone merges it into `main`).
**Implementer:** Claude Code. **Independent reviewer:** Codex.
**Version 2, 2026-11-19.** Rewritten from the owner's answers to the Phase 0 decision record. What changed from v1:

- The goal is release **1.0.0**, delivered in **iterations**. Each iteration ends in a state you can ship and stop at.
- Scope is now seven capabilities, not fourteen. JWT security, authorization, caching, scheduling, locking, validation and
  OpenAPI are cut. REST APIs are secured with **basic auth only**.
- Every capability, now and later, is built with one repeatable recipe (§8).

Predecessor: the hardening initiative ([`../production-readiness/plan-v3.md`](../production-readiness/plan-v3.md), closed
2026-11-04, tag `hardening/phase-08-closed`). Its rules are summarised here wherever this plan uses them, so you should not
need to open v3 except for detail.

---

## 1. What we are building

A small Spring Boot chassis that a Spring developer can use without learning a platform API.

- **Spring types only in service code.** The platform supplies beans, customizers and property defaults, and steps back when
  the service defines its own. A service writes `RabbitTemplate`, `@RabbitListener`, `RestClient.Builder`, `JpaRepository`,
  not `PlatformRestClientFactory` or `EventPublisher`.
- **Nothing proven is lost.** Every behaviour the hardening initiative proved (timeouts, no retry of POST, masking of tokens in
  logs, no lost outbox events, …) still holds after the rewrite, each with a test. A guarantee may move from a platform type into
  auto-configuration. It may be dropped only with a test showing Spring now provides it.
- **Shippable at every iteration.** At the end of each iteration the chassis builds, its archetype generates a working
  service, every lab passes, the docs describe only what ships, and a release decision is written.
- **Repeatable.** New capabilities arrive later through the same recipe (§8, §14).

---

## 2. Scope

Every module directory in the repository lands in exactly one of five bands.

### 2.1 Iteration 1 — release 1.0.0

| Capability | What a service uses | Modules |
|---|---|---|
| Logging | SLF4J (fluent `addKeyValue`), Boot logging config; masking built in | `logging/*` |
| Observability | Micrometer `Observation` / `Tracer`, Actuator | `observability/*` |
| Data | Spring Data JPA (`JpaRepository`, `@Transactional`), Flyway; Oracle in production, H2 for labs | `data/*` |
| Messaging (RabbitMQ only) | Spring AMQP `RabbitTemplate`, `@RabbitListener` | `messaging/platform-messaging-rabbit`, `-autoconfigure`, `platform-starter-messaging-rabbit`, `-test` |
| Outbox (RabbitMQ only) | Publish inside the business transaction; delivery to RabbitMQ after commit | `messaging/platform-messaging-outbox*`, `platform-starter-messaging-outbox` (or Spring Modulith, per ND5) |

### 2.2 Iteration 2 — release 1.1.0 (see ND12)

| Capability | What a service uses | Modules |
|---|---|---|
| REST API | Spring MVC controllers, Bean Validation; every path behind **HTTP basic auth** (the API gateway checks JWT or OAuth client credentials and then calls the service with basic auth) | new: `rest-api/*` (Phase 8 decides the layout) |
| REST client | Boot `RestClient.Builder` or `@HttpExchange` interfaces; outbound **basic auth** or **OAuth2 client credentials**; timeouts and safe retries | `restclient/*`, `resilience/*` |

### 2.3 Foundation — kept because the chassis needs it

Not a capability you pick: it is in every generated service. Re-certified in Phase 2, documented, and covered by the
"first service" lab.

| Part | Why it stays | Modules |
|---|---|---|
| Build | Parents, BOM, dependency management, enforcer and ArchUnit rules, the build plugin | `build/*` |
| Core | Trace-based correlation, context propagation across threads, the startup banner | `core/*` |
| Errors | Every error becomes an RFC 9457 `ProblemDetail` with a stable code, including Bean Validation failures | `errors/*` |
| Test support | What Boot's test slices do not give (ND6) | `test/platform-test-api`, `test/platform-starter-test` |
| Archetype and tooling | One archetype generating the current iteration's capabilities; migrations recipe, MCP server and skill, pruned | `tooling/*` |
| Docs site | Module pages, book, labs, samples for kept capabilities only | `docs/platform-docs`, `docs/` |

### 2.4 Parked — kept in the reactor, not shipped yet

Iteration 2 code lives in the repository during iteration 1, but must not ship in 1.0.0: shipping today's REST client API
and breaking it in 1.1.0 would break semantic versioning.

- **Modules:** `restclient/*`, `resilience/*`.
- **While parked:** they compile and their tests run, they are **not** in `platform-bom`, **not** deployed
  (`maven.deploy.skip`), **not** offered by the archetype, and their doc pages carry a "coming in iteration 2" note.
- **Unparked** in Phase 9.

### 2.5 Cut — deleted from `develop` in Phase 1

Restore point: tag `pre-spring-native` (ND8).

| Removed | Module directories |
|---|---|
| JWT security and authorization | `security/*` (8 modules) |
| Validation (platform constraints) | `validation/*`. Spring's own Bean Validation stays; errors maps its failures (§2.3) |
| OpenAPI | `openapi/*` |
| Caching | `cache/*`, `redis/*` |
| Scheduling and locking | `scheduling/*`, `locking/*` (ND9: nothing kept needs a lock; the outbox uses `SKIP LOCKED`, not the locking module) |
| In-process events | `events/*` (Spring's `ApplicationEventPublisher` replaces it) |
| Kafka messaging | `messaging/platform-messaging-kafka`, `platform-starter-messaging-kafka` |
| Audit, idempotency, rate limiting, storage, files, feature flags | `audit/*`, `idempotency/*`, `ratelimit/*`, `storage/*`, `files/*`, `flags/*` |
| Their contract tests | `test/tck/*` except `platform-tck-messaging` (which goes in Phase 6, with the messaging API) |
| Their docs | their `docs/modules/*.md`, book chapters 04, 05, 09–13, `docs/samples/security` |

Also cut, in the phase that rewrites their area:
- `messaging/platform-messaging-api`, `-spi`, `-inmemory`, `platform-starter-messaging-inmemory`, `platform-tck-messaging`
  (Phase 6: the platform messaging API gives way to Spring AMQP).
- Every `*-api` type in the removal map (Phase 0), in the phase listed there.

Three kept modules have an **optional** link to the JWT security API, which Phase 1 removes:
- `platform-data-jpa-autoconfigure`: the JPA auditor falls back to `"system"`;
- `platform-messaging-outbox-autoconfigure`: the outbox stops recording the principal;
- `platform-restclient-autoconfigure`: token relay goes.

In iteration 2, the REST API phase restores the auditor and the outbox principal from Spring's `SecurityContextHolder`.

`scratch/` and `tmpviol/` are untracked local leftovers: deleted locally, no repository change.

### 2.6 Out of scope

- Re-adding a cut capability. Each one can come back later, through the recipe (§14).
- PostgreSQL (HD3). Oracle RAC stays `BLOCKED` (HD4) until an environment exists.
- A Spring Boot major or minor upgrade. A patch upgrade is allowed when a CVE needs one (as D133 did).

---

## 3. Iterations and phases

| Phase | Name | Depends on | Ends with |
|---|---|---|---|
| **0** | Baseline, spikes, decisions | — | Decisions answered. **Done; closing** |
| | **Iteration 1** | | |
| 1 | Scope cut and parking | 0 | Smaller reactor; kept consumer runs unchanged |
| 2 | Foundation: core + errors, Spring-native | 1 | Lab "first service" |
| 3 | Logging + observability, Spring-native | 2 | Lab "logging and observability" |
| 4 | Data (Spring Data JPA) | 2 | Lab "data" |
| 5 | Outbox trial: Spring Modulith vs the platform outbox | 1 | **Owner stop: ND5** |
| 6 | Messaging + outbox on Spring AMQP | 4, 5 | Labs "messaging" and "outbox"; composition **C1** |
| 7 | Iteration 1 release check | 1–6 | Release decision for **1.0.0**. **Owner stop** |
| | **Iteration 2** | | |
| 8 | REST API with basic auth | 7 | Lab "REST API" |
| 9 | REST client + resilience, Spring-native, with basic auth and OAuth client credentials | 8 | Lab "REST client"; composition **C2** |
| 10 | Iteration 2 release check | 8, 9 | Release decision for **1.1.0**. **Owner stop** |

**Why this order:**
- The cut comes first, so no phase ports code that is about to be deleted.
- Foundation comes next because every other capability's errors and trace IDs go through it.
- The outbox trial is separate from the messaging rewrite because its result decides what Phase 6 builds.
- REST API comes before REST client so that C2 can test an authenticated request that makes an outbound call.

**What "you can stop here" means.** An iteration is finished when its release check (Phase 7 or 10) passes:
- a clean full build;
- every lab of the iteration passes the lab runner;
- the archetype generates exactly the iteration's capabilities;
- the docs and the BOM list only what ships (parked modules excluded);
- the scans are clean;
- a release decision is written.

`develop` is then a releasable 1.0.0 (or 1.1.0). Nothing from a later iteration is half-done in it.

---

## 4. Rules that bind every phase

These carry over from v3 §3 (summarised here), with the changes this plan makes.

1. **Truthful status.** Every build, test, harness run and Codex call is awaited to the end, and its real exit code is
   recorded. No fire-and-forget, and CI never stands in for local evidence. A service under test is started, exercised and
   stopped by one script that returns one honest exit code.
2. **Full builds.** CI runs the whole reactor on every push. Locally, run a full `verify` at the end of each phase (in the
   foreground, `-T1`, §5.1); during a phase, run targeted `-pl … -am` builds.
3. **One phase at a time.** A phase starts only after the one before it is `PASS` (or `PASS` with exceptions the owner approved).
4. **Two Codex rounds per capability phase,** plus a short verification pass for any material change made after Round 2 (§6).
   Codex's words are quoted, never paraphrased into agreement. If Codex is unavailable, the phase is `BLOCKED`.
5. **Critical and High findings block.** Only the owner can approve an exception. A disagreement is not a resolution.
6. **Keep the architecture.**
   - **Stays:** BOM, parents, conditional auto-configuration, kill switches (`dc.platform.<cap>.enabled`), and
     `@ConditionalOnMissingBean` back-off.
   - **Removed:** a platform API module, wherever Spring already has the type (ND3, ADR-011).
   - **Needs a recorded decision:** a new module.
7. **Evidence before status.** Every "passes", "works" or "secure" names the command, its exit code, the evidence file and the
   evidence level (§6).
8. **No scope creep.** A foundation module touched along the way is not thereby certified.
9. **Catch downstream breaks early.** After changing a module others depend on, run
   `mvn -q -pl <changed> -amd -DskipTests test-compile`. A break found here belongs to the current phase.
10. **Re-test what you touch.** If a phase changes a module certified earlier, re-run that capability's tests, consumer run and
    lab, and note it in the issue register.
11. **Flaky is failing.** Concurrency, timing and container tests must pass three runs in a row
    (`-Dsurefire.rerunFailingTestsCount=0`).
12. **How the platform gives a service behaviour.** Use the **first** of these that works, and record which in `scope.md`:
    1. a property default in a lowest-precedence `EnvironmentPostProcessor` source (as logging and observability already do).
       It shows in `/actuator/env`, and any user value overrides it;
    2. a Spring Boot customizer bean (`RestClientCustomizer`, `RabbitTemplateCustomizer`, `Customizer<HttpSecurity>`, …);
    3. a platform bean of a **Spring** type, `@ConditionalOnMissingBean`, with a kill switch;
    4. a platform type in service code, only with an ND decision explaining why no Spring type fits.

    A `BeanPostProcessor` that rewrites a user's bean is not allowed: the user's bean wins, unchanged.
13. **Carry every guarantee.** Each phase that rewrites a capability builds a carry-over table (§8, Gate A) and proves each row
    with a test before it closes.
14. **Consumer counts are a floor.** A ported consumer run keeps at least its v3 assertion count, or `scope.md` lists every
    assertion dropped or changed and why. The floors:

    | Run | v3 count | Phase |
    |---|---|---|
    | Core + errors | 92 | 2 |
    | Logging + observability | 76 | 3 |
    | Data | 92 | 4 |
    | Outbox | 106 | 5, 6 |
    | RabbitMQ | 63 | 6 |
    | C1 composition | 106 + the RabbitMQ run's AC1 and AC5 | 6 |
    | REST client + resilience | 182 | 9 |

    REST API (Phase 8) and C2 (Phase 9) are new: the v3 counts came from JWT security and do not apply. Their floor is set at
    Gate A.
15. **`develop` stays green.**
    - **Each merge:** every phase merges into `develop` when it closes. The golden path, CI, and every consumer run and lab
      already ported must pass.
    - **`main`:** this plan never touches it.
16. **Zero-config acceptance.** Each capability ends its phase with a test where an app holding only the starter (plus the
    deliberate fail-closed settings) starts and meets the capability's contract. Those settings are listed on the
    capability's doc page:
    - data: `spring.datasource.*` and Flyway migrations;
    - messaging: a reachable broker;
    - outbox: its table in the service's migrations;
    - REST API: the basic-auth credentials (no default password ever ships).
17. **Every capability has a guided lab** (§5.10), and the phase is not closed until the lab passes the lab runner.

---

## 5. How we work

Everything here was learned the hard way in the hardening initiative.

### 5.1 Builds and long commands

- Before **every** Maven call (Git Bash), point at JDK 25. The shell defaults to 21:

  ```bash
  export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"
  ```

- **Invocation:**
  - Run `rtk proxy mvn -o -T1 -B -ntp -l <log> …`.
  - Full builds use `-T1`, never `-T1C`: this machine hits its native-thread limit.
  - CycloneDX and `deploy` refuse offline mode: run those without `-o`.
- **Per-module builds:** add `-DforkCount=0 -Djacoco.skip=true` when coverage is not the point.
- **Foreground only:** run full builds and consumer runs in the foreground. Idle background runs get killed for memory.
  - If a command will not fit the tool timeout, split it (per module, per group of test classes).
  - Last resort: run it in the background and watch it to the end, with the exit code written to a file.
- **Stale artifacts:** a stale `~/.m2` copy of a changed module causes `NoClassDefFoundError` downstream. Run
  `install -DskipTests` on the upstream modules first.
- **Metadata:** configuration-processor metadata merges need `clean` before `verify`.

### 5.2 Shell and file hygiene

- **No heredocs or long quoted one-liners for files or commit messages:** they break in this shell. Write the file with the
  Write tool (scratchpad for temporary files) and pass its path: `git commit -F <file>`, `gh pr create --body-file <file>`,
  `python -I -X utf8 <script>`.
- **UTF-8:** Python output redirected to a file is cp1252 here, and `-I` ignores `PYTHONIOENCODING`.
  - Always use `-X utf8`.
  - Check generated text decodes as UTF-8 before committing: `DocsLinkCheckTest` fails otherwise.
- **Staging:**
  - Never stage `.serena/`, `dc-platform-chassis-review-fix-prompt.md`, `*.stackdump` or `properdocs.yml`.
  - Stage by name, never `git add -A`.
- **Deleting:** tell the owner before deleting anything outside this plan's own changes. `~/dc-hardening-runs` is not approved
  for deletion.

### 5.3 Codex

- **Invocation:** `codex exec --skip-git-repo-check -s read-only - < prompt > out`, as
  [`../production-readiness/README.md`](../production-readiness/README.md) documents. Recreate any wrapper script from there;
  scratchpad scripts do not survive a session.
- **Independence:** the first prompt of each round gets only `scope.md`, the diff against the phase start commit, and paths to
  read. Claude's conclusions go in only at the rebuttal step.
- **Each finding:** Codex gives a severity, `file:line`, a concrete failure scenario, its reasoning and a fix.
- **Records:** prompts and raw answers are saved verbatim under `docs/spring-native/phase-XX-<slug>/codex/`.
- **Base commit:** each prompt names its base commit. Before sending, the runner checks that the commit resolves and the diff is
  not empty: a review that read nothing is not a review.
- **Usage limit:** wait for the reset and retry; never buy credits. The phase is `BLOCKED` until Codex answers.

### 5.4 Branches, commits, tags, PRs

- **Branches:** `develop` is long-running, created from `main` at `bdf2845`. Never open a PR into `main` and never merge
  `develop` into `main`: the owner does that.
- **One branch per phase:** `native/phase-XX-<slug>` off `develop`, with one PR targeting `develop`.
- **Closing a phase:** merge without asking once the phase is closed and CI is green; tag the merge commit
  `native/phase-XX-closed`.
- **Plan amendments:** their own small PR into `develop`.
- **Commits:**
  - Conventional commits, one logical change each, with a trailer `[native phase-XX gate-Y]`.
  - End with the `Co-Authored-By` line naming the model actually in use, and the `Claude-Session` URL.
- **Changelog:** `CHANGELOG.md` under `## [Unreleased]` lists every removed type and its Spring replacement.
- **CI:** `ci.yml` runs on every PR and on pushes to `main` and `develop`. The nightly container job runs only on the default
  branch, so the local container runs in each phase stand in for it.

### 5.5 Picking up after a restart

`docs/spring-native/STATE.md` is updated at every gate: phase, gate, last commit, blockers, pending decisions, next action.
On any restart, read it first. Never work from memory.

### 5.6 When to stop and ask the owner

Stop and wait when:
- **Release checks:** at the end of each iteration (Phases 7 and 10);
- **ND5:** when the outbox trial ends, the owner confirms it against the evidence;
- **Blocking findings:** a Critical or High finding cannot be fixed inside the phase;
- **Exceptions:** an exception is needed (written to `exceptions.md` as `PENDING`, with risk, blast radius, mitigation and
  expiry);
- **Missing environment:** an environment is missing and the phase would become `BLOCKED`;
- **Platform types:** a carried guarantee can only be kept with a platform type in service code (rule 12, step 4).

Do not stop between phases otherwise, and do not ask about anything this plan already decides.

### 5.7 Evidence hygiene

- **Where it goes:** trimmed evidence goes to `phase-XX-<slug>/evidence/` (about 2 MB per file at most). Raw logs go to the
  git-ignored `evidence-raw/`, referenced by path and SHA-256.
- **Scan first:** before committing evidence, scan it for `eyJ`, `Bearer `, `Basic `, `password`, `secret`, connection
  strings and personal data.
- **Redact:** redact what the scan finds, and say so in the evidence.

### 5.8 Generated documentation

- **Full builds only:** `docs/reference/*`, `llms-full.txt` and the skill resources are generated from a **full** build: the
  generators read other modules' `target/`. Never commit them from an isolated `-pl` build.
- **Docs checks:** every docs change passes `python -m mkdocs build --strict` and `DocsLinkCheckTest`. `mkdocs.yml` must not
  name a page that no longer exists.

### 5.9 Consumer runs

A consumer run proves the capability from the outside: a service generated by the archetype, run against real
infrastructure, by a script.

- **Scenarios:** `tooling/hardening/scenarios/<scenario>.sh`, probe code in `tooling/hardening/scenarios/phase-0X/`.
- **Local repository:** a fresh one per phase, via `bash tooling/hardening/phase-repo.sh <phase> <modules>`. It checks the
  baseline manifest and copies it to `~/.m2-hardening/<phase>`.
  - Never install into `~/.m2-baseline` or `~/.m2-native-baseline`.
- **Generate outside the repository:** runs land in `~/dc-hardening-runs/native-<phase>/`.
- **Check the install:** install only the parents, BOM, archetype and the starters under test plus their upstream modules.
  Then check one installed POM has no unresolved `${revision}`.
- **Ported runs:** the probe app's code moves to Spring types; infrastructure, faults and assertions stay (rule 14).

### 5.10 Guided labs

Every capability gets a lab. A lab takes a developer from nothing to a service generated by the archetype, built, started,
and showing the capability working.

- **Where:**
  - Each lab lives in `docs/labs/<capability>/index.md`.
  - `docs/labs/index.md` lists the prerequisites and the order.
  - Labs have their own "Labs" section in the `mkdocs.yml` nav.
  - A lab links to its capability's sample and module page; it never copies them.
- **Shape:**
  1. what you will build and what you need (JDK, Maven, Docker, which containers and how much memory);
  2. step 0, getting the platform into a local Maven repository (nothing is published yet);
  3. generating the project with `mvn archetype:generate` and the options this capability needs;
  4. the minimum configuration, each value explained;
  5. the smallest code that uses the capability, with Spring types only;
  6. starting the app and seeing it work: the requests to send and the responses or log lines to expect;
  7. one failure to try, and what the platform does about it;
  8. cleanup, and where to go next.
- **The lab text is the test:**
  - Commands are marked ` ```bash lab-step `, and checks whose exit code must be 0 are marked ` ```bash lab-check `.
  - `tooling/labs/run-lab.sh <capability>` runs them in order, in a fresh directory outside the repository. It uses a clean,
    dedicated local Maven repository, starts and stops only the containers the lab names, and returns one honest exit code.
  - A lab that has not passed the runner on the phase's final commit is not done. None of its commands may be called tested.
- **Infrastructure (ND11):**
  - Iteration 1 labs use H2 first, then Oracle Free, plus RabbitMQ. No identity provider is needed.
  - Iteration 2 decides how the REST client lab gets an OAuth token endpoint (ND11b).
- **Fail-closed stays:** a lab never turns a protection off to make startup easier.

---

## 6. Words we use

- **Severity:**

  | Severity | Meaning | Effect |
  |---|---|---|
  | Critical | Data loss or corruption, security bypass, one caller seeing another's data, a non-idempotent operation run twice, or a fault that takes down every replica | Blocks; owner exception only |
  | High | Wrong behaviour under a realistic failure (outage, slowness, restart, concurrency), internals leaked in responses or logs, broken override, misleading health | Blocks; owner exception only |
  | Medium | Worse to operate or use, with a workaround; a failure path with no observability | May stay `OPEN` with an owner and a date |
  | Low | Cosmetic, docs, naming | May stay `OPEN` |

- **Finding status:**
  - `FIXED`: code and test.
  - `ACCEPTED`: disproved or not applicable, with evidence.
  - `OPEN`: tracked, with owner, mitigation and target.
  - `BLOCKED`: cannot be verified.
  - A Critical or High finding is `OPEN` only with an approved exception.
- **Evidence level:**
  - `UNIT`.
  - `SLICE`: a Spring slice or `ContextRunner` test.
  - `CONTAINER`: a real provider in Testcontainers.
  - `CONSUMER`: an archetype-generated app, really run.
  - `ENVIRONMENT`: real OpenShift or a real RAC.
  - Every claim names its level. Claiming a provider works needs at least `CONTAINER` and `CONSUMER`.
- **Degradation states:** each backing dependency is tested in four states, plus recovery from each back to healthy:
  - healthy;
  - **slow** (p99 × 20, via Toxiproxy). Slowness exhausts pools and cascades, so it is never skipped;
  - erroring;
  - unavailable.
- **Material change:** a change after Round 2 that touches `src/main` beyond a trivial fix, changes a public type, a default,
  a posture, or a contract in `scope.md`. It gets a Codex verification pass: one prompt with the diff and the finding.
- **Guarantee kinds:**
  - **Carried:** a behaviour the hardening initiative proved (an issue `FIXED` or `ACCEPTED`, or a §7 contract) that must still
    hold.
  - **Retired:** a guarantee that is now Spring's job, proven by a test against Spring.
  - **New risk:** a failure the Spring-native design opens up that the old API prevented.

---

## 7. The quality bar for every capability

Each phase shows, with tests, how its capability meets each of these contracts. Contracts 1–11 come from v3 §7; 12–14 are new.

1. **Real override.** For every platform bean, a test where the service defines its own and the platform's backs off.
2. **Default snapshot.** A test snapshots every `@ConfigurationProperties` default. A changed default fails until the
   snapshot is updated on purpose and the change logged.
3. **Kill switch.** The capability is `DEFAULT_ON` or `OPT_IN`, and `enabled=false` is tested.
4. **Outage posture.** The capability states and tests what happens in every degradation state (§6): fail-open,
   fail-closed or fail-durable. The test records what the **request** gets, not only what the component does.
5. **Health.** Liveness never depends on anything outside the pod. Readiness asks "can this pod serve", so a shared dependency
   (database, broker) is not in readiness: its outage would mark every replica unready at once. Shared dependencies get their
   own `dependencies` health group, for dashboards.
6. **Context propagation.** The trace ID and MDC survive every thread or process boundary the capability adds (executors,
   listener containers, retries, the outbox drainer). MDC is cleared after each unit of work. A concurrency test checks for
   bleed between requests. A boundary with no inbound context (a background drainer) restores context from the stored
   record, or uses a defined system context.
7. **Error surface.** Every error is an RFC 9457 `ProblemDetail` with a stable code. No exception message, SQL, stack trace or
   downstream body reaches a response.
8. **Redaction.** Logs, span attributes and stored payloads (outbox rows, dead-lettered messages, captured error bodies) carry
   no tokens, passwords or listed personal data.
9. **Observability.** The capability's own work emits an `Observation` (span + timer) with low-cardinality tags only. A test
   checks that no tag takes an unbounded value (IDs, keys, raw paths, user names).
10. **Graceful shutdown.** On SIGTERM the capability stops taking work, finishes or safely abandons in-flight work within the
    lifecycle timeout, and releases pools, channels and consumers. Verified in the consumer run.
11. **Startup under adversity.** The app starts within budget with healthy infrastructure **and** with its dependency slow.
    An outage at startup gives a clear `FailureAnalyzer` message or a defined degraded start, never a hang.
12. **Spring types only.** The consumer probe app imports no platform type, unless an ND decision allows one. An ArchUnit
    test or a `grep` in the harness fails the run otherwise.
13. **Back-off to the user's Spring bean.** For every Spring-type bean the platform supplies (`RabbitTemplate`,
    `RestClient.Builder`, `SecurityFilterChain`, …), a test where the service supplies its own:
    - the platform's bean steps back;
    - the service's bean is used unchanged;
    - any guarantee lost as a result (for example, timeouts on a hand-built `RestClient`) is written on the doc page.
14. **Zero-config acceptance** (rule 16).

---

## 8. The capability recipe

Every capability phase, in this plan and later ones, runs these gates in order. A phase section below lists only what is
specific to it.

### Gate A — Look, measure, scope

1. Record the phase start commit. Run the capability's **current** tests and record the results, including skips, before
   changing anything.
2. Write `scope.md`:
   - the requirements and what is excluded;
   - the contracts from §7 that apply;
   - acceptance criteria, each one testable;
   - environment prerequisites.
3. **Carry-over table** in `scope.md`. One row for every `FIXED` or `ACCEPTED` issue and every §7 contract that applies:

   | Guarantee | Source | Lives today in | Lives after in | Proof | Kind |
   |---|---|---|---|---|---|
   | One line | HI-ID or §7 number | File path | File path, once written | Test class and method | `CARRIED` / `RETIRED` / `NEW RISK` |

   Every `NEW RISK` row gets a mitigation (a startup check, a test, or a documented limit) and an issue-register entry.
4. **Removal list:** the types this phase deletes and every call site, from the Phase 0 removal map.
5. **Port plan** for the consumer run, with its floor (rule 14). A new capability writes its scenarios here and sets its own
   floor.

### Gate B — Build and test

- **Changes:**
  - Delete the API types (or slim the module).
  - Move the behaviour into auto-configuration (rule 12).
  - Port the consumer probe app to Spring types.
  - Update the archetype and the OpenRewrite recipe `tooling/platform-migrations/.../adopt-platform.yml`.
- **Tests:** regression, negative, concurrency and degradation tests, in targeted runs.
- **After each change:** run the downstream compile check (rule 9) and re-test what you touched (rule 10).
- **Record:** the exact commands, exit codes and test counts, including skips.

### Gate C — Codex round 1

1. Codex reviews independently (§5.3). Its prompt includes the carry-over table as a requirement, and asks:
   - is any guarantee lost?
   - is any `RETIRED` row not really covered by Spring?
   - what new risk is missing?
2. Claude answers every finding (agree, partly agree, or disagree) with code references and test evidence.
3. Codex reassesses each answer.
4. Claude fixes what is warranted, re-runs the targeted tests and writes `review-round-1.md`.

### Gate D — Codex round 2

Codex reviews the revised code from the phase start commit: it confirms round 1's outcomes and hunts for regressions. Claude
rebuts with evidence, and Codex re-evaluates. Unresolved disputes are recorded word for word. Any material change after this
round gets a verification pass. Claude writes `review-round-2.md`.

### Gate E — Settle findings

Update `../production-readiness/issue-register.md` (numbering continues from HI-0110):
- **Each entry:** severity, location, reproduction, Codex's claim, Claude's reply, evidence, status, fix commit, test.
- **Blocking:** a Critical or High finding that is not `FIXED` or `ACCEPTED` stops the phase for the owner.

### Gate F — Consumer run

Generate a fresh service from the archetype, with only the capability under test plus the foundation, and run the
scenarios in the foreground:
- **What runs:** happy path, negative cases, every degradation state that applies, recovery, concurrency and graceful
  shutdown.
- **What counts:** the run asserts real outcomes (response bodies, rows, queue contents, log fields), not only `200` or `UP`.
- **To pass:** three passes in a row, at or above the floor.
- **No workarounds:** a workaround in the consumer is a chassis or archetype defect. Fix it and re-run.

### Gate G — Composition (Phases 6 and 9)

A second generated app holds every capability so far, and runs the cross-capability scenarios. A failure here belongs to the
current phase.

### Gate H — Docs and closure

1. **Module page.** `docs/modules/<cap>.md` is written around Spring types:
   - what the platform configures;
   - which Spring extension point changes it;
   - a zero-config table (what works with only the starter, what must be set);
   - the back-off limits (contract 13).
2. **Book chapter.** Update it, or log an issue to do it later.
3. **Sample.** `docs/samples/<slug>/` holds `README.md`, `commands.md`, `verification.md` (expected **and** actual results,
   with evidence levels), `troubleshooting.md` (each entry marked observed or anticipated), `open-issues.md` and
   `operations.md` (posture table, health groups, metrics to alert on, kill switch, shutdown behaviour, how to replace a bean).
4. **Lab.** Written, and passing the lab runner on the phase's final commit.
5. **Archetype.** It offers the capability as an option, with the dependencies and configuration the lab uses.
6. **Checks.** `mkdocs build --strict` and `DocsLinkCheckTest` pass. Local full `verify` passes.
7. **Closure.**
   - Write `closure.md`, with a `PASS`, `PASS WITH HUMAN-APPROVED EXCEPTIONS` or `BLOCKED` verdict.
   - Update `STATE.md` and `capability-status.md`.
   - Merge the PR on green, and tag `native/phase-XX-closed`.
8. **End-of-phase report.** Post it to the owner, then continue. It covers:
   - the verdict;
   - the carry-over table with proofs;
   - the removed types;
   - the consumer count against its floor;
   - the lab-runner result;
   - open issues.

---

## 9. Phase 0 — Baseline, spikes, decisions (done; closing)

- **Done.** Baseline `bdf2845`, tagged `pre-spring-native`. Compatibility baseline in `~/.m2-native-baseline`.
- **Spikes S1–S7:** see [`phase-00-baseline/spikes.md`](phase-00-baseline/spikes.md). With the new scope, what still
  matters:
  - S1 (Modulith on Boot 4.1.2 with Oracle and AMQP) for the outbox;
  - S5 (structured logging) for logging;
  - S7 (`ProblemDetail` advice) for errors;
  - S3 and S4 (HTTP service clients, `@Retryable`) for iteration 2;
  - S2's `Customizer<HttpSecurity>` finding, for basic auth.
  - S6 (method security) no longer applies: there is no authorization layer.
- **Other records:** the inventory, the removal map and `expected-removals.md`.
- **Decisions:** answered by the owner on 2026-11-19 (see [`decisions.md`](decisions.md)).

**To close:**
1. Update the removal map and `expected-removals.md` to the new scope. The cut modules' types now go in Phase 1.
2. Run one Codex round over the spikes, the removal map and this plan. It asks:
   - are the replacements real?
   - does anything kept depend on something cut?
   - is any guarantee left without a home?
3. Merge PR #7 on green, and tag `native/phase-00-closed`.

---

## 10. Iteration 1

### Phase 1 — Scope cut and parking

**Goal:** a smaller reactor. Nothing kept changes behaviour, except the three optional JWT links (§2.5).

1. Delete the cut modules (§2.5): from the reactor `pom.xml`, from `build/platform-bom` and `build/platform-dependencies`,
   and their directories. Remove third-party versions that only cut modules used (Kafka, Redis/Lettuce, AWS SDK,
   OpenFeature, springdoc, …), and confirm with a dependency tree of every kept module.
2. Remove the optional JWT-security links from data, outbox and restclient. Their tests that used `CurrentUser` assert the
   fallback instead.
3. Park `restclient/*` and `resilience/*` (§2.4): out of the BOM, `maven.deploy.skip=true`, out of the archetype, with a
   note on their doc pages.
4. **Archetype:**
   - Remove the starters for validation, OpenAPI and security.
   - Remove the in-memory messaging default: messaging becomes RabbitMQ.
   - Keep the `database=h2|oracle` and `features=data,messaging,outbox` options.
5. **Examples:** keep `example-minimal` and `example-golden-path` (pruned to kept capabilities). Delete
   `example-extension-provider` (it shows an SPI that goes) and `example-event-driven` (it uses the cut in-process events and
   Kafka) unless Gate A finds it uses only kept modules.
6. **Rules:** apply ND3 (ADR-011) to `CLAUDE.md` rules 4, 5 and 9, the enforcer and the ArchUnit rules. Remove rules about
   cut modules.
7. **Docs:**
   - Delete the cut module pages, book chapters 04, 05 and 09–13, `docs/samples/security` and their reference entries.
   - Update `mkdocs.yml` and the compatibility appendix. Regenerate `docs/reference/*`, `llms-full.txt` and the skill resources
     from a full build.
   - Do **not** edit the closed hardening record (`../production-readiness/final-release-assessment.md`,
     `release-decision.md`, the phase folders): it is the evidence behind tag `hardening/phase-08-closed`.
   - Add one line at the top of the assessment and of the release decision: "Superseded on `develop` by
     [`docs/spring-native/`](../spring-native/plan.md): the realigned platform targets 1.0.0 (ND1)."
8. **Compose file** (ND11):
   - Remove Kafka, Redis, Vault, LocalStack and Postgres from `docker-compose.local.yml`.
   - Keep RabbitMQ, and add Oracle Free with its memory stated.
9. **Build:** fix HI-0109 (`develop` push builds hitting Maven Central 429s) first, so the cut is judged on a green CI.
10. **Verify:**
    - a full `verify`, with no test newly skipped;
    - the golden path (its count may drop where a step covered a cut module, with each dropped step justified);
    - the kept v3 consumer runs at their v3 counts: core + errors, logging + observability, data, outbox, RabbitMQ, C1 and
      REST client (still runs while parked);
    - the docs checks;
    - Trivy on a generated service: still no Critical or High.
11. **Review:** one Codex round. Its question: does anything kept depend on, document or ship anything removed?

### Phase 2 — Foundation: core + errors, Spring-native

**Specific to this phase:**
- **Errors:**
  - The platform `@RestControllerAdvice` handles Spring's `ErrorResponse` types and `@ResponseStatus`.
  - It adds `code`, `traceId`, `timestamp` and `retryable` to every `ProblemDetail` (proven by S7).
  - It maps Bean Validation failures (`MethodArgumentNotValidException`, `HandlerMethodValidationException`) to a 400 with
    field paths and no internal detail. This replaces what the cut validation module did.
  - Gate A decides how a service sets its own error code without a platform type: a `ProblemDetail` property set by the
    thrower, the `type` URI, or a resolver bean.
- **Core:** remove the API types in the removal map. The correlation filter, the executor context propagation and the banner
  are already auto-configuration.
- **Data value types (ND7):** delete `EntityId`, `Money` and `PersistenceConventions` here, since errors and data both used
  them in tests.
- **Carried from v3 (to confirm at Gate A):**
  - `traceparent` is the only correlation header (HD5);
  - an injected header is rejected;
  - no message, SQL or stack trace reaches a response;
  - MDC is empty after each request;
  - 200 parallel requests show no context bleed;
  - framework exceptions keep their statuses;
  - error codes are unique at build time.
- **Consumer floor:** 92.
- **Lab "first service":** generate a service, start it, call an endpoint that fails, and read the `ProblemDetail` with its
  `traceId`. Then find the same trace ID in the log.

### Phase 3 — Logging + observability, Spring-native

**Specific to this phase:**
- **Logging:**
  - Remove `Kv` and `LogSanitizer`. Services log key-value pairs with SLF4J's fluent `addKeyValue` (S5).
  - Gate A decides between the platform's Logback configuration and Boot's structured logging with masking as a Logback
    component.
  - Masking is carried either way: JWTs, `Authorization` headers (now including `Basic`), passwords and personal data,
    including the regex boundary cases Codex found in v3.
- **Observability:**
  - Already configuration-only. Check that nothing depends on a removed type.
  - The tag-cardinality guard, the health groups (HD6) and the actuator exposure stay.
- **Actuator without security (ND13):** iteration 1 ships no web security, so the management endpoints must be safe on their
  own. Apply ND13's answer here, and prove at Gate F that `/actuator/prometheus` is not reachable on the application port.
- **Consumer floor:** 76.
- **Lab "logging and observability":**
  - log a key-value pair and see it as JSON;
  - log a password and see it masked;
  - scrape a metric;
  - follow one trace across an HTTP call.

### Phase 4 — Data (Spring Data JPA)

**Specific to this phase:**
- **Already Spring-native:** data was certified on Oracle in v3, with `JpaRepository`, `@Transactional` and Flyway. This
  phase confirms it with the new recipe instead of rewriting it.
- **Auditing:** the JPA auditor uses `"system"` until iteration 2 adds a principal. Document it.
- **Carried from v3:**
  - transaction boundaries;
  - pool sizing and the slow-database behaviour (a slow database must not exhaust the pool and hang requests);
  - Flyway on startup;
  - Oracle type mappings;
  - no SQL in error responses.
- **Consumer floor:** 92, on Oracle Free.
- **Lab "data":** an entity, a repository and a Flyway migration on H2. Then switch the same project to Oracle Free and
  run it again. Failure to try: stop the database mid-request, and read the `ProblemDetail` and the health group.

### Phase 5 — Outbox trial (Spring Modulith vs the platform outbox)

A time-boxed comparison, not a rewrite.

1. Build a trial of the outbox consumer app on Spring Modulith's event publication registry and AMQP externalisation, on Oracle
   and RabbitMQ.
2. Run the v3 outbox scenarios (106 assertions) against it, and against the platform outbox on the same commit.
3. Compare the carried guarantees row by row:
   - **Write path:** same transaction and `DataSource` as the business write; a stable event ID generated once.
   - **Drainer:** bounded batches; `FOR UPDATE SKIP LOCKED` (or equivalent) across two instances; a separate, bounded
     connection budget.
   - **Failures:** rejected or unroutable messages parked, never retried forever.
   - **Operations:** lag and parked metrics; trace context restored on drain; retention; stop timeout and shutdown order.
   - **Parked issues:** HI-0071, HI-0079 and HI-0086. Does Modulith have them, or different ones?
4. Check Modulith's seven open items from S1 (M1–M7), among them:
   - a 4000-byte payload column where the platform outbox has a `BLOB`;
   - DDL that needs Oracle 23c while production runs 19c;
   - unconfirmed publisher-confirm handling.
5. Write `phase-05-outbox-trial/comparison.md` and run one Codex round on it.
6. **Stop for the owner.** The rule agreed in advance: Modulith wins only if it passes every scenario and every carried
   guarantee (or each gap has an accepted mitigation). Otherwise the platform outbox stays.

### Phase 6 — Messaging + outbox on Spring AMQP

**Specific to this phase:**
- **Using it:** services publish with `RabbitTemplate` (or through the outbox ND5 chose) and consume with `@RabbitListener`.
- **What the platform supplies:**
  - a message converter;
  - publisher confirms and returns (an unroutable message is an error, not silence);
  - header post-processors (event ID, type, version, `traceparent`);
  - a listener container factory with bounded retry, backoff and dead-lettering;
  - quorum queues by default.
- **Declaring a subscription:** without `@EventHandler`, Gate A decides how a service declares one: plain `Declarables` beans
  or a property-driven topology.
- **Removed:** the platform messaging API, the SPI, the in-memory provider and the messaging TCK.
- **Carried from v3:**
  - one handler per message (two handlers that could receive the same message fail startup);
  - an unreadable payload or a newer version goes to the DLQ without quoting the payload;
  - the DLQ replay tool (`tooling/scripts/rabbit-dlq-replay.py`) still works;
  - the shutdown order;
  - the consumer-side dedupe pattern in the sample.
- **Consumer floors:** RabbitMQ 63, outbox 106.
- **Composition C1** (Gate G): logging, observability, data, outbox and RabbitMQ in one app. Scenarios:
  - HTTP request → database write + outbox row → drain → RabbitMQ → listener → database write, on **one trace**;
  - database slow while the broker is healthy;
  - broker down while the database is healthy;
  - app killed mid-flow: no loss, bounded duplicates;
  - drainer and request threads under load do not exhaust the pool.
- **Afterwards:** re-evaluate HI-0071, HI-0079 and HI-0086 under the chosen outbox.
- **Lab "messaging":** publish with `RabbitTemplate`, consume with `@RabbitListener`, and send a poison message to watch it
  land in the DLQ.
- **Lab "outbox":** save an order and publish its event in one transaction. Stop RabbitMQ, save more orders, start RabbitMQ,
  and watch the backlog drain with nothing lost.

### Phase 7 — Iteration 1 release check

A release candidate check of everything iteration 1 ships. No new features.

1. **Clean build:** a full `verify` (foreground, `-T1`).
2. **Containers:** the container suites for Oracle and RabbitMQ (with Toxiproxy).
3. **Archetype:**
   - it generates the iteration 1 set (foundation, logging, observability, and on request data, messaging and outbox);
   - the golden path passes;
   - one generated app with **every** iteration 1 capability runs C1 plus the request → outbox → broker → listener chain on
     one trace.
4. **Regressions:** every consumer run of the iteration again on this commit, and **every iteration 1 lab** through the lab
   runner.
5. **Scans:** Trivy, a CycloneDX SBOM, and a secrets scan including git history (the repository is public).
6. **Compatibility:**
   - japicmp output matches `expected-removals.md` (an unlisted break is a finding);
   - the config-default snapshot diff;
   - BOM parity between `platform-service-parent` and a plain `platform-bom` import;
   - parked modules are absent from the BOM and from the deploy.
7. **Docs:** only iteration 1 capabilities and the foundation are documented as shipped. Parked modules carry their
   "iteration 2" note. Both docs checks pass.
8. **Release rehearsal:** a reproducible build, and close HI-0108 (SBOM and provenance in `release.yml`; `release.sh`
   without a hard-coded `-T1C`).
9. **OpenShift:** with the team's chart tool, `BLOCKED` unless the owner supplies the details (HD7).

Then write `phase-07-iteration-1/run-results.md`, `iteration-1-release-assessment.md` and `iteration-1-release-decision.md`:
- what is certified, and the open issues;
- the known limits, and the conditions for a first adopter;
- carried forward explicitly: HI-0071, HI-0079, HI-0086 (or Modulith's equivalents), HI-0106, HI-0107, Oracle RAC
  (`BLOCKED`), OpenShift (`BLOCKED` unless run).

**Stop for the owner:** release 1.0.0, continue to iteration 2, or stop here.

---

## 11. Iteration 2

### Phase 8 — REST API with basic auth

A new capability. The gateway authenticates the caller (JWT or OAuth client credentials) and calls the service with basic
auth. The service checks only that basic-auth credential.

**Specific to this phase:**
- **What the platform supplies:**
  - a `SecurityFilterChain` that requires HTTP basic on every application path;
  - credentials from configuration, with the password as a `{bcrypt}` hash, or from a secret mounted by OpenShift;
  - stateless sessions, no CSRF for a pure API, no form login, no `WWW-Authenticate` realm leak beyond the standard header;
  - 401 as a `ProblemDetail`.
- **Customizing:** a `Customizer<HttpSecurity>` bean (S2), or the service's own `SecurityFilterChain`, which backs the
  platform's off (contract 13).
- **Fail-closed:** no credential configured means the app does **not** start, with a `FailureAnalyzer` message naming the
  property. Boot's generated default password must never be active.
- **Open paths:** only the health probes. A startup check fails the app if a configured permit path opens anything else
  (carried from v3's HI-0099 idea).
- **Principal back in context:**
  - the JPA auditor and the outbox principal now read the authenticated user from `SecurityContextHolder`;
  - the masking covers `Authorization: Basic`.
- **Gate A decides:** whether more than one credential (one per calling gateway) is needed, and the module layout under
  `rest-api/`.
- **Consumer floor:** set at Gate A. It must at least cover:
  - no credential, a wrong credential and the right one;
  - every path except probes is protected;
  - no password in logs;
  - the failure to start without a configured credential;
  - concurrency with two credentials and no principal bleed.
- **Lab "REST API":** generate a service with `features=rest-api`, set a credential, call an endpoint with and without it,
  and send an invalid body to read the validation `ProblemDetail`.

### Phase 9 — REST client + resilience, Spring-native, with outbound auth

Unpark `restclient/*` and `resilience/*`, and rewrite them.

**Specific to this phase:**
- **Using it:** services inject Boot's `RestClient.Builder` or declare `@HttpExchange` interfaces (S3).
- **What the platform contributes,** as `RestClientCustomizer` beans:
  - timeouts and the overall deadline;
  - tracing;
  - bounded, redacted error-body capture;
  - logging.
- **Outbound auth, per client, by configuration:**
  - **basic:** a username and password for that downstream;
  - **OAuth2 client credentials:** Spring Security's `OAuth2AuthorizedClientManager` with
    `OAuth2ClientHttpRequestInterceptor`. Tokens are cached and refreshed before expiry, and a 401 from the token endpoint
    is not retried.
  - Tokens and passwords never reach logs, spans or captured error bodies.
- **Retries (carried, HD2):**
  - POST and PATCH are **never** retried, with or without an `Idempotency-Key`;
  - a 4xx is not retried; `Retry-After` is honoured;
  - this holds whichever mechanism S4 selects.
- **Errors:**
  - downstream failures surface as Spring's `RestClientResponseException` / `ResourceAccessException`;
  - the errors advice maps them to a `ProblemDetail` with `retryable`, never leaking the downstream body.
- **Contract 13 matters most here:** a service that builds its own `RestClient` without the builder loses the platform's
  timeouts. Document it, and consider a startup WARN.
- **Consumer floor:** 182, plus the new auth scenarios:
  - token fetched once and reused;
  - token endpoint slow or down;
  - token expiry mid-run;
  - wrong client secret;
  - basic credential rejected by the downstream.
- **Token endpoint for tests and the lab (ND11b):** Gate A picks Keycloak in the compose file, or a small local token
  endpoint built on the test kit.
- **Composition C2** (Gate G): REST API (basic) + REST client (OAuth client credentials) + data + observability. Scenarios:
  - a basic-auth request → database write → outbound call with a client-credentials token, on one trace;
  - the downstream returns 401, 403 or 5xx: mapped to a `ProblemDetail`, nothing leaked, the 401 not retried;
  - the circuit breaker opens on the downstream while the database is healthy;
  - the database slow while the downstream is healthy;
  - two callers concurrently with no principal bleed;
  - shutdown with outbound calls in flight.
- **Lab "REST client":** call a downstream with basic auth, then switch the same client to client credentials. Failure to
  try: the downstream slows down, and you watch the timeout and the `ProblemDetail`.

### Phase 10 — Iteration 2 release check

As Phase 7, over the iteration 1 and 2 capabilities together. Iteration 1's labs and consumer runs are re-run too, since
Phase 8 touched data and outbox. Write the iteration 2 release assessment and decision.

**Stop for the owner:** release 1.1.0, or stop.

---

## 12. Deliverables layout

```text
docs/
  spring-native/
    plan.md  STATE.md  decisions.md  capability-status.md  exceptions.md  expected-removals.md
    phase-00-baseline/           # inventory.md, spikes.md, removal-map.md, codex/, evidence/
    phase-01-scope-cut/
    phase-02-foundation/
    phase-03-logging-observability/
    phase-04-data/
    phase-05-outbox-trial/       # comparison.md
    phase-06-messaging-outbox/   # composition-c1.md
    phase-07-iteration-1/        # run-results.md, release assessment and decision
    phase-08-rest-api/
    phase-09-rest-client/        # composition-c2.md
    phase-10-iteration-2/
    # each capability phase: scope.md, review-round-1.md, review-round-2.md, closure.md, codex/, evidence/
  production-readiness/issue-register.md   # continues from HI-0110
  decisions/decision-log.md                # continues from D135; adr-011.md (ND3)
  modules/<cap>.md                         # kept capabilities only
  samples/<slug>/                          # kept capabilities only
  labs/index.md, labs/<capability>/index.md
tooling/labs/run-lab.sh
```

`evidence-raw/` directories are git-ignored.

---

## 13. Start and restart

1. **Read:** `STATE.md`, then this plan. Read v3 only for detail a section here points to.
2. **Environment:** set it up per §5.1. Check `git status`, and never stage the files §5.2 lists.
3. **Work:** on a phase branch off `develop` (§5.4).
4. **Order:** close Phase 0 (§9), then run the phases in order. Respect the stops in §5.6.
   - Never start Phase 6 before the owner confirms ND5.
   - Never start a new iteration before the owner answers its release check.
5. **Conflicts:** if this plan conflicts with the repository (a module name, existing behaviour, tooling), do not improvise.
   Record the conflict in `STATE.md`, propose a fix, and ask.

---

## 14. Adding a capability later

New capabilities follow the same path. To add one (say, caching, or OpenAPI back again):

1. **Decide where it lands.** It joins the next iteration that is not yet closed, or opens a new one. Add it to §2 and §3
   with the new phase.
2. **Write its phase section** in this plan, the way Phases 8 and 9 are written:
   - what a service uses (Spring types);
   - what the platform supplies;
   - the carried guarantees (if it comes back from the cut list, start from its v3 or pre-cut issues);
   - the fail-closed settings;
   - the consumer floor;
   - the lab outline;
   - any ND decision it needs.
3. **Take decisions to the owner first.** If it needs an owner decision, add it to `decisions.md` with a recommendation
   before the phase starts.
4. **Run the recipe** (§8) from Gate A to Gate H. Add the capability to the archetype as an option, write its lab, module
   page and sample.
5. **Close with the iteration's release check** (as Phase 7): every lab and consumer run re-run, docs and BOM matching
   what ships, a release decision, and a stop for the owner.

A capability is not "in" until its lab passes on the closing commit and its release check is written.
