# Enterprise Microservice Chassis — Platform Architecture Design

**Codename:** `acme-platform` (substitute your organization's groupId: `com.acme.platform`)
**Target:** Spring Boot 3.x (latest stable), Java 21 LTS, Maven multi-module
**Status:** Implementation-ready design for incremental execution by Claude Code

---

## 0. Executive Summary

This document designs an internal Spring Boot platform ("chassis") delivered as a **Hybrid Starter + SPI Platform**: a family of small, feature-sliced Maven modules, each split into `api` / `spi` / `implementation` / `autoconfigure` / `starter` layers, aligned by a single BOM and released as a **release train**. Extension happens exclusively through Spring's own mechanisms (auto-configuration, `@ConditionalOn*`, `ObjectProvider`, `AutoConfiguration.imports`) plus narrow platform SPIs — never through a custom plugin runtime.

The result feels like "more Spring Boot," not a framework on top of Spring Boot. A new service needs one parent POM, one `application.yml`, one `@SpringBootApplication`, one controller, one service. Everything else is convention.

---

## 1. Architectural Reasoning (Mandatory Thinking Process)

### 1.1 Candidate architectures

Seven realistic approaches were identified, per the requirements:

1. **Modular Monolith Framework** — one (or few) large platform artifacts (`platform-all`, `platform-core`) containing every capability behind feature toggles.
2. **Starter-Based Platform** — one Spring Boot starter per capability, each pulling in auto-configuration and defaults; composition at the dependency level.
3. **Plugin-Based Platform** — a runtime plugin model (custom registry, classpath scanning, possibly classloader isolation) where capabilities are discovered and loaded dynamically.
4. **Hexagonal Platform** — the platform itself organized strictly as ports and adapters: a technology-free core with adapter modules for every technology.
5. **Layered Platform** — horizontal layers: `platform-common` → `platform-core` → `platform-services` → `platform-web`, each capability living inside a layer.
6. **Feature Modular Platform** — vertical feature slices (logging, messaging, security…), each a fully independent product with its own version, lifecycle, and release cadence.
7. **Hybrid Starter + Plugin Platform** — starters as the delivery mechanism, with explicit SPI extension points inside each capability so enterprises can add providers without touching platform code.

### 1.2 Evaluation and rejection

**Option 1 — Modular Monolith Framework: REJECTED.**
A single large artifact is the textbook God Framework. Every service inherits every transitive dependency (Kafka client, JPA, Redis, S3 SDK) whether used or not; classpath bloat degrades startup, native-image builds, and CVE surface. Any change to any capability forces a version bump of the whole platform, so upgrade risk is maximal and unrelated to what a team actually uses. Feature toggles inside one artifact replace compile-time modularity with runtime conditionals — hidden coupling, untestable combinations. This violates "small focused modules," "explicit dependencies," and "upgrade safety" simultaneously. Rejected without further scoring.

**Option 3 — Plugin-Based Platform (runtime plugins): REJECTED.**
A custom plugin runtime (registry, discovery, lifecycle, possibly isolated classloaders à la OSGi/PF4J) reinvents what Spring Boot auto-configuration already does, but worse: it introduces a second component model developers must learn, runtime magic that defeats IDE navigation and AOT analysis, and classloader hazards that break native-image compatibility. Spring's conditional auto-configuration *is* a compile-time-resolved plugin system with first-class tooling. Building a parallel one violates "reinventing Spring," "runtime magic," and "AOT readiness." Rejected. (Its one good idea — provider extensibility — survives as SPI interfaces in the hybrid model.)

**Option 5 — Layered Platform: REJECTED.**
Horizontal layers (`common`, `core`, `services`) accrete into God Modules: `platform-common` becomes the dumping ground with fan-in from everything, so every change ripples platform-wide. Layers group code by *kind* rather than by *capability*, which means a messaging change and a security change share release units and reviewers. High fan-in, poor ownership boundaries, worst-case upgrade blast radius. Rejected.

**Option 4 — Hexagonal Platform (as the overall organizing style): REJECTED as the top-level style, ADOPTED as an internal discipline.**
Hexagonal architecture is a superb pattern for *applications* with a domain core. The platform, however, has no business domain — it is infrastructure glue. Forcing every capability through a technology-free "core + adapter" shape produces abstraction for its own sake (e.g., a platform-neutral abstraction over OpenAPI or Actuator adds a layer with no second implementation ever). Where multiple providers genuinely exist (messaging, storage, cache, locking), the ports-and-adapters idea is exactly right — and is retained as the **API/SPI/provider split inside those capabilities**. As the global style: rejected; as a per-capability tactic: adopted.

**Options 2, 6, 7 survive** and are compared in depth below; the decision matrix (§14) quantifies the comparison.

### 1.3 Surviving architectures in detail

#### A. Pure Starter-Based Platform (Option 2)

*Philosophy:* Mirror Spring Boot itself. Each capability is `<cap>-autoconfigure` + `<cap>-starter`. Users add a starter; conditions do the rest.

*Strengths:* Zero learning curve for Spring developers; perfect Boot idiom; small modules; opt-in dependencies; excellent DX.

*Weakness:* Without a formal API/SPI discipline, "autoconfigure" modules tend to expose concrete classes as de-facto API; enterprises extending the platform (new storage provider, new auth provider) must fork or patch autoconfigure modules. Extensibility is implicit, binary compatibility is hard to promise, and internal packages leak.

*Structure sketch:*
```
platform-parent, platform-bom
platform-<cap>-autoconfigure
platform-starter-<cap>
```

#### B. Feature Modular Platform (Option 6)

*Philosophy:* Each capability is an independently versioned mini-product (own repo or own release cadence), like Spring Data's sub-projects.

*Strengths:* Maximal team autonomy and independent evolution; a security fix in messaging doesn't force a logging release.

*Weakness:* Independent versions create a compatibility matrix (logging 2.3 + security 1.9 + messaging 3.1 — tested together?). Spring solved this with the *release train* + BOM precisely because per-module versioning at consumer scale is a support nightmare. For an internal platform serving hundreds of teams with a small platform team, matrix testing cost dwarfs the autonomy benefit. Feature *slicing* is right; feature *versioning* independence is premature.

#### C. Hybrid Starter + SPI Platform (Option 7, refined)

*Philosophy:* Feature-sliced capabilities (from B), delivered as starters over auto-configuration (from A), with each multi-provider capability split into `api` (consumer-facing), `spi` (extender-facing), `impl` providers, `autoconfigure`, and thin `starter` modules (the hexagonal tactic from Option 4). One BOM, one release train, semantic versioning of the train.

*Why it wins:* It keeps the DX and idiom of pure starters, adds a contractual extension model so enterprises plug in providers without modifying platform modules, and avoids the version-matrix cost of independent versioning by shipping a coordinated train. Every "prefer" principle in the philosophy section maps to a concrete mechanism; every "avoid" item is structurally prevented (see §16).

*Where it may fail / trade-offs:* More modules than A (each capability is 2–5 modules instead of 2), so repository navigation and build orchestration need tooling (archetype, module generator, CI caching). The release train couples release timing (a hotfix to one capability ships a patch train). API/SPI discipline requires governance (ArchUnit + enforcer rules + review) or it decays into A's weakness. These costs are accepted and mitigated in §§10–12.

**RECOMMENDATION (final, after comparison): Option C — Hybrid Starter + SPI Platform.** The decision matrix in §14 quantifies this.

---

## 2. Architecture Overview of the Recommended Platform

### 2.1 Guiding philosophy

1. **The platform is a set of defaults, not a cage.** Every bean is `@ConditionalOnMissingBean`; every feature has a kill switch (`acme.platform.<cap>.enabled=false`); every default is overridable in `application.yml`.
2. **Consumers see APIs, extenders see SPIs, nobody sees internals.** Three audiences, three package families, three compatibility promises.
3. **Spring Boot mechanisms only.** Auto-configuration, conditions, `ConfigurationProperties`, `ObjectProvider`, ordering annotations. No custom lifecycle, registry, or DI.
4. **A capability you don't add costs you nothing.** No transitive reach into Kafka from the logging starter. Optional/provided scopes and `@ConditionalOnClass` everywhere.
5. **One train, one BOM, one truth.** All platform modules share a version; consumers import one BOM; upgrades are one property change.

### 2.2 Module taxonomy (per capability)

A capability `X` (e.g., messaging) is built from up to five module kinds:

| Module | Category | Contains | Depends on |
|---|---|---|---|
| `platform-X-api` | API | Consumer-facing interfaces, annotations, value types, exceptions | JDK, `platform-core-api`, minimal Spring (`spring-core` at most) |
| `platform-X-spi` | SPI | Provider/extension contracts (`XProvider`, `XCustomizer`) | `platform-X-api` |
| `platform-X-<provider>` | Implementation | One provider (e.g., `platform-messaging-kafka`) | `platform-X-api`, `platform-X-spi`, provider library |
| `platform-X-autoconfigure` | Auto Configuration | `@AutoConfiguration` classes, `@ConfigurationProperties`, conditions | api, spi, *optional* deps on providers |
| `platform-starter-X[-provider]` | Starter | **No code.** Only a `pom.xml` aggregating dependencies | autoconfigure + chosen provider |

Simple capabilities (single possible implementation, e.g., error handling with ProblemDetail) collapse to `api + autoconfigure + starter`. The SPI module exists **only** where a second provider is plausible. Never create abstraction speculatively.

### 2.3 Foundation modules

```
platform-parent            (Parent)   — build parent for platform modules themselves
platform-service-parent    (Parent)   — the parent POM consumed by application teams
platform-bom               (BOM)      — all platform artifacts, one version
platform-dependencies      (BOM)      — third-party version alignment; imports spring-boot-dependencies
platform-build-tools       (Build Plugin) — enforcer rules, ArchUnit rule jar, checkstyle/error-prone config
platform-build-maven-plugin(Build Plugin) — module scaffolding, api-diff (japicmp) orchestration
platform-core-api          (API)      — tiny: PlatformException hierarchy, TenantId/CorrelationId value types, @PlatformInternal/@PlatformApi annotations
platform-core-autoconfigure(Auto Configuration) — banner, context id, correlation propagation baseline
platform-starter-core      (Starter)  — the baseline starter every service gets via platform-service-parent
platform-test-api          (Test Support) — @PlatformTest slices, ApplicationContextRunner helpers, Testcontainers fixtures
platform-starter-test      (Starter/Test) — aggregates test support
platform-docs              (Documentation) — Antora/asciidoc site, generated config-props reference
platform-examples          (Examples)  — golden-path sample services (see §17)
```

`platform-core-api` is deliberately anemic (< 30 public types) and is the only module with platform-wide fan-in — reviewed under the strictest change policy (§12).

### 2.4 Dependency graph (per capability, arrows = "depends on")

```
starter ──▶ autoconfigure ──▶ impl(provider) ──▶ spi ──▶ api ──▶ core-api
                     └────────── (optional) ──────┘
```

Maximum depth from starter to JDK-only code: **4 platform hops**. Cross-capability edges are allowed only `api → api` and `autoconfigure → other-api` (e.g., audit autoconfigure may consume the messaging API to emit audit events, guarded by `@ConditionalOnClass`/`@ConditionalOnBean`). Never impl→impl, never starter→starter.

### 2.5 Package structure (convention, enforced)

For capability `messaging`:

```
com.acme.platform.messaging                  → public API (stable)
com.acme.platform.messaging.annotation      → public annotations
com.acme.platform.messaging.spi             → SPI (stable-for-extenders)
com.acme.platform.messaging.config          → @ConfigurationProperties (stable property names)
com.acme.platform.messaging.autoconfigure   → @AutoConfiguration classes (semi-public: class names stable for exclude=)
com.acme.platform.messaging.kafka           → provider public surface (small)
com.acme.platform.messaging.kafka.internal  → internals (no guarantees)
com.acme.platform.messaging.internal        → internals (no guarantees)
com.acme.platform.messaging.migration       → deprecated bridges kept during a deprecation window
com.acme.platform.messaging.testing         → test fixtures published from test-support
```

`*.internal.*` is excluded from javadoc, excluded from japicmp compatibility checks, and flagged by an ArchUnit rule if imported from application code in example/test builds.

---

## 3. Complete Capability Catalog

Each row lists the modules the capability ships. Legend: A=api, S=spi, I=impl(s), AC=autoconfigure, ST=starter(s), T=test-support.

| Capability | Modules | Providers / Notes |
|---|---|---|
| Core | core-api, core-autoconfigure, starter-core | Correlation ID, context conventions, banner, `acme.platform.*` root props |
| Logging | logging-api, logging-autoconfigure, starter-logging | Logback + ECS/JSON encoder by default; MDC ↔ tracing bridge |
| Audit Logging | audit-api, audit-spi, audit-autoconfigure, audit-jdbc, audit-messaging, starter-audit(-jdbc/-messaging) | `@Audited`, structured audit events; sinks are SPI |
| Security | security-api, security-autoconfigure, starter-security | Baseline: OAuth2 resource server, sane headers, actuator lockdown |
| AuthN | authn-oidc (impl under security), starter-security-oidc | OIDC/JWT decoding conventions, issuer registry |
| AuthZ | authz-api, authz-spi, authz-autoconfigure, authz-method, starter-authz | `@RequiresPermission`, PDP SPI (local rules default; OPA/enterprise PDP pluggable) |
| REST Client | restclient-api, restclient-autoconfigure, starter-restclient | Builder conventions on `RestClient`: correlation, auth propagation, retry hooks, Observation |
| OpenAPI | openapi-autoconfigure, starter-openapi | springdoc preconfigured: servers, security schemes, error schemas |
| Validation | validation-api, validation-autoconfigure, starter-validation | Common constraints, message conventions |
| Errors | errors-api, errors-autoconfigure, starter-errors | RFC 9457 ProblemDetail, error-code registry, exception mapping SPI-lite (customizer) |
| Messaging | messaging-api, messaging-spi, messaging-kafka, messaging-rabbit, messaging-autoconfigure, starter-messaging-kafka, starter-messaging-rabbit, messaging-test | `EventPublisher`/`@EventHandler` façade; outbox option |
| Domain/Integration Events | events-api, events-autoconfigure, starter-events | In-process domain events; integration events delegate to messaging api if present |
| Redis | redis-autoconfigure, starter-redis | Client conventions, codec defaults |
| Data/JPA | data-api, data-jpa-autoconfigure, starter-data-jpa | Auditing columns, naming strategy, converters (Money, TenantId), Flyway conventions |
| Object Storage | storage-api, storage-spi, storage-s3, storage-azure, storage-fs, storage-autoconfigure, starter-storage-s3 … | `ObjectStore` API; providers via SPI |
| File Processing | files-api, files-autoconfigure, starter-files | Streaming upload/download, checksum, content-type safety |
| Scheduling | scheduling-autoconfigure, starter-scheduling | Locked scheduling via locking-api (ShedLock-style), virtual-thread executor |
| Feature Flags | flags-api, flags-spi, flags-autoconfigure, flags-inmemory, flags-openfeature, starter-flags | OpenFeature-compatible SPI |
| Config | config-autoconfigure (part of core), conventions doc | Profile & property conventions, config metadata generation everywhere |
| Health | health-autoconfigure, starter-observability | Opinionated groups: liveness/readiness/startup wired per provider |
| Metrics/Tracing/Observability | observability-autoconfigure, starter-observability | Micrometer + Observation API + OTLP defaults; common tags (service, env, tenant) |
| Caching | cache-api, cache-autoconfigure, starter-cache(-redis/-caffeine) | Key conventions, TTL properties, cache metrics |
| Secrets | secrets-api, secrets-spi, secrets-vault, secrets-env, secrets-autoconfigure, starter-secrets-vault | `PropertySource` bridge; providers via SPI |
| Resilience | resilience-api, resilience-autoconfigure, starter-resilience | Retry, circuit breaker, bulkhead via Resilience4j; annotation + properties conventions |
| Transactions | tx-autoconfigure (in data-jpa), docs | Conventions: `@Transactional` boundaries, outbox with messaging |
| Distributed Locking | locking-api, locking-spi, locking-redis, locking-jdbc, locking-autoconfigure, starter-locking-redis … | `LockManager` API |
| Idempotency | idempotency-api, idempotency-autoconfigure, starter-idempotency | `@Idempotent` on handlers/endpoints; store via locking/cache SPI |
| Rate Limiting | ratelimit-api, ratelimit-spi, ratelimit-redis, ratelimit-inmemory, ratelimit-autoconfigure, starter-ratelimit-redis | Server filter + client-side limiter |
| Multi-tenancy (optional) | tenancy-api, tenancy-spi, tenancy-autoconfigure, tenancy-jpa, starter-tenancy | TenantContext, resolver SPI (header/JWT), JPA discriminator/schema strategies |

**Added capabilities (platform-improving, beyond the requested list):** *Idempotency store abstraction reused by messaging outbox; error-code registry with build-time uniqueness check; config-metadata everywhere so IDEs autocomplete every platform property; test-slices per capability (`@PlatformMessagingTest`); golden-path service archetype.*

---

## 4. Architecture Dependency Constitution

**Allowed dependencies**

1. `api → core-api`, `api → JDK`, `api → spring-core` (only if unavoidable; prefer none).
2. `spi → api` (same capability), `spi → core-api`.
3. `impl → spi, api` (same capability) + its third-party library.
4. `autoconfigure → api, spi` (same capability); `autoconfigure → other capability api` **only** with `@ConditionalOnClass` guarding and `<optional>true</optional>`.
5. `autoconfigure → impl` only as `<optional>true</optional>` (compiled against, activated conditionally).
6. `starter → autoconfigure + exactly the impl(s) it names + that impl's driver`.
7. `test-support → api, spi` (+ test libs).
8. Everything → `platform-build-tools` (build-time only).

**Forbidden dependencies**

1. `api → spi | impl | autoconfigure | starter` (API depends on nothing downward). **API must never depend on implementation.**
2. `impl → impl` (cross-provider or cross-capability).
3. `starter → starter`.
4. `spi → impl`.
5. Any platform module → `platform-examples` or any application.
6. Any module → another capability's `spi`, `impl`, or `internal` packages.
7. `core-api → anything platform` (it is the root).
8. Compile-scope Spring Boot starters inside `api`/`spi` modules.

**Quantitative limits**

- Maximum platform dependency depth: **4** (`starter → autoconfigure → impl → spi/api`; api→core-api doesn't count as it's the root).
- Maximum fan-out per module: **api ≤ 1** platform dep, **spi ≤ 2**, **impl ≤ 3**, **autoconfigure ≤ 6** (incl. optionals), **starter ≤ 4**.
- Fan-in ceiling triggers review: any non-core module with fan-in > 5 must justify or split.
- **Zero cycles**, enforced at build time.

**Enforcement (fitness functions):** maven-enforcer (`banCircularDependencies`, custom `PlatformLayerRule` in `platform-build-tools`), ArchUnit rule-jar executed in every module's test phase, japicmp for API/SPI packages, dependency-convergence enforced, `mvn verify` fails on any violation. CI runs a whole-graph check (jdeps + custom analyzer) nightly.

---

## 5. Public API Policy

Every module declares its surface in its README and via annotations:

- **Public packages** (`com.acme.platform.<cap>`, `…​.annotation`, `…​.config` property names): SemVer-guaranteed binary compatibility within a major train. Checked by japicmp on every build against the last released minor.
- **SPI packages** (`…​.spi`): binary compatible within a major; **new default methods allowed** in minors (documented as the only permitted expansion); implementors are warned that SPIs evolve faster than APIs.
- **Internal packages** (`…​.internal`, `…​.kafka.internal`): no guarantees, may change in patches. Annotated `@PlatformInternal`; excluded from docs and japicmp.
- **Configuration packages / property names**: property keys are API. Renames require a deprecation window with `additional-spring-configuration-metadata.json` deprecation entries and a `PropertiesMigrationListener`-style warning.
- **Extension packages** (`…​.customizer` types living in api): treated as Public.
- **Migration packages** (`…​.migration`): deprecated bridges; guaranteed for exactly one minor after deprecation, then removed at the next major.
- **Auto-configuration class names**: semi-public (users exclude them by name); renames only at majors, with the old name kept as a deprecated forwarder for one minor.

**Binary compatibility guarantee statement:** *Within major version N, any application or extension compiled against platform N.x public API/SPI runs unmodified on every N.y (y ≥ x). Internals carry no guarantee. Majors may break with a published migration guide and automated OpenRewrite recipes.*

---

## 6. Extension Model

All extension follows one pattern — **implement the SPI, ship your own auto-configuration, register it in `AutoConfiguration.imports`, order it before the platform's default** (platform defaults are all `@ConditionalOnMissingBean`). No platform module is ever modified.

| Add a new… | Implement | Register | Platform behavior |
|---|---|---|---|
| Messaging provider (e.g., Pulsar) | `MessageChannelProvider`, `EventTransportBinder` from `messaging-spi` | Your `pulsar-autoconfigure` + `starter`; bean of type `EventTransport` | `messaging-autoconfigure` binds the façade to any `EventTransport` bean; default backs off via `@ConditionalOnMissingBean(EventTransport.class)` |
| Storage provider (e.g., GCS) | `ObjectStoreProvider` from `storage-spi` | Bean `ObjectStore` | Same back-off pattern |
| Authentication provider | `TokenIntrospector`/`IssuerResolver` customizers (security api) or full `AuthenticationScheme` SPI | Bean replaces default | Security autoconfig composes customizers via `ObjectProvider<List<…Customizer>>` |
| Cache provider | Spring's own `CacheManager` + `CacheKeyConvention` SPI | Bean `CacheManager` | Platform decorates any manager with metrics/conventions |
| Tracing provider | Micrometer `Tracer` bridge (industry SPI) + `ObservationCustomizer` | Bean | Platform never wraps tracing itself — Micrometer *is* the SPI |
| Event bus | `EventTransport` (same as messaging provider) | Bean | — |
| Persistence technology | New `platform-data-<tech>-autoconfigure` implementing `data-api` conventions (auditing, converters) | New starter | `data-api` holds tech-neutral conventions only |

Two extension tiers: **Customizer tier** (tweak the default: `RestClientCustomizer`, `ProblemDetailCustomizer`, `KafkaTemplateCustomizer` — collected via `ObjectProvider`) and **Provider tier** (replace the engine: implement SPI, contribute bean). Contract tests (§11) let extenders verify their provider: `platform-<cap>-tck` test-jar with an abstract JUnit class each provider extends.

---

## 7. Auto-Configuration Strategy

- **Registration:** every autoconfigure module ships `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. One `@AutoConfiguration` class per concern, small and focused.
- **Conditions:** `@ConditionalOnClass` (provider on classpath) → `@ConditionalOnProperty(prefix="acme.platform.<cap>", name="enabled", matchIfMissing=true)` (kill switch) → `@ConditionalOnMissingBean` (user override) — in that order of evaluation cost.
- **Customization:** collect `*Customizer` beans with `ObjectProvider<…>.orderedStream()`; apply in `@Order` order. Users add behavior without replacing beans.
- **Replacement:** define the same bean type; platform backs off. Documented per bean in the config reference.
- **Ordering:** platform configs declare `@AutoConfiguration(before/after = …)` explicitly against Spring Boot's classes (e.g., observability before `WebMvcAutoConfiguration` filter registration). Cross-capability order expressed only via before/after on autoconfig classes, never `@DependsOn` on beans.
- **Defaults:** every property has a default in code **and** in `additional-spring-configuration-metadata.json`; `@ConfigurationProperties` records are immutable (constructor binding).
- **Optional dependencies:** autoconfigure compiles against optional impls; absence of a class disables that path silently (`@ConditionalOnClass`), with a `ConditionEvaluationReport`-friendly message.
- **Graceful degradation:** e.g., audit falls back from messaging sink → jdbc sink → log sink with a startup `WARN` stating the active sink and why.
- **Starter composition:** starters are additive and non-overlapping; `starter-core` is included by the service parent; teams add capability starters à la carte. A starter never excludes another's classes.

---

## 8. Versioning Strategy

- **Scheme:** Semantic Versioning on the **release train**: all `platform-*` artifacts share one version (`2.4.1`). Marketing/train names optional; the number is the contract.
- **Cadence:** minor train every 6–8 weeks; patch trains on demand (security < 48h); major train aligned to Spring Boot majors (~yearly window, adopted within one quarter of Boot GA).
- **Dependency alignment:** `platform-dependencies` imports `spring-boot-dependencies` and pins every third-party the platform touches. Applications import only `platform-bom` (which imports `platform-dependencies`). One property (`platform.version`) upgrades everything.
- **BOM evolution:** artifacts are only *added* in minors; removals happen at majors (with the artifact relocated via a Maven relocation POM for one major).
- **Backward compatibility:** §5 guarantees; japicmp gates releases.
- **Deprecation lifecycle:** `@Deprecated(since, forRemoval=true)` + config-metadata deprecation + release-notes entry → survives all remaining minors of the major → removed at next major. Minimum 6 months between deprecation and removal.
- **Support policy:** current major fully supported; previous major receives security + critical fixes for 12 months after the new major GA. One LTS train per major, chosen at `.4`–`.6`, supported 24 months.
- **Spring Boot upgrades:** platform minors track Boot minors within 4 weeks; Boot patch bumps ride platform patches. Boot majors = platform majors, shipped with OpenRewrite migration recipes.
- **Java upgrades:** platform builds on latest LTS; bytecode target = oldest supported LTS (one LTS overlap window per platform major).
- **Binary compatibility tooling:** japicmp semver-check bound to `verify`; a `compat-report` aggregated per release published in docs.

---

## 9. Build Strategy

- **Layout:** monorepo, multi-module Maven (§17 tree). One reactor; capabilities are top-level directories aggregating their sub-modules.
- **Parents:** `platform-parent` (plugin management, enforcer, toolchains, japicmp, ArchUnit test dep, error-prone/checkstyle) → each module. `platform-service-parent` is a separate, tiny, application-facing parent: imports `platform-bom`, configures spring-boot-maven-plugin, surefire/failsafe, jacoco, git-commit-id — nothing else.
- **Performance:** Maven 4 / mvnd daemon; `-T 1C` parallel builds; Develocity/Gradle-Enterprise-style build cache (or `maven-build-cache-extension`) keyed per module; CI computes changed-module set (`gitflow-incremental-builder`) and builds only affected modules + dependents.
- **Incremental CI:** PR pipeline = affected modules + their ArchUnit/japicmp checks (< 10 min target); nightly = full reactor + native smoke + full Testcontainers suite.
- **Publishing:** internal artifact repository (Nexus/Artifactory); `maven-deploy` from CI only; releases via tag-triggered pipeline using CI-friendly versions (`${revision}` + flatten-maven-plugin) — no `maven-release-plugin` commits.
- **Convergence:** `enforcer:dependencyConvergence` + `requireUpperBoundDeps` mandatory.
- **Version catalog:** all third-party versions live *only* in `platform-dependencies`; enforcer bans `<version>` tags elsewhere.
- **Release automation:** conventional commits → release-notes generation; tag `vX.Y.Z` → build, sign, publish BOM-first, publish docs site, publish compat report, create GitHub release. Reproducible builds (`project.build.outputTimestamp`).

---

## 10. Testing Strategy

| Layer | Tooling | Scope |
|---|---|---|
| Unit | JUnit 5, AssertJ, Mockito (sparingly) | api/impl logic; no Spring context |
| Auto-configuration tests | `ApplicationContextRunner` | every condition branch: enabled/disabled/missing-class/user-override/customizer-order |
| Integration | `@SpringBootTest` + Testcontainers (Kafka, Redis, Postgres, LocalStack, Vault) | impl modules against real services |
| Contract (TCK) | `platform-<cap>-tck` abstract test classes | every provider (internal or enterprise) must pass its capability's TCK |
| SPI tests | TCK + ArchUnit "SPI implementors only touch spi+api" | extension safety |
| Compatibility | japicmp gate + "previous-minor consumer" job: examples compiled against N-1 run on N | binary compat proof |
| Smoke | golden-path example services boot with each starter combo matrix (curated ~12 combos) | startup, health, one request |
| Native | GraalVM native build of `example-minimal` + `example-golden-path` nightly | AOT metadata correctness |
| Performance | JMH for hot paths (correlation filter, event publish); startup-time budget test (≤ +15% over vanilla Boot per starter) | regressions gate nightly |
| Golden Path | scripted: generate service from archetype → build → test → run → assert observability endpoints | the DX contract itself, tested |

Test-support modules publish fixtures (`@PlatformMessagingTest` slice, `TestEventTransport`, container factories) so application teams inherit the same quality cheaply.

---

## 11. Developer Experience

**Create a new service** — `mvn archetype:generate -DarchetypeArtifactId=platform-service-archetype` (or `platform new-service` CLI wrapper): produces parent-POM project with `application.yml`, `@SpringBootApplication`, sample controller/service/test. Add capability = add one starter line. Time-to-first-endpoint target: **< 10 minutes**.

**Override defaults** — set `acme.platform.*` properties (IDE-completed via config metadata), or define a `*Customizer` bean, or define the bean type yourself (platform backs off).

**Disable features** — `acme.platform.<cap>.enabled=false`, or omit the starter, or `spring.autoconfigure.exclude` as a last resort.

**Replace implementations** — swap starter (`starter-messaging-kafka` → `starter-messaging-rabbit`) or contribute your own `EventTransport` bean.

**Debug startup** — `--debug` ConditionEvaluationReport is the primary tool; platform adds a startup summary log line per capability (`platform-messaging: ACTIVE (kafka), audit sink: jdbc (messaging api absent)`); actuator `platform` endpoint lists capability status.

**Customize configuration** — every property documented in the generated config reference; profiles convention (`local`, `test`, `prod`) documented in `platform-docs`.

**Upgrade safely** — bump `platform.version`; run `platform-build-maven-plugin:upgrade-check` (japicmp against your own code's usage + property-deprecation scan + OpenRewrite recipes for majors); release notes per train with "action required" section.

**Write tests** — add `starter-test`; use `@PlatformTest` slices and provided containers.

**Publish reusable modules** — teams building shared libs extend `platform-parent`'s conventions via `platform-library-parent` (thin variant) and get the same enforcer/ArchUnit/japicmp gates.

---

## 12. Governance

- **Architecture rules** live as code in `platform-build-tools` (fitness functions §4); prose in `platform-docs/architecture-constitution.adoc`.
- **Ownership:** CODEOWNERS per capability directory; core-api owned by the platform architecture group; every capability has ≥ 2 named maintainers.
- **Review:** changes to `api`/`spi` packages require 2 approvals incl. one architecture-group member; internals need 1 maintainer.
- **Quality gates (merge-blocking):** build + tests, ArchUnit, japicmp semver check, dependency convergence, coverage ≥ 80% on non-generated code, config-metadata completeness check, startup-time budget on affected examples.
- **Fitness functions (nightly):** whole-graph cycle/fan-out scan, native build, N-1 compatibility job, CVE scan of `platform-dependencies`.
- **Backward compatibility & API stability policy:** as §5; exceptions require an ADR.
- **Deprecation policy:** as §8; every deprecation PR must include the migration path and the removal-target release in the annotation.
- **Release governance:** release manager rotates; go/no-go checklist (gates green, compat report clean, docs published, examples on new train); patch trains need only the affected capability's maintainer + release manager.

---

## 13. Architecture Decision Records

**ADR-001 — Parent + BOM split.** *Decision:* separate `platform-parent` (platform's own build), `platform-service-parent` (applications), `platform-bom` (platform artifacts), `platform-dependencies` (third-party pins). *Why:* applications must inherit conventions without inheriting the platform's internal build machinery; BOM-only consumption must remain possible for Gradle teams and for teams with their own parent. *Consequence:* four small POMs to maintain; total decoupling of consumption styles.

**ADR-002 — Starter philosophy.** *Decision:* starters contain zero code; one starter per capability-provider pair; `starter-core` is the only implicit starter. *Why:* code in starters becomes un-excludable; per-provider starters keep classpaths minimal and choices explicit. *Consequence:* more starter POMs; trivially cheap.

**ADR-003 — API vs SPI.** *Decision:* separate artifacts and packages for consumer API and extender SPI; SPI exists only when ≥ 2 providers are plausible. *Why:* different audiences evolve at different speeds and need different compatibility promises; merging them forces the slowest evolution speed on everything. *Consequence:* more modules; crisp compatibility contracts; extenders never see internals.

**ADR-004 — Auto-configuration as the only wiring mechanism.** *Decision:* all platform behavior activates via `@AutoConfiguration` + conditions; no `@ComponentScan` into platform packages, no `spring.factories` legacy, no programmatic registrars beyond what conditions require. *Why:* deterministic, debuggable (`--debug` report), AOT-analyzable, overridable by construction. *Consequence:* condition-matrix tests are mandatory (§10).

**ADR-005 — Release-train versioning.** *Decision:* single version across all platform artifacts; SemVer on the train; no per-module versions. *Why:* eliminates the compatibility matrix; one tested set; one upgrade lever. *Consequence:* a one-line fix ships a whole patch train — accepted; cheap with automated releases.

**ADR-006 — Module boundaries by capability, layered inside.** *Decision:* vertical capability slices, each internally split api/spi/impl/autoconfigure/starter; every module in exactly one category (§2.2). *Why:* ownership, blast-radius control, and classpath minimalism follow capability lines, not technical layers. *Consequence:* many small modules; mitigated by scaffolding plugin and build caching.

**ADR-007 — Dependency constitution enforced as code.** *Decision:* the rules in §4 are executable (enforcer + ArchUnit + japicmp) and merge-blocking. *Why:* prose architecture decays; failing builds don't. *Consequence:* occasional friction on legitimate exceptions → ADR-gated rule changes.

**ADR-008 — Extension via Spring back-off + SPI, never modification.** *Decision:* platform defaults always `@ConditionalOnMissingBean`; providers plug in by contributing beans implementing SPIs; TCKs certify providers. *Why:* open-closed at the ecosystem level; enterprises extend without forking; upgrades don't merge conflicts. *Consequence:* discipline required to never expose concrete classes as contracts.

**ADR-009 — Why not a God Framework.** *Decision:* no `platform-all`, no mega-core, `core-api` capped in size. *Why:* transitive bloat, universal blast radius, CVE surface, native-image hostility, and the coupling ratchet: every convenience added to a god module is permanent. *Consequence:* teams add 3–8 starters instead of 1 artifact — the archetype writes those lines for them.

**ADR-010 — Why Convention over Configuration.** *Decision:* every capability works with zero configuration using documented conventions; configuration exists to *deviate*. *Why:* cognitive load is the platform's primary cost; conventions turn N team-decisions into 1 platform-decision made once by experts; consistency across hundreds of services is itself a feature (operability, staffing mobility). *Consequence:* conventions are API — changing a default is a breaking change and versioned as such.

---

## 14. Decision Matrix

Weights reflect the success criteria (§18). Scores /10; weighted total /10.

| Criterion | Wt | A: Pure Starter | B: Feature Modular | C: Hybrid Starter+SPI |
|---|---|---|---|---|
| Simplicity | 10 | 9 | 6 | 8 |
| Maintainability | 10 | 7 | 7 | 9 |
| Modularity | 8 | 7 | 9 | 9 |
| Extensibility | 8 | 5 | 8 | 9 |
| Upgrade Safety | 9 | 7 | 5 | 9 |
| Developer Experience | 10 | 9 | 6 | 9 |
| Testability | 7 | 7 | 7 | 9 |
| Learning Curve | 7 | 9 | 6 | 8 |
| Operational Complexity | 6 | 8 | 5 | 7 |
| Enterprise Readiness | 8 | 6 | 8 | 9 |
| Long-term Sustainability | 9 | 6 | 7 | 9 |
| Build Performance | 5 | 8 | 7 | 6 |
| Release Complexity | 5 | 8 | 4 | 7 |
| **Weighted total** | | **7.42** | **6.58** | **8.48** |

Score rationale (abridged): A scores highest on simplicity/learning curve (pure Boot idiom, fewest modules) but loses on extensibility (5 — no contractual extension point; enterprises patch or fork), upgrade safety (7 — leaked internals break silently), enterprise readiness (6 — no TCK, no compat contract) and sustainability (6 — API decay is the historical failure mode of internal platforms). B scores highest on modularity/autonomy but is punished on upgrade safety (5 — version matrix), DX (6 — users manage N versions), release complexity (4 — N pipelines, matrix testing) and ops (5). C pays a build-performance (6 — most modules; mitigated by caching/incremental CI) and simplicity tax (8 — more modules than A) but leads everywhere the success criteria weight most: maintainability, upgrade safety, extensibility, sustainability, DX. **C wins by a clear margin.**

### Where C may fail, and 5-year evolution
*Failure modes:* module sprawl without scaffolding tooling (mitigate: generator plugin, Phase 6); API/SPI discipline decay if gates are relaxed (mitigate: gates are merge-blocking, exceptions need ADRs); release-train hostage situations if one capability is chronically unstable (mitigate: incubator track below); over-abstraction temptation (mitigate: "SPI only at 2 plausible providers" rule).
*Evolution:* Year 1 — phases 1–10, LTS 1.x. Year 2 — incubator repo (`platform-incubator`) for experimental capabilities with looser guarantees, graduating into the train; Gradle version-catalog publication alongside BOM. Year 3 — major train tracking Boot's next major; OpenRewrite-first migrations; possible split of very large capabilities into sub-slices. Years 4–5 — candidate extraction of genuinely generic capabilities to inner-source/OSS; contract-first (TCK-first) development for all new SPIs; evaluate JPMS `module-info` once the ecosystem justifies it (explicitly *not* at start — cost > benefit today).

---

## 15. Implementation Roadmap (Claude Code executable)

Every phase ends in a green, releasable reactor. Complexity: S/M/L/XL.

**Phase 1 — Foundation (S).** *Objectives:* repo skeleton, versioning, parents, BOMs. *Modules:* platform-parent, platform-service-parent, platform-bom, platform-dependencies, root aggregator. *Deliverables:* building reactor, CI pipeline stub, `${revision}` versioning, first `0.1.0` publishable. *Acceptance:* `mvn -T1C verify` green; an empty app using service-parent boots. *Risks:* over-engineering parents. *Dependencies:* none.

**Phase 2 — Build System (M).** *Objectives:* gates as code. *Modules:* platform-build-tools (enforcer rules, ArchUnit rule-jar, checkstyle/error-prone config), platform-build-maven-plugin (scaffold `new-module` goal). *Deliverables:* dependency constitution enforced; incremental CI (changed-module builds); japicmp wired (no-op until first release). *Acceptance:* seeded rule-violation branch fails CI; scaffold generates a compliant module. *Risks:* enforcer/ArchUnit false positives — start warn-then-fail. *Dependencies:* P1.

**Phase 3 — Core Platform (M).** *Objectives:* smallest useful chassis. *Modules:* core-api, core-autoconfigure, starter-core, logging-*, errors-*, validation-*. *Deliverables:* correlation ID end-to-end, JSON logs, ProblemDetail errors, config-metadata. *Acceptance:* golden-path mini app returns RFC 9457 errors with correlation IDs in structured logs; ContextRunner tests cover all conditions. *Risks:* core-api scope creep — cap enforced. *Dependencies:* P2.

**Phase 4 — Auto-Configuration Patterns (M).** *Objectives:* canonize the condition/customizer/back-off pattern. *Modules:* observability-*, openapi-*, restclient-*. *Deliverables:* pattern documented as the template all capabilities copy; startup summary line; `platform` actuator endpoint. *Acceptance:* metrics/traces/OpenAPI appear with zero config; every default overridable in a test. *Risks:* ordering bugs vs Boot autoconfigs — covered by ContextRunner order tests. *Dependencies:* P3.

**Phase 5 — Platform Services (XL, parallelizable per capability).** *Objectives:* the capability catalog (§3): security/authn/authz, messaging (kafka, rabbit) + events, data-jpa, redis, cache, storage (s3, fs), resilience, scheduling+locking, idempotency, ratelimit, secrets, flags, audit, files, tenancy(optional). *Deliverables:* each capability = api(+spi)+impl+autoconfigure+starter+TCK+docs page, merged only when its whole slice is green. *Acceptance:* per-capability TCK green; combo smoke matrix green; constitution holds at full graph. *Risks:* scope; sequence by consumer demand, ship trains incrementally (0.x minors). *Dependencies:* P4; internal order: security & messaging first (most depended-on APIs), tenancy last.

**Phase 6 — Developer Experience (M).** *Objectives:* golden path automation. *Modules:* platform-service-archetype, CLI wrapper, upgrade-check goal. *Deliverables:* `new-service` in <10 min; upgrade-check with property-deprecation scan. *Acceptance:* scripted golden-path test (§10) green in CI. *Risks:* archetype drift — archetype is itself integration-tested each build. *Dependencies:* P3 (usable), P5 (complete).

**Phase 7 — Testing Infrastructure (M).** *Objectives:* consumer-grade test kit. *Modules:* test-api, starter-test, per-capability test-support + slices, TCK packaging. *Deliverables:* `@PlatformTest` slices, container fixtures, `TestEventTransport` etc. *Acceptance:* example services' tests use only platform fixtures; slice startup < 5s. *Risks:* fixture coupling to internals — fixtures live in api-facing test modules. *Dependencies:* parallel with P5.

**Phase 8 — Documentation (M).** *Objectives:* docs as a product. *Modules:* platform-docs (Antora), generated config-props reference, per-capability guides, constitution, ADRs. *Deliverables:* versioned docs site auto-published per train; javadoc for public/spi only. *Acceptance:* docs build in CI; broken-link check; every property documented (completeness gate). *Risks:* staleness — reference pages generated from code/metadata. *Dependencies:* continuous from P3.

**Phase 9 — Sample Applications (M).** *Objectives:* executable proof + living tests. *Modules:* example-minimal, example-golden-path (REST+JPA+Kafka+security+observability), example-extension-provider (custom storage provider passing the TCK), example-event-driven. *Deliverables:* examples pinned to the train, run in smoke matrix and native nightly. *Acceptance:* all examples green on every train build. *Dependencies:* P5–P7.

**Phase 10 — Release Automation (S/M).** *Objectives:* one-click trains. *Deliverables:* tag-triggered release pipeline (build, sign, publish BOM-first, docs, compat report, notes from conventional commits), patch-train fast path, N-1 compat job, LTS branch mechanics. *Acceptance:* dry-run release of `1.0.0-RC1` end-to-end; then GA `1.0.0`. *Risks:* repo credentials/signing — standard CI secrets. *Dependencies:* all.

---

## 16. Implementation Order (Maven modules, dependency order)

```
 1. platform-parent
 2. platform-dependencies
 3. platform-bom
 4. platform-service-parent
 5. platform-build-tools
 6. platform-build-maven-plugin
 7. platform-core-api
 8. platform-core-autoconfigure
 9. platform-starter-core
10. platform-logging-api
11. platform-logging-autoconfigure
12. platform-starter-logging
13. platform-errors-api
14. platform-errors-autoconfigure
15. platform-starter-errors
16. platform-validation-api
17. platform-validation-autoconfigure
18. platform-starter-validation
19. platform-observability-autoconfigure
20. platform-starter-observability
21. platform-openapi-autoconfigure
22. platform-starter-openapi
23. platform-restclient-api
24. platform-restclient-autoconfigure
25. platform-starter-restclient
26. platform-security-api
27. platform-security-autoconfigure
28. platform-starter-security
29. platform-authz-api
30. platform-authz-spi
31. platform-authz-autoconfigure
32. platform-starter-authz
33. platform-messaging-api
34. platform-messaging-spi
35. platform-messaging-kafka
36. platform-messaging-rabbit
37. platform-messaging-autoconfigure
38. platform-starter-messaging-kafka
39. platform-starter-messaging-rabbit
40. platform-events-api
41. platform-events-autoconfigure
42. platform-starter-events
43. platform-data-api
44. platform-data-jpa-autoconfigure
45. platform-starter-data-jpa
46. platform-redis-autoconfigure
47. platform-starter-redis
48. platform-cache-api
49. platform-cache-autoconfigure
50. platform-starter-cache-caffeine
51. platform-starter-cache-redis
52. platform-storage-api
53. platform-storage-spi
54. platform-storage-fs
55. platform-storage-s3
56. platform-storage-autoconfigure
57. platform-starter-storage-s3
58. platform-resilience-api
59. platform-resilience-autoconfigure
60. platform-starter-resilience
61. platform-locking-api
62. platform-locking-spi
63. platform-locking-redis
64. platform-locking-jdbc
65. platform-locking-autoconfigure
66. platform-starter-locking-redis
67. platform-scheduling-autoconfigure
68. platform-starter-scheduling
69. platform-idempotency-api
70. platform-idempotency-autoconfigure
71. platform-starter-idempotency
72. platform-ratelimit-api
73. platform-ratelimit-spi
74. platform-ratelimit-inmemory
75. platform-ratelimit-redis
76. platform-ratelimit-autoconfigure
77. platform-starter-ratelimit-redis
78. platform-secrets-api
79. platform-secrets-spi
80. platform-secrets-env
81. platform-secrets-vault
82. platform-secrets-autoconfigure
83. platform-starter-secrets-vault
84. platform-flags-api
85. platform-flags-spi
86. platform-flags-inmemory
87. platform-flags-openfeature
88. platform-flags-autoconfigure
89. platform-starter-flags
90. platform-audit-api
91. platform-audit-spi
92. platform-audit-jdbc
93. platform-audit-messaging
94. platform-audit-autoconfigure
95. platform-starter-audit-jdbc
96. platform-files-api
97. platform-files-autoconfigure
98. platform-starter-files
99. platform-tenancy-api
100. platform-tenancy-spi
101. platform-tenancy-jpa
102. platform-tenancy-autoconfigure
103. platform-starter-tenancy
104. platform-test-api
105. platform-messaging-test
106. platform-starter-test
107. platform-tck-messaging
108. platform-tck-storage
109. platform-tck-locking
110. platform-tck-flags
111. platform-tck-secrets
112. platform-tck-ratelimit
113. platform-service-archetype
114. platform-docs
115. platform-examples/example-minimal
116. platform-examples/example-golden-path
117. platform-examples/example-extension-provider
118. platform-examples/example-event-driven
```

---

## 17. Complete Repository Structure

GroupId: `com.acme.platform`. Version: `${revision}` (single train version). Tree (Maven `artifactId` = directory name; category in brackets):

```
acme-platform/                                  [aggregator pom]
├── pom.xml                                     modules list, ${revision}
├── build/
│   ├── platform-parent/                        [Parent]      parent: none (imports platform-dependencies)
│   ├── platform-service-parent/                [Parent]      imports platform-bom
│   ├── platform-bom/                           [BOM]         lists every platform artifact
│   ├── platform-dependencies/                  [BOM]         imports spring-boot-dependencies + pins
│   ├── platform-build-tools/                   [Build Plugin] enforcer rules, archunit-rules, style configs
│   └── platform-build-maven-plugin/            [Build Plugin] new-module, upgrade-check goals
├── core/
│   ├── platform-core-api/                      [API]         com.acme.platform.core{,.context,.annotation}
│   ├── platform-core-autoconfigure/            [Auto Configuration] …core.autoconfigure, …core.internal
│   └── platform-starter-core/                  [Starter]
├── logging/
│   ├── platform-logging-api/                   [API]         …logging{,.annotation}
│   ├── platform-logging-autoconfigure/         [Auto Configuration]
│   └── platform-starter-logging/               [Starter]
├── errors/
│   ├── platform-errors-api/                    [API]         …errors{,.annotation}
│   ├── platform-errors-autoconfigure/          [Auto Configuration]
│   └── platform-starter-errors/                [Starter]
├── validation/
│   ├── platform-validation-api/                [API]
│   ├── platform-validation-autoconfigure/      [Auto Configuration]
│   └── platform-starter-validation/            [Starter]
├── observability/
│   ├── platform-observability-autoconfigure/   [Auto Configuration] metrics, tracing, health groups
│   └── platform-starter-observability/         [Starter]
├── openapi/
│   ├── platform-openapi-autoconfigure/         [Auto Configuration]
│   └── platform-starter-openapi/               [Starter]
├── restclient/
│   ├── platform-restclient-api/                [API]
│   ├── platform-restclient-autoconfigure/      [Auto Configuration]
│   └── platform-starter-restclient/            [Starter]
├── security/
│   ├── platform-security-api/                  [API]
│   ├── platform-security-autoconfigure/        [Auto Configuration] oidc resource-server baseline
│   ├── platform-starter-security/              [Starter]
│   ├── platform-authz-api/                     [API]         @RequiresPermission
│   ├── platform-authz-spi/                     [SPI]         PolicyDecisionProvider
│   ├── platform-authz-autoconfigure/           [Auto Configuration]
│   └── platform-starter-authz/                 [Starter]
├── messaging/
│   ├── platform-messaging-api/                 [API]         EventPublisher, @EventHandler
│   ├── platform-messaging-spi/                 [SPI]         EventTransport, SerializerProvider
│   ├── platform-messaging-kafka/               [Implementation]
│   ├── platform-messaging-rabbit/              [Implementation]
│   ├── platform-messaging-autoconfigure/       [Auto Configuration]
│   ├── platform-starter-messaging-kafka/       [Starter]
│   ├── platform-starter-messaging-rabbit/      [Starter]
│   └── platform-messaging-test/                [Test Support] TestEventTransport
├── events/
│   ├── platform-events-api/                    [API]         domain events
│   ├── platform-events-autoconfigure/          [Auto Configuration]
│   └── platform-starter-events/                [Starter]
├── data/
│   ├── platform-data-api/                      [API]         auditing, converters, Money, naming
│   ├── platform-data-jpa-autoconfigure/        [Auto Configuration] + Flyway conventions
│   └── platform-starter-data-jpa/              [Starter]
├── redis/
│   ├── platform-redis-autoconfigure/           [Auto Configuration]
│   └── platform-starter-redis/                 [Starter]
├── cache/
│   ├── platform-cache-api/                     [API]         key conventions
│   ├── platform-cache-autoconfigure/           [Auto Configuration]
│   ├── platform-starter-cache-caffeine/        [Starter]
│   └── platform-starter-cache-redis/           [Starter]
├── storage/
│   ├── platform-storage-api/                   [API]         ObjectStore
│   ├── platform-storage-spi/                   [SPI]         ObjectStoreProvider
│   ├── platform-storage-fs/                    [Implementation]
│   ├── platform-storage-s3/                    [Implementation]
│   ├── platform-storage-autoconfigure/         [Auto Configuration]
│   └── platform-starter-storage-s3/            [Starter]
├── resilience/
│   ├── platform-resilience-api/                [API]
│   ├── platform-resilience-autoconfigure/      [Auto Configuration] resilience4j
│   └── platform-starter-resilience/            [Starter]
├── locking/
│   ├── platform-locking-api/                   [API]         LockManager
│   ├── platform-locking-spi/                   [SPI]
│   ├── platform-locking-redis/                 [Implementation]
│   ├── platform-locking-jdbc/                  [Implementation]
│   ├── platform-locking-autoconfigure/         [Auto Configuration]
│   └── platform-starter-locking-redis/         [Starter]
├── scheduling/
│   ├── platform-scheduling-autoconfigure/      [Auto Configuration] locked schedules, VT executor
│   └── platform-starter-scheduling/            [Starter]
├── idempotency/
│   ├── platform-idempotency-api/               [API]         @Idempotent
│   ├── platform-idempotency-autoconfigure/     [Auto Configuration]
│   └── platform-starter-idempotency/           [Starter]
├── ratelimit/
│   ├── platform-ratelimit-api/                 [API]
│   ├── platform-ratelimit-spi/                 [SPI]
│   ├── platform-ratelimit-inmemory/            [Implementation]
│   ├── platform-ratelimit-redis/               [Implementation]
│   ├── platform-ratelimit-autoconfigure/       [Auto Configuration]
│   └── platform-starter-ratelimit-redis/       [Starter]
├── secrets/
│   ├── platform-secrets-api/                   [API]
│   ├── platform-secrets-spi/                   [SPI]
│   ├── platform-secrets-env/                   [Implementation]
│   ├── platform-secrets-vault/                 [Implementation]
│   ├── platform-secrets-autoconfigure/         [Auto Configuration]
│   └── platform-starter-secrets-vault/         [Starter]
├── flags/
│   ├── platform-flags-api/                     [API]
│   ├── platform-flags-spi/                     [SPI]
│   ├── platform-flags-inmemory/                [Implementation]
│   ├── platform-flags-openfeature/             [Implementation]
│   ├── platform-flags-autoconfigure/           [Auto Configuration]
│   └── platform-starter-flags/                 [Starter]
├── audit/
│   ├── platform-audit-api/                     [API]         @Audited, AuditEvent
│   ├── platform-audit-spi/                     [SPI]         AuditSink
│   ├── platform-audit-jdbc/                    [Implementation]
│   ├── platform-audit-messaging/               [Implementation]
│   ├── platform-audit-autoconfigure/           [Auto Configuration]
│   └── platform-starter-audit-jdbc/            [Starter]
├── files/
│   ├── platform-files-api/                     [API]
│   ├── platform-files-autoconfigure/           [Auto Configuration]
│   └── platform-starter-files/                 [Starter]
├── tenancy/                                    (optional capability)
│   ├── platform-tenancy-api/                   [API]         TenantContext
│   ├── platform-tenancy-spi/                   [SPI]         TenantResolver
│   ├── platform-tenancy-jpa/                   [Implementation]
│   ├── platform-tenancy-autoconfigure/         [Auto Configuration]
│   └── platform-starter-tenancy/               [Starter]
├── test/
│   ├── platform-test-api/                      [Test Support] @PlatformTest slices, containers
│   ├── platform-starter-test/                  [Starter]
│   └── tck/
│       ├── platform-tck-messaging/             [Test Support]
│       ├── platform-tck-storage/               [Test Support]
│       ├── platform-tck-locking/               [Test Support]
│       ├── platform-tck-flags/                 [Test Support]
│       ├── platform-tck-secrets/               [Test Support]
│       └── platform-tck-ratelimit/             [Test Support]
├── tooling/
│   └── platform-service-archetype/             [Build Plugin/Examples]
├── docs/
│   └── platform-docs/                          [Documentation] Antora site, ADRs, constitution, config reference
└── examples/                                   [Examples] (built in reactor, never published)
    ├── example-minimal/
    ├── example-golden-path/
    ├── example-extension-provider/
    └── example-event-driven/
```

Parent relationships: every `platform-*` module → `platform-parent`; examples & archetype-generated projects → `platform-service-parent`; `platform-service-parent` imports `platform-bom`; `platform-bom` imports `platform-dependencies`; `platform-dependencies` imports `spring-boot-dependencies`.

---

## 18. Things to Avoid (each with its maintenance-cost rationale)

1. **`platform-all` / god artifact** — universal blast radius; every upgrade risks every service; CVE surface of the union of all deps.
2. **God `platform-common`/`platform-utils` module** — becomes an append-only dumping ground with total fan-in; every change ripples everywhere; impossible to ever remove anything.
3. **Custom DI container or bean registry** — duplicates Spring, doubles the mental model, breaks tooling and AOT; you maintain a framework forever.
4. **Runtime plugin system / dynamic classloaders** — undebuggable production issues, native-image incompatibility, security review burden; permanent specialist-only code.
5. **Service Locator pattern** — hides dependencies, defeats constructor-injection testability; call sites silently break when wiring changes.
6. **Static mutable registries / singletons holding state** — test pollution, ordering bugs, impossible parallel tests, native-image init headaches.
7. **Deep inheritance hierarchies (base controllers/services/entities)** — fragile-base-class problem: any base change is a platform-wide behavioral change you can't scope.
8. **Required base classes at all** (`extends PlatformService`) — couples every consumer to your class hierarchy; composition via beans keeps consumers free.
9. **Reflection-heavy “magic” (custom scanning, dynamic proxies beyond Spring's)** — AOT metadata maintenance forever, IDE-opaque, refactoring-hostile.
10. **Per-module independent versioning** — combinatorial compatibility matrix; support cost grows O(n²) with modules.
11. **Snapshot dependencies between platform and services** — irreproducible builds; "works on my machine" institutionalized.
12. **Code inside starter modules** — can't be excluded or replaced; forces classpath hacks; violates the one escape hatch users rely on.
13. **`@ComponentScan` over platform packages from user apps (or platform scanning user packages)** — nondeterministic activation, double-registration bugs, unscoped coupling.
14. **Exposing third-party types in platform APIs** (KafkaTemplate in messaging-api, AWS SDK types in storage-api) — vendor upgrades become your API breaks; providers can never be swapped.
15. **Mutable `@ConfigurationProperties` with field injection** — partial-binding bugs, thread-safety doubts; immutability is free correctness.
16. **Property renames without deprecation metadata** — silent misconfiguration in hundreds of services; the cheapest way to destroy trust in upgrades.
17. **Overriding Spring Boot defaults invisibly** (e.g., changing Jackson behavior globally without a property) — "why does my service behave differently than the docs say" support load forever.
18. **Business logic in the platform** (domain entities, org-specific workflows) — platform release cadence now gated by business change; wrong ownership, wrong lifecycle.
19. **Speculative SPIs / abstraction with one implementation** — every abstraction is permanent API surface to guarantee; YAGNI applies doubly to contracts.
20. **Checked-exception-laden or generics-baroque APIs** — every signature complexity is multiplied by thousands of call sites; simplicity is the compatibility strategy.
21. **Starter-to-starter and impl-to-impl dependencies** — invisible transitive activation; users get Kafka because they asked for audit; classpath control lost.
22. **Circular module dependencies (or "temporary" cycles)** — build order fragility; refactoring gridlock; they never stay temporary.
23. **Forked/patched third-party libraries** — you own their CVE stream and upgrade merges forever; use customizers/decorators instead.
24. **Hard requirement on external infrastructure to boot** (registry, config server, vault mandatory) — local dev and CI friction for every team, every day; degrade gracefully instead.
25. **Blocking on custom annotations replacing standard ones** (`@AcmeTransactional`) — retraining cost, tooling blindness; extend semantics via conventions, keep standard annotations.
26. **Un-versioned "latest" docs only** — teams on older trains get wrong instructions; support tickets replace self-service.
27. **Relaxing quality gates "just this once"** — gates only work if unconditional; every exception is a precedent that compounds into decay.
28. **JPMS module-info from day one** — ecosystem friction (unnamed-module deps, test hostility) for near-zero benefit inside one org; revisit when the ecosystem does.

---

## 19. Success Criteria Traceability

1. **Low cognitive load** — one parent, one BOM, conventions with kill switches, config-metadata autocomplete, startup summary (§§2,7,11).
2. **Long-term maintainability** — small modules, constitution-as-code, TCKs, release train (§§2,4,10,12).
3. **Spring Boot idioms** — auto-configuration only, back-off pattern, starters, ProblemDetail, Observation, ConfigurationProperties (§7, ADR-004).
4. **Modularity** — capability slices, category-pure modules (§2.2–2.3).
5. **Explicit dependencies** — constructor injection, no scanning, constitution limits fan-out/depth (§4).
6. **Binary compatibility** — japicmp-gated SemVer, package-tier guarantees (§5).
7. **Ease of onboarding** — archetype, golden-path example, <10-min first endpoint (§11, Phase 6).
8. **Enterprise scalability** — SPI + TCK extension, tenancy option, governance, LTS (§§6,8,12).
9. **Testability** — ContextRunner condition matrices, slices, fixtures, TCKs (§10).
10. **Sustainable evolution** — deprecation lifecycle, incubator track, OpenRewrite migrations, 5-year plan (§§8,14).

No element of the recommended design violates these principles; designs that did (Options 1, 3, 5, and global-hexagonal 4) were rejected in §1.2 with reasoning, before the final recommendation was made.
