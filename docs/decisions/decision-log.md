# Decision Log

One line of context per decision; details live in the commit bodies referenced.

## Phase 2 — build gates

- **D1 — Enforcer API.** `PlatformLayerRule` uses the current enforcer API
  (`enforcer-api` 3.5.0: `@Named` + `AbstractEnforcerRule`) instead of the deprecated
  `EnforcerRule2` named in the spec. The rule name, behavior and messages are the contract.
- **D2 — check-bom binding.** Not bound to aggregator `verify`: a Maven plugin cannot be resolved
  from the reactor that is building it for the ROOT project on a clean machine, which would break
  ground rule 1 (first `mvn verify` after checkout must pass). It runs as an explicit invocation
  (`mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:check-bom` after an install of the plugin)
  and as a dedicated CI step. Revisit in phase 13 tooling scripts.
- **D3 — new-module verification.** The "generate into a temp reactor and verify it" integration
  test is realized as: unit tests over the generation internals + a plugin-descriptor wiring test;
  the phase Acceptance block performs the real `new-module` + root `mvn verify` end to end. A nested
  Maven invocation in tests would need a seeded local repo and network (violates local-first rule 2).
- **D4 — build modules' parent.** `platform-build-tools` and `platform-build-maven-plugin` are
  parented on the ROOT AGGREGATOR, not `platform-parent`: platform-parent's checkstyle/enforcer
  depend on build-tools, so inheriting them there would be a resolution cycle. Build category is
  exempt from the constitution; both modules wire their own compiler/jacoco basics.
- **D5 — japicmp activation.** Fully configured in platform-parent but gated behind
  `platform.japicmp.skip=true` until the first release tag exists (phase 4 flips it after `0.1.0`);
  `platform.japicmp.breakBuild=true` is set only by `-api`/`-spi` modules (scaffolder emits it).
- **Starter packaging.** Generated starters use `jar` packaging (Boot convention, empty jar):
  "POM only" refers to content, and pom-packaging would hide starters from check-bom's
  parent/BOM exclusion.
- **Spring-free build-tools.** `PlatformArchRules` matches Spring annotations by fully-qualified
  NAME, so build-tools has no Spring dependency and the rules run in modules where Spring is absent.

## Phase 3 — core

- **D8 — module builds include the third-party BOM.** Maven 3.9's `-am` does not pull
  import-scoped POMs into the reactor subset, so the per-module loop is
  `mvn -T1C -pl build/platform-dependencies,<module> -am verify` (or run the root build) until
  `platform-dependencies` is in the local repository. Root `mvn -T1C verify` is unaffected.
- **D9 — no customizer SPI in core.** The matrix's "customizers apply in order" case has no
  subject (the spec defines no core customizer); it is covered by the ordering-behavior tests
  instead (filter registered at `Ordered.HIGHEST_PRECEDENCE`, banner output sorted by name).
  A customizer interface would have invented public API the spec does not define.
- **D10 — starter does not depend on core-api directly.** Phase-03 wording lists
  "core-autoconfigure + core-api" in the starter, but the constitution (CLAUDE.md rule 5,
  enforced by `platformLayerRule`) allows only `starter -> autoconfigure + named impl(s)`.
  The constitution wins; applications still get core-api transitively via the autoconfigure
  module's compile dependency.

## Phase 4 — errors, logging, validation

- **D11 — api standard-model whitelist lives in the ArchUnit rule.** The spec's "Enforcer:
  whitelist spring-web for errors-api only" targets `PlatformLayerRule`, but that rule polices
  platform-group dependencies only; the gate that actually rejects third-party types in api
  packages is `PlatformArchRules.apiPackagesDependOnlyOnJdkSpringAnnotationsAndCore`. The
  whitelist is therefore a per-capability map in that rule (errors -> `org.springframework.http`,
  validation -> `jakarta.validation`), proven by pass/fail fixtures so it cannot silently widen.
- **D12 — CapabilityDescriptor moved to core-api.** The canonical autoconfigure pattern requires
  every capability to register a `CapabilityDescriptor` bean, but the constitution forbids
  `<cap>-autoconfigure -> core-autoconfigure` (other capabilities are reachable only via `-api`).
  Phase-3 placement made the type unreachable for every future capability; moved (same package
  `…core.report`) before `0.1.0` exists, while the move is still free of compatibility cost.

## Phase 5 — observability, openapi

- **D13 — one EnvironmentPostProcessor for the management defaults.** The spec's
  `HealthGroupsAutoConfiguration` defines no beans (it only contributes environment defaults),
  so health groups, actuator exposure, baggage and the OTLP-off posture all ship in
  `PlatformObservabilityEnvironmentPostProcessor` (`platform-observability-defaults` source),
  matching the spec's own "same EnvPostProcessor" wording. Details: commit 6a30f71.
- **D14 — no ObservationConvention beans; correlation via baggage only.** The spec reverses
  itself on tagging observations with the correlation id ("low-cardinality? NO"); correlationId
  is high-cardinality, so it propagates exclusively through tracing baggage
  (`management.tracing.baggage.remote-fields=X-Correlation-Id`); the mandatory cardinality
  comment lives on the EnvironmentPostProcessor. Details: commit 6a30f71.
- **D15 — readiness "when present" via membership validation off.** Readiness includes optional
  `db,rabbit,redis` members with `management.endpoint.health.validate-group-membership=false`
  (the group shows the intersection with EXISTING contributors) instead of classpath sniffing;
  `probes.enabled=true` makes the state contributors exist off Kubernetes. Details: commit 6a30f71.
- **D16 — Boot 4.1 EnvironmentPostProcessor API.** New platform EPPs implement
  `org.springframework.boot.EnvironmentPostProcessor` under the new spring.factories key; the
  `org.springframework.boot.env` variant is deprecated for removal (logging's phase-4 EPP still
  uses it — migrate when phase 4 is next touched). Details: commit 6a30f71.

- **D18 — maven-jar-plugin needs an explicit, versioned binding.** An unversioned
  `pluginManagement` entry for `maven-jar-plugin` did not merge into the implicit default-jar
  lifecycle execution; fixed by also declaring the plugin (version 3.5.0, matching
  pluginManagement) in `platform-parent`'s `<build><plugins>`. Caught only by running the actual
  packaged jar (unit tests build against `target/classes`, never a packaged jar, so
  `Package.getImplementationVersion()` is untestable at that layer) — a reminder that the phase
  Acceptance block's scratch-app step is not optional busywork.

- **D17 — prometheus-metrics-bom 1.7.0 override.** Boot 4.1.0 manages `io.prometheus` at 1.5.1
  while its own micrometer 1.17 declares 1.7.0 — an upper-bound violation our
  `requireUpperBoundDeps` gate rejects. `platform-dependencies` imports
  `prometheus-metrics-bom:1.7.0` BEFORE `spring-boot-dependencies` (first import wins) — the one
  sanctioned exception to "only adds, never overrides"; re-check on every Boot bump and delete
  once Boot catches up.

## Phase 6 — restclient, security, authz

- **D19 — api standard-model whitelist extended for restclient and security.** Same rationale as
  D11: `PlatformRestClientFactory.builder()` hands back `RestClient.Builder` and
  `SecurityCustomizer.customize()` configures `HttpSecurity` — both ARE the standard model the
  capability is defined in terms of, not implementation details. Added
  `restclient -> org.springframework.web.client` and `security -> org.springframework.security`
  to `PlatformArchRules.API_STANDARD_MODEL_PACKAGES`.
- **D20 — Boot 4 modularized security/MVC test packages.** The spec-era package names
  (`org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration`,
  `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`) no longer exist
  at 4.1.0: security autoconfiguration moved to the new `spring-boot-security`/
  `spring-boot-security-oauth2-resource-server` artifacts under
  `org.springframework.boot.security.autoconfigure.*` (servlet chain:
  `ServletWebSecurityAutoConfiguration`; resource server: `OAuth2ResourceServerAutoConfiguration`
  under `org.springframework.boot.security.oauth2.server.resource.autoconfigure`), and MockMvc test
  support moved to the new `spring-boot-webmvc-test` artifact under
  `org.springframework.boot.webmvc.test.autoconfigure`. Used the new names throughout (per D6's
  "prefer new canonical names in new code").
- **D21 — SecurityCustomizers run before the platform's own `anyRequest()`.** Spring Security
  forbids adding more `authorizeHttpRequests()` matchers after `anyRequest()` is registered
  (`IllegalStateException`), so `PlatformSecurityAutoConfiguration` applies customizers first and
  seals the chain with permit-paths + `anyRequest().authenticated()` last — a customizer that wants
  to open an additional path still can; one that wants to override the catch-all cannot (by
  design: the platform's authenticated-by-default posture is not customizer-overridable).
- **D23 — restclient module order revised: security-api built first.** The plan's original
  dependency order (restclient before security) could not survive contact with the guarded
  token-relay edge: `PlatformRestClientAutoConfiguration`'s nested `TokenRelayConfiguration`
  needs `CurrentUserAccessor` (security-api) on its own compile classpath even though the
  dependency is `<optional>true</optional>` and the bean only activates via
  `@ConditionalOnBean`. Implemented security (api, autoconfigure, starter) first, then returned
  to `platform-restclient-autoconfigure`.
- **D24 — Boot 4's `ClientHttpRequestFactoryBuilder` replaces `ClientHttpRequestFactorySettings`.**
  The spec's "JDK HttpClient request factory" is realized via
  `org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder.jdk().build(HttpClientSettings)`
  (module `spring-boot-http-client`), Boot 4.1's modularized replacement for the Boot-3-era
  `ClientHttpRequestFactorySettings`/`ClientHttpRequestFactories` API the spec would have assumed.
- **D25 — authz artifacts prefixed `platform-security-authz-*`.** `PlatformLayerRule` infers a
  module's capability purely from its artifactId's first segment after stripping
  `platform-`/`platform-starter-`, and its SPI rule allows only `core-api` or a SAME-capability
  api dependency (no cross-capability edge exists for SPI, unlike autoconfigure). The phase-06
  spec's `PermissionEvaluatorProvider.hasPermission(CurrentUser, String)` needs `CurrentUser`
  from `platform-security-api` — a different capability under the literal short names
  (`platform-authz-spi` -> cap `authz`). Naming the modules `platform-security-authz-{api,spi,
  autoconfigure}` / `platform-starter-security-authz` makes `PlatformLayerRule` infer capability
  `security` for all of them (matches `platform-security-api`), so the SPI dependency is a
  legitimate same-capability edge instead of a constitution violation — consistent with the spec
  grouping authz under one `security/` section and one directory to begin with. Java packages stay
  `ae.gov.dubaicustoms.platform.authz[.spi]` (the conceptual capability name); only the Maven
  artifactId carries the `security-` prefix.
- **D26 — plain InfrastructureAdvisorAutoProxyCreator, not @EnableAspectJAutoProxy.**
  `@EnableAspectJAutoProxy` registers `AnnotationAwareAspectJAutoProxyCreator`, whose advisor
  factory (`ReflectiveAspectJAdvisorFactory`) touches `org.aspectj.lang.annotation.Pointcut` at
  class-load time even though the authz bridge uses plain `Advisor`/`MethodInterceptor` beans, not
  `@Aspect` classes — failing with `ClassNotFoundException` unless `aspectjweaver` is added.
  `PlatformAuthzAutoConfiguration` instead `@Import`s a `ImportBeanDefinitionRegistrar` that calls
  `AopConfigUtils.registerAutoProxyCreatorIfNecessary` directly (the same cooperative-escalation
  protocol `@EnableMethodSecurity`/`@EnableTransactionManagement` use), avoiding the new dependency.
  The `requiresPermissionAdvisor` bean is also marked `@Role(ROLE_INFRASTRUCTURE)`: when the
  resulting creator is an `InfrastructureAdvisorAutoProxyCreator` (the plain, non-AspectJ variant),
  it only considers Advisor beans carrying that role — a default-role bean is silently skipped.
- **D27 — `afterName`, not `@AutoConfiguration(after = PlatformSecurityAutoConfiguration.class)`.**
  `requiresPermissionAdvisor`'s `@ConditionalOnBean(CurrentUserAccessor.class)` only sees bean
  DEFINITIONS from auto-configurations already processed — Boot's own documented ordering caveat
  for `@ConditionalOnBean` across auto-configuration classes. A class-literal `after =` would
  require a compile dependency on `platform-security-autoconfigure`, which the constitution
  forbids (autoconfigure -> autoconfigure of another capability is not on the allowed matrix, even
  same-capability). `@AutoConfiguration(afterName = "...PlatformSecurityAutoConfiguration")` orders
  by string, avoiding the dependency while still fixing the real bug (found by inspecting
  `context.getBeansOfType(Advisor.class)` in a failing test: the advisor bean was silently never
  created, not merely unproxied).
- **D22 — 401/403 bodies bypass the errors capability's advice.** `AuthenticationException`/
  `AccessDeniedException` thrown inside the security filter chain are handled by
  `ExceptionTranslationFilter` before the `DispatcherServlet` (and its `@RestControllerAdvice`)
  ever runs, so `platform-errors-autoconfigure`'s advice cannot shape them. A minimal internal
  `ProblemDetailAuthenticationEntryPoint`/`ProblemDetailAccessDeniedHandler` pair writes the
  RFC-9457 body directly (manual JSON, no new dependency) to satisfy the phase-06 acceptance
  criterion without coupling security to errors (which the dependency constitution forbids anyway:
  autoconfigure -> autoconfigure of another capability is not on the allowed matrix).

## Phase 7 — messaging & events

- **D28 — `com.rabbitmq:amqp-client:5.31.0` pin.** Boot 4.1.0 manages `spring-amqp`'s
  `com.rabbitmq:amqp-client` at 5.30.0, but `org.testcontainers:testcontainers-rabbitmq`
  (testcontainers-bom 2.0.5, itself imported by `spring-boot-dependencies`) pulls 5.31.0
  transitively, failing `requireUpperBoundDeps`. Pinned to the higher version in
  `platform-dependencies` (same "first import wins" pattern as D17's prometheus override);
  re-check on every Boot/testcontainers bump and delete once they converge.
- **D29 — testcontainers 2.x artifact renames.** testcontainers-bom 2.0.5 renamed its per-module
  artifacts with a `testcontainers-` prefix (`testcontainers-kafka`, `testcontainers-rabbitmq`,
  `testcontainers-junit-jupiter`) versus the pre-2.x short names (`kafka`, `rabbitmq`,
  `junit-jupiter`) the phase-07 spec's era would have used; used the current artifactIds.
- **D30 — kafka/rabbit `EventTransport` beans live in `platform-messaging-autoconfigure`, not a
  per-provider autoconfigure module.** The phase-07 spec lists `messaging-kafka`/`messaging-rabbit`
  as plain implementation modules (no autoconfigure suffix), so their `EventTransport` beans are
  wired via nested `@Configuration` classes inside `PlatformMessagingAutoConfiguration`, guarded by
  `@ConditionalOnClass(KafkaTemplate.class)` / `@ConditionalOnClass(RabbitTemplate.class)` and each
  provider's own enable property. This is CLAUDE.md rule 5's explicit allowance: autoconfigure may
  depend on same-capability impls (optional dependency, guarded).
- **D31 — DLQ republish in `EventHandlerRegistrar` is transport-agnostic, not provider-specific.**
  Once `@EventHandler` retries are exhausted, the registrar republishes the raw message to
  `destination + dc.platform.messaging.dlq.suffix` via the same `EventTransport.send` — this works
  identically over inmemory/kafka/rabbit with no per-provider code, in addition to (not instead of)
  the broker-native DLQ each of kafka (`DeadLetterPublishingRecoverer`) and rabbit (DLX) wire up at
  the transport level for messages that fail before ever reaching a handler.

- **D32 — events outbox precursor, not a true outbox.** The relay publishes the integration event
  on the same after-commit callback as `@DomainEventHandler` dispatch, so a process crash between
  the local commit and the publish call still loses the integration event. A true outbox (durable
  staging row written in the same transaction, published by a separate poller/CDC process) is
  scoped as a documented future enhancement, not implemented in phase 7 — see
  `EventsProperties.Relay`'s javadoc.
- **D33 — mutually exclusive `AfterCommitDispatcher` nested configs.** `ImmediateDispatcherConfiguration`
  is guarded `@ConditionalOnMissingClass("...TransactionSynchronizationManager")`, not left
  unconditional, so it can never race `TransactionalDispatcherConfiguration` for which bean wins —
  nested `@Configuration` classes have no guaranteed processing order, so two unconditional
  `@ConditionalOnMissingBean` candidates for the same type is a latent bug, not just an unlikely one.

## Phase 8 — data/JPA, cache, redis

- **D34 — Money/CorrelationId converters use explicit `@Convert`, not global auto-apply.** A library
  `@Converter(autoApply=true)` only fires for converters inside the application's scanned persistence
  unit; a converter shipped in a platform jar is never scanned, so auto-apply would silently do
  nothing. Registering it globally would need a Boot-version-specific `EntityManagerFactoryBuilder`
  hook. The portable, unambiguous choice is `@Convert(converter = MoneyConverter.class)` per field,
  documented on the converter and in `docs/modules/data.md`.
- **D35 — snake_case physical naming left as Boot's default.** Boot already maps camelCase to
  snake_case via its default physical naming strategy; `PlatformDataJpaEnvironmentPostProcessor` sets
  only the non-default Hibernate tunings (open-in-view off, batch size 50, ordered inserts/updates,
  UTC jdbc time zone) and deliberately does not pin a naming-strategy class, which would couple the
  platform to a Boot-version-specific type. Verified by the H2 slice's native snake_case queries.
- **D36 — JPA auditing enablement gated on a real `EntityManagerFactory`.**
  `@EnableJpaAuditing` lives on a nested config guarded by `@ConditionalOnBean(EntityManagerFactory.class)`
  with the auto-configuration ordered `after HibernateJpaAutoConfiguration`, so a context without JPA
  infrastructure still starts cleanly (the auditing metamodel needs an EMF) and the mandatory
  ContextRunner matrix runs without spinning up a datasource. End-to-end auditing is proven by the
  H2 `@DataJpaTest` slice and the `@Tag("docker")` Postgres parity IT.

## Phase 9 — resilience, locking, scheduling, idempotency

- **D37 — `resilience4j-spring-boot4` 2.4.0, not the spec's `resilience4j-spring-boot3`.** The spec
  (written against Boot 3) says "Resilience4j spring-boot3"; under the D6 Boot-4 baseline that module
  does not target Boot 4's auto-configuration. Resilience4j 2.4.0 is the first line shipping a
  dedicated `resilience4j-spring-boot4` integration; its `resilience4j-bom` is imported in
  `platform-dependencies`, and `platform-starter-resilience` depends on `resilience4j-spring-boot4`.
- **D38 — `RetryableOperation` wires over a runtime `RetryRegistry`; no fallback registry; Micrometer
  binding delegated.** `PlatformResilienceAutoConfiguration` contributes `RetryableOperation` only
  `@ConditionalOnBean(RetryRegistry)` (that registry comes from `resilience4j-spring-boot4`, brought
  by the starter), ordered `afterName` Resilience4j's `RetryAutoConfiguration`. The auto-configure
  module deliberately does NOT define its own `RetryRegistry` (that would duplicate the starter's) and
  does NOT add a `TaggedRetryMetrics` binder — `resilience4j-spring-boot4` binds metrics by default, so
  a second binder would double-register meters. The capability's declarative annotations remain usable
  even where the helper is not (no registry); the matrix supplies a `RetryRegistry` directly.
- **D39 — no hand-written metadata for the `resilience4j.*` env defaults.** Property-conventions §7
  asks for `additional-spring-configuration-metadata.json` for EnvironmentPostProcessor-set keys that
  are invisible to the processor — but `resilience4j-spring-boot4` ships its own
  `spring-configuration-metadata.json` for every `resilience4j.*` key, so they are already documented.
  Same reasoning as the `spring.jpa.*` defaults in phase 8; no metadata added.

- **D40 — locking provider precedence via separate auto-configs ordered with `@AutoConfigureAfter`,
  not nested `@ConditionalOnMissingClass`.** The spec's back-off order is user bean > redis (if
  present) > jdbc (if a DataSource). Class-exclusion (as the cache module uses) would wrongly disable
  JDBC whenever Spring Data Redis is merely on the classpath without a configured Redis. Instead each
  provider is its own `@AutoConfiguration` guarded by `@ConditionalOnClass` + `@ConditionalOnBean` +
  `@ConditionalOnMissingBean(LockProvider)`, with `JdbcLockProviderAutoConfiguration`
  `@AutoConfigureAfter` the Redis one, so Redis wins the missing-bean race when both a template and a
  DataSource exist, and JDBC still works when Redis is absent. `PlatformLockingAutoConfiguration`
  (`after` both) wires the `LockManager`.
- **D41 — Flyway location appended via `FlywayConfigurationCustomizer`, not `spring.flyway.locations`.**
  Setting the property would REPLACE the application's locations; the customizer reads the existing
  `Location`s and appends `classpath:db/migration-platform-locking`, preserving the app's own. Guarded
  by `@ConditionalOnClass(Flyway, FlywayConfigurationCustomizer)` (Boot 4 packages the contract in
  `spring-boot-flyway`), so a JDBC-without-Flyway consumer still starts. The `starter-locking-jdbc`
  bundles `flyway-core` + `spring-boot-flyway` to make the happy path the default.

- **D42 — `@LockedSchedule` uses the plain-advisor + auto-proxy-registrar pattern (D26), not
  `@Aspect`.** The spec says "aspect", but the authz capability already established that
  `@EnableAspectJAutoProxy`/`@Aspect` drags in `aspectjweaver` at class-load time (D26). Scheduling
  reuses the proven pattern: a `StaticMethodMatcherPointcut` + `MethodInterceptor` +
  `DefaultPointcutAdvisor` (`@Role(ROLE_INFRASTRUCTURE)`) with an `ImportBeanDefinitionRegistrar`
  calling `AopConfigUtils.registerAutoProxyCreatorIfNecessary`. Same behavior, no new dependency.
- **D43 — the `@LockedSchedule` advisor is guarded by `@ConditionalOnClass(LockManager)`, and the
  interceptor resolves the `LockManager` softly.** The constitution requires other-capability api
  references to be `@ConditionalOnClass`-guarded, so the advisor config only loads when locking-api is
  present; the interceptor then reads the `LockManager` through an `ObjectProvider` and runs the method
  unlocked (warn once) when no bean exists. Consequence: a scheduling-only app without locking-api on
  the classpath gets no warning — acceptable, since the guard is mandatory and the warning still fires
  for the "locking present but not configured" case. `@LockedSchedule` itself lives in the api root
  package `ae.gov.dubaicustoms.platform.scheduling` (String attributes keep it dependency-poor), since
  the spec gives scheduling no separate -api module.
- **D44 — `platform-starter-scheduling` drops the spec's `spring-boot-starter-aop`.** Under the Boot-4
  baseline (D6) that aggregate starter no longer exists (the BOM manages `aspectjweaver` directly, not
  a `spring-boot-starter-aop`). The plain-advisor proxying (D42) needs only `spring-aop`, which arrives
  transitively through the autoconfigure's `spring-context`, so the starter depends on the autoconfigure
  alone. No AOP capability is lost.

- **D45 — idempotency store selection reuses the locking mechanism (D40); the "cache-backed" store is
  the Redis store.** The spec says the store is "cache/redis-backed … auto-chosen if present". Store
  selection uses the same separate-auto-configs-ordered-with-`@AutoConfigureAfter` pattern as locking
  (Redis preferred, JDBC fallback). Only the Redis store is implemented as the distributed option: a
  Spring `Cache`-backed store cannot offer an atomic put-if-absent (`Cache.get` then `put` races),
  whereas `SET NX PX` is atomic — and the platform's own cache-redis provider is Redis anyway. The
  default JDBC store lives in the autoconfigure module (`internal`), per the spec's module list (no
  separate `-jdbc` module), reusing the `platform_lock` table pattern with its own
  `platform_idempotency` table and the D41 Flyway-location-append.
- **D46 — `@Idempotent` rejects duplicates via the errors capability's `ConflictException` (409),
  guarded by `@ConditionalOnClass`.** Rather than defining an idempotency-specific exception in the
  api (which would not carry an HTTP-status hint the errors capability understands), the advisor throws
  `ConflictException` (`DC-IDEM-0409`). Its config is `@ConditionalOnClass(ConflictException)` so a
  service without the errors capability simply gets no `@Idempotent` interception. The advisor uses the
  plain auto-proxy creator (D26); `platform-starter-idempotency` drops the spec's `spring-boot-starter-aop`
  (absent under Boot 4, D44). The `Idempotency-Key` filter rejects duplicates only (no response replay,
  v1 scope) and is a plain `jakarta.servlet.http.HttpFilter` (no spring-web dependency).

## Pre-phase-3 baseline amendments

- **D6 — Java 25 / Spring Boot 4.x baseline.** Deliberate deviation from the spec pack's
  "Java 21, Spring Boot latest stable 3.x": the platform moves to Java 25 and Boot 4.1.0 BEFORE any
  capability code exists, when the cost is purely build plumbing. Toolchain bumps required for
  Java 25 class files (major 69): jacoco 0.8.15, sisu-maven-plugin 1.0.1, maven-plugin-tools 3.15.2,
  maven-enforcer-plugin/enforcer-api 3.6.3, japicmp 0.26.1, archunit 1.4.2, flatten 1.7.3.
  Boot 4 note: the modularized starters publish NEW canonical names (e.g.
  `spring-boot-starter-webmvc`) while the classic names the specs reference
  (`spring-boot-starter-web`, `spring-boot-autoconfigure`, `spring-boot-starter-test`) are still
  published at 4.1.0, so spec references remain valid; prefer the new names in new code where the
  spec does not pin one.
- **D7 — platform identity.** Placeholder replacement per the handoff runbook's one-time setup:
  base identity renamed from the spec pack's placeholder (short name and Maven groupId/Java root
  package) to `dc` / `ae.gov.dubaicustoms.platform`. Follows through everywhere the identity is
  load-bearing: Maven coordinates (aggregator artifactId now `dc-platform`), Java packages incl.
  build-tools test fixtures, `PlatformLayerRule`/`PlatformArchRules` constants and regexes, the
  scaffolder templates, property prefix (`dc.platform.<cap>`), error-code namespace
  (`DC-<CAP>-<NNNN>`), specs, runbooks, docs, and CI. The specs are the implementation authority
  for later phases, so they were swept too rather than left historical.
- **D47 — storage SPI is provider-interface-free.** The spec (phase-10 §A) offers an
  `ObjectStoreProvider { name(); create(props); }` but immediately proposes the simpler shape:
  providers just contribute an `ObjectStore` bean, so `platform-storage-spi` holds only the shared
  `KeyValidator` (path-traversal guard) and no provider interface. Chosen the simpler option per
  CLAUDE.md "when the spec is silent / two options": one fewer contract type to evolve, and provider
  selection is by classpath (fs default, S3 when present) exactly like locking/idempotency, not by a
  named-provider lookup.
- **D48 — filesystem sidecar uses Jackson.** The spec calls for a JSON metadata sidecar next to each
  object file. Rather than hand-roll a JSON writer (which would mis-escape arbitrary user-tag values —
  quotes, newlines, unicode), the fs provider depends on Boot-managed `jackson-databind` to serialise
  `ObjectMetadata`. Jackson is the fs provider's declared 3rd-party lib (impl-rule compliant); the
  version is inherited, not pinned.
- **D49 — storage checksum spools to a temp file.** The spec wants a SHA-256 computed on put and
  stored as a user tag, but the API is streaming-first (no `byte[]`, objects can exceed heap) and an
  `InputStream` can't be read twice. `ChecksumObjectStore` spools the content once through a
  `DigestInputStream` to a temp file, then hands the spool to the provider — bounded by disk, not
  memory. The alternative (buffer into heap) is the exact footgun the API forbids; the alternative
  (skip the tag, expose only the provider etag) fails the "stored as user tag" requirement. Trade-off:
  one extra disk write per put when checksums are on; toggle off via
  `dc.platform.storage.checksum.enabled=false`.
- **D50 — phase 10 ships storage + flags; secrets deferred.** The team manages secrets via Helm
  (injected as environment variables) and, locally, plain environment variables — which Spring Boot's
  relaxed binding already consumes natively, making the phase-10 secrets `env` provider redundant and
  the Vault provider unneeded today. Secrets is self-contained (nothing depends inbound on it; phases
  11–16 do not require it), so it was dropped from this phase and can be added later in its own PR with
  zero changes to storage/flags. Log redaction, the one real loss, is available via a user-registered
  `LogSanitizer` bean (the SPI ships in `platform-logging-api`). Consequently phase-10 delivers docs ×2
  (storage, flags), not ×3, and the `-Pdocker` vault suite is not present.
- **D51 — audit_jdbc stores the details map as a CLOB JSON document.** The `AuditEvent.details` map
  is schemaless by design (arbitrary structured context per action), so `JdbcAuditSink` serialises it
  to JSON with Jackson (the sink's declared 3rd-party lib alongside spring-jdbc) and stores it in a
  single `CLOB` column rather than modelling per-key columns. CLOB is H2-tested here; PostgreSQL
  deployments map CLOB→TEXT (Flyway runs the DDL against the target engine). The alternative
  (key/value child table) adds a join and write per detail entry for data that is only ever read back
  whole. Trade-off: details are not queryable by key in SQL; that is acceptable for an append-only
  trail whose primary consumers are the log pipeline and export.
- **D53 — the messaging audit sink is its own autoconfigure module, not an impl and not folded into
  the main audit autoconfigure.** The messaging sink must reference `EventPublisher` (messaging-api).
  An impl module may not depend on another capability's api (constitution: reach other capabilities
  from the autoconfigure layer), so the spec's `audit-messaging` cannot be an impl. Folding it into
  `platform-audit-autoconfigure` instead pushed that module to 7 platform dependencies (api, spi,
  core, log-sink, jdbc-sink, security-api, messaging-api) — over the autoconfigure fan-out ceiling of
  6. Resolution: `platform-audit-messaging-autoconfigure` is a dedicated autoconfigure module (cap
  `audit`) contributing the messaging `AuditSink`, guarded by `@ConditionalOnClass(EventPublisher)`.
  Ordering across modules (messaging > jdbc > log) uses name-based `@AutoConfigureAfter`, so no
  autoconfigure→autoconfigure dependency is introduced. It is not bundled in `platform-starter-audit`,
  keeping messaging-api off the classpath of audit consumers who do not use messaging.
- **D54 — rate-limit rejection is its own exception type, not a BusinessException.** The errors
  capability's `HttpStatusHint` is a closed 4xx enum with no 429 value, and adding one would change an
  errors contract. So `@RateLimited` rejection throws `RateLimitExceededException` (ratelimit-api,
  carrying the retry-after), and the ratelimit autoconfigure registers a Spring-MVC `@RestControllerAdvice`
  mapping it to a 429 `ProblemDetail` with `Retry-After`. The HTTP-filter path writes its own 429
  problem+json because a servlet filter runs before MVC exception handling. Both paths therefore emit
  RFC-9457 problem+json with a Retry-After header, without touching the errors capability.
