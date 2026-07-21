# Changelog

All notable changes to the DC Platform. Format: [Keep a Changelog](https://keepachangelog.com),
versioning: Semantic Versioning on the release train (all artifacts share one version).

## [Unreleased]

### Fixed
- Phase 7: `platform-starter-messaging-{inmemory,kafka,rabbit}` — added `jackson-databind` as a
  required (non-optional) dependency of each starter. It's optional on
  `platform-messaging-autoconfigure` itself (a consumer supplying their own `EventSerializer`
  shouldn't be forced to add it), but a bare service with only a messaging starter and no web
  starter had no transitive path to Jackson at all, breaking the default JSON `EventSerializer` at
  startup (`No qualifying bean of type EventSerializer`). Found by the phase-07 acceptance script's
  scratch-app round trip.

### Added
- Phase 8: `platform-starter-data-jpa` — POM-only starter: the JPA autoconfigure (data-api arrives
  transitively) plus `spring-boot-starter-data-jpa` and `flyway-core`, so a service gets
  platform-conventional persistence and migrations by adding one dependency. Completes the phase 8
  data slice.
- Phase 8: `platform-data-jpa-autoconfigure` — `PlatformDataJpaAutoConfiguration`: Spring Data JPA
  auditing with `AuditorAware<String>` = the current user's subject when the security capability is
  present and the request is authenticated, else `"system"` (two mutually exclusive definitions keyed
  on `CurrentUserAccessor` class presence, so a data-only consumer never loads a security type);
  auditing enabled only once a real `EntityManagerFactory` exists (`@ConditionalOnBean`, ordered after
  `HibernateJpaAutoConfiguration`) so a no-JPA context still starts; `MoneyConverter`/
  `CorrelationIdConverter` (`jakarta.persistence` `AttributeConverter`s, opt-in via `@Convert`, D34);
  a `FlywayPresenceCheck` that fails startup when `require-migrations` is on but Flyway is absent, with
  an actionable message; and `PlatformDataJpaEnvironmentPostProcessor` contributing lowest-precedence
  `spring.jpa.*` defaults (open-in-view off, batch size 50, ordered inserts/updates, UTC jdbc time
  zone; snake_case naming left as Boot's default, D35). Tests: ContextRunner matrix, an H2
  `@DataJpaTest` slice (auditing + converter round-trip + snake_case naming), converter/EPP/Flyway
  units, and a `@Tag("docker")` Postgres parity IT.
- Phase 8: `platform-data-api` — tech-neutral persistence value objects with no JPA on the
  classpath: `Money` (immutable amount + ISO-4217 `Currency`, same-currency `add`/`subtract`, and
  the `toStorageString()`/`parse(String)` attribute-converter storage contract), `EntityId<T>`
  (typed-id wrapper), and `PersistenceConventions` (column lengths, audit column names,
  `snake_case` physical naming). `docs/modules/data.md`.
- Phase 7: `platform-starter-events` — POM-only starter: autoconfigure only (events-api arrives
  transitively); `docs/modules/events.md` (with a publish/dispatch sequence diagram). Completes the
  phase 7 events slice.
- Phase 7: `platform-events-autoconfigure` — `PlatformEventsAutoConfiguration`: bridges
  `DomainEventPublisher`/`@DomainEventHandler` to Spring's `ApplicationEventPublisher`;
  `AfterCommitDispatcher` strategy — `TransactionalDispatcher` (after-commit when a transaction is
  active, immediate otherwise) when spring-tx is present, `ImmediateDispatcher` fallback otherwise
  (mutually exclusive via `@ConditionalOnMissingClass`, decision D33); optional `DomainEventRelay`
  re-publishes `@EventType`-annotated domain events as integration events via the messaging
  capability's `EventPublisher`, gated by `dc.platform.events.relay.enabled` (default `false`) AND
  an actual `EventPublisher` bean (`@ConditionalOnBean`) — a lightweight outbox precursor, not a
  true transactional outbox (decision D32). Tests: same-tx handler ordering, real after-commit/
  rollback semantics against an H2-backed `DataSourceTransactionManager`, relay round-trip over the
  real in-memory transport.
- Phase 7: `platform-events-api` — `DomainEvent` marker interface, `DomainEventPublisher`,
  `@DomainEventHandler` method marker; zero platform dependencies (no core-api needed).
- Phase 7: `platform-messaging-test` — `TestEventTransport` (records `sent()`, `deliver(...)`
  simulates inbound messages independently of `send`), `EventsAssert`
  (`assertThatEvents(transport).sentTo("dc.orders").withType("OrderPlaced")`), and
  `@AutoConfigureTestTransport` (registers it as the `@Primary` `EventTransport` bean).
- Phase 7: `platform-starter-messaging-inmemory`/`-kafka`/`-rabbit` — POM-only starters, each
  bringing the autoconfigure module plus its one named `EventTransport` implementation;
  `docs/modules/messaging.md`. Completes the phase 7 messaging slice through the provider starters.
- Phase 7: `platform-messaging-rabbit` — `RabbitEventTransport`: `EventTransport` over spring-amqp's
  `RabbitTemplate`/`ConnectionFactory`, built from Boot's own `spring.rabbitmq.*`; destination syntax
  `"exchange"` or `"exchange:routingKey"`; each subscription declares (via `RabbitAdmin`) a durable
  topic exchange, a bound queue, and a dead-letter exchange + queue; quorum queues are opt-in
  (`x-queue-type=quorum`); redelivery is a stateless retry interceptor
  (`RejectAndDontRequeueRecoverer`) — exhausted messages route to the DLX automatically via the
  queue's `x-dead-letter-exchange` argument. Docker-free unit tests mock `RabbitAdmin`/
  `ConnectionFactory`; `@Tag("docker")` Testcontainers round-trip test excluded from the default
  build. Decision D28 (`docs/decisions/decision-log.md`): pinned `com.rabbitmq:amqp-client:5.31.0`
  in `platform-dependencies` — testcontainers-rabbitmq (testcontainers-bom 2.0.5) pulls a newer
  client than Boot 4.1.0 manages, failing `requireUpperBoundDeps`.
- Phase 7: `platform-messaging-kafka` — `KafkaEventTransport`: `EventTransport` over spring-kafka's
  `KafkaTemplate`/`ConsumerFactory`, built entirely from Boot's own `spring.kafka.*` (no broker
  config duplicated); destination = topic; each subscription's `KafkaMessageListenerContainer` gets
  a `DefaultErrorHandler` backed by `DeadLetterPublishingRecoverer` (broker-native DLQ, `<topic>.DLT`)
  honoring the platform retry properties; headers mapped verbatim. Unit tests are docker-free
  (real `ConsumerFactory`/deserializer wiring against an unreachable broker plus direct header/key
  conversion tests); `@Tag("docker")` Testcontainers round-trip test excluded from the default build.
- Phase 7: `platform-messaging-autoconfigure` — `PlatformMessagingAutoConfiguration`: wraps the sole
  `EventTransport` bean with a Jackson-JSON `EventSerializer` default, `DefaultEventPublisher`
  (correlationId/eventType/eventVersion headers, Micrometer `Observation`,
  `platform.messaging.published` counter), and `EventHandlerRegistrar` (a `BeanPostProcessor`
  scanning `@EventHandler` methods, dispatching with bounded retry then republishing to
  `destination + dc.platform.messaging.dlq.suffix` — transport-agnostic, works over any provider —
  plus `platform.messaging.handled` counters); `CapabilityDescriptor` reports INACTIVE with no
  publisher/registrar beans when zero or more than one `EventTransport` bean is present
  (fail-at-injection, not fail-at-boot); kill switch `dc.platform.messaging.enabled`. Nested
  `InMemoryTransportConfiguration`/`KafkaTransportConfiguration`/`RabbitTransportConfiguration`
  (decision D30) supply the corresponding `EventTransport` bean via optional same-capability impl
  dependencies, each guarded by `@ConditionalOnClass` on the provider's implementation/template
  type plus `@ConditionalOnMissingBean(EventTransport.class)` — real applications bring exactly one
  onto the classpath via their chosen starter; `dc.platform.messaging.rabbit.quorum-queues`
  (default `false`) added to `MessagingProperties`.
- Phase 7: `platform-messaging-inmemory` — `InMemoryEventTransport`: bounded in-JVM queues per
  (destination, group), one dispatcher virtual thread per subscription (competing consumers within
  a group, fan-out across groups), bounded redelivery with backoff then drop+log (`DC-MSG-0500`),
  `awaitIdle(Duration)` for sleep-free tests. The local/dev/test default and the messaging TCK
  (phase 12) reference implementation.
- Phase 7: `platform-messaging-spi` — `EventTransport` (provider contract: `send`/`subscribe`,
  at-least-once delivery obligation on implementations), `EventSerializer` (pluggable payload
  codec); depends only on `platform-messaging-api`.
- Phase 7: `platform-messaging-api` — `EventEnvelope<T>` (immutable envelope, `Builder` deriving
  `eventType`/`eventVersion` from `@EventType` or falling back to simple class name/version 1),
  `EventPublisher` (AT-LEAST-ONCE, blocking publish), `@EventHandler` method marker,
  `EventPublishException` (`DC-MSG-0001`), `EventSerializationException` (`DC-MSG-0002`).
- Phase 6: `platform-starter-restclient` — POM-only starter: autoconfigure only (restclient-api
  arrives transitively); `docs/modules/restclient.md`.
- Phase 6: `platform-security-authz-api` — `@RequiresPermission` (declarative method/type-level
  permission enforcement); artifacts prefixed `platform-security-authz-*` so the dependency
  constitution's capability inference groups authz with security, letting the SPI legitimately
  depend on `CurrentUser` (decision D25).
- Phase 6: `platform-security-authz-spi` — `PermissionEvaluatorProvider` (pluggable permission
  evaluation behind `@RequiresPermission`); depends on `platform-security-api` for `CurrentUser`
  as a same-capability edge (decision D25).
- Phase 6: `platform-starter-security-authz` — POM-only starter: autoconfigure only (authz-api/spi
  arrive transitively); `docs/modules/authz.md`. Completes the phase 6 restclient/security/authz
  slices.
- Phase 6: `platform-security-authz-autoconfigure` — `PlatformAuthzAutoConfiguration`: a plain
  (non-AspectJ) `InfrastructureAdvisorAutoProxyCreator` bridging `@RequiresPermission` to the
  ordered `PermissionEvaluatorProvider` beans (unauthenticated → 401 via
  `InsufficientAuthenticationException`, unauthorized → 403 via `AccessDeniedException`, both
  translated by the security capability's baseline chain); default
  `RolesClaimPermissionProvider` (configurable `dc.platform.authz.roles-claim`, default `roles`);
  kill switch `dc.platform.authz.enabled`.
- Phase 6: `platform-restclient-autoconfigure` — `PlatformRestClientAutoConfiguration`:
  `DefaultPlatformRestClientFactory` on the JDK HttpClient request factory
  (`ClientHttpRequestFactoryBuilder.jdk()`, Boot 4.1's replacement for the Boot-3-era settings
  API), per-client connect/read timeout overrides, correlation-header propagation, non-2xx
  responses mapped to `RemoteCallException`, ordered `PlatformRestClientCustomizer`s; a guarded
  `platformTokenRelayCustomizer` relays the current bearer token to outbound calls only when a
  JWT-shaped resource server is on the classpath AND the security capability's
  `CurrentUserAccessor` bean is actually registered (`@ConditionalOnClass` + `@ConditionalOnBean`,
  an optional edge to `platform-security-api`); kill switch `dc.platform.restclient.enabled`;
  `com.squareup.okhttp3:mockwebserver:4.12.0` pinned in `platform-dependencies`.
- Phase 6: `platform-starter-security` — POM-only starter: autoconfigure only (security-api
  arrives transitively); `docs/modules/security.md`.
- Phase 6: `platform-security-autoconfigure` — `PlatformSecurityAutoConfiguration`: stateless JWT
  resource-server `SecurityFilterChain` (permit-paths, security headers, RFC-9457-shaped 401/403
  bodies via internal entry-point/denied-handler since the errors advice cannot reach filter-chain
  exceptions, ordered `SecurityCustomizer`s applied before the platform's own catch-all so a
  customizer can open extra paths but not override authenticated-by-default), `JwtCurrentUserAccessor`;
  kill switch `dc.platform.security.enabled` plus explicit-only `dc.platform.security.mode=disabled`
  escape hatch (never profile-implied — secure by default).
- Phase 6: `platform-security-api` — `SecurityCustomizer` (ordered `HttpSecurity` extension
  point), `CurrentUser` (token-format-neutral principal: subject/tenant/roles/claims) +
  `CurrentUserAccessor`; sanctioned `spring-security-config` dependency because `HttpSecurity` is
  the model `SecurityCustomizer` configures (decision D19).
- Phase 6: `platform-restclient-api` — `PlatformRestClientFactory` (inject instead of
  `RestClient.Builder`), `RemoteCallException` (status/1KB-truncated body/remote correlation id),
  `PlatformRestClientCustomizer` SPI-lite; sanctioned `spring-web` dependency because
  `RestClient.Builder` is the model the factory hands back (decision D19).
- Phase 5: `platform-starter-openapi` — POM-only starter: autoconfigure +
  `springdoc-openapi-starter-webmvc-ui` (`/v3/api-docs`, `/swagger-ui.html`).
- Phase 5: `platform-openapi-autoconfigure` — `PlatformOpenApiAutoConfiguration`: `OpenAPI` bean
  (title/version default to `spring.application.name`/`info.app.version`, resolved at bean-build
  time so a blank key falls back too; bearer-jwt security scheme by default) and
  `platformProblemDetailOpenApiCustomizer` appending the platform `ProblemDetail` schema plus
  default 400/401/403/404/409/422/500 responses to every operation without overwriting an
  operation-defined status; kill switch `dc.platform.openapi.enabled`; springdoc 3.0.3 pinned in
  `platform-dependencies` (the Boot-4-compatible line); `docs/modules/openapi.md`.
- Phase 5: `platform-starter-observability` — POM-only starter: autoconfigure +
  `spring-boot-starter-actuator` + OTel tracing bridge (with Boot 4's companion
  `spring-boot-micrometer-tracing-opentelemetry`) + prometheus AND otlp registries (prometheus
  scrapes locally; OTLP export opt-in). `prometheus-metrics-bom` aligned to 1.7.0 in
  `platform-dependencies` (Boot 4.1.0 pins 1.5.1 against micrometer 1.17's declared 1.7.0,
  failing the upper-bound gate; decision D17).
- Phase 5: `platform-observability-autoconfigure` — common meter tags
  `service`/`env`/`platform.version` (`CommonTagsAutoConfiguration`, back-off bean name
  `platformCommonTagsCustomizer`); `PlatformObservabilityEnvironmentPostProcessor`
  (lowest-precedence `platform-observability-defaults` source) contributing actuator exposure
  (`health,info,platform,metrics,prometheus`), liveness/readiness health groups on every
  platform (readiness lists optional `db,rabbit,redis` members), correlation propagation via
  tracing baggage only (`X-Correlation-Id`; never a metric tag — cardinality), and OTLP export
  OFF by default; `/actuator/platform` endpoint serving the `CapabilityDescriptor` report; kill
  switch `dc.platform.observability.enabled`; `docs/modules/observability.md`.
- Phase 5: platform jar manifests now carry `Implementation-Version` (maven-jar-plugin
  `addDefaultImplementationEntries` in `platform-parent`) so the `platform.version` common tag
  reports the real train version at runtime.

### Fixed
- `platform-parent`'s `maven-jar-plugin` manifest configuration (Implementation-Version for the
  `platform.version` common tag) lived only in `pluginManagement` and was silently ignored by
  the implicit default-jar binding; also, plexus-archiver's "is uptodate" check meant an
  already-built jar kept its stale manifest even after the config was corrected, until a clean
  build. Fixed by declaring the plugin explicitly (pinned to 3.5.0) in `platform-parent`'s
  `<build><plugins>` alongside the `pluginManagement` entry; verified end-to-end against a
  packaged scratch app (`/actuator/prometheus` now reports `platform_version="0.2.0-SNAPSHOT"`
  instead of `"unknown"`).
- `platform-service-parent`'s self-contained `<revision>` (its parent is
  `spring-boot-starter-parent`, so it cannot inherit the aggregator's) was left at
  `0.1.0-SNAPSHOT` by the 0.2.0-SNAPSHOT train bump, making it build at the wrong version and
  import a stale `platform-bom:0.1.0-SNAPSHOT` in reactor builds; bumped to `0.2.0-SNAPSHOT`
  (phase-04 audit finding).

## [0.1.0] - 2026-07-20

Milestone M1: a service built on the platform gets correlation IDs, structured JSON logs,
RFC-9457 error responses, and common validation constraints out of three starters.

### Changed
- Platform baseline moved to Java 25 and Spring Boot 4.1.0 (decision D6); toolchain plugins bumped
  for Java 25 class files (jacoco, sisu, maven-plugin-tools, enforcer, japicmp, archunit, flatten).
- Platform identity renamed to `dc` / `ae.gov.dubaicustoms.platform` (decision D7): Maven groupId,
  Java root package, property prefix `dc.platform.*`, error-code namespace `DC-*`, specs and docs.

### Added
- Phase 4: `platform-starter-validation` — POM-only starter (autoconfigure +
  `spring-boot-starter-validation`); `docs/modules/validation.md`.
- Phase 4: `platform-validation-autoconfigure` — `PlatformValidationAutoConfiguration`
  (kill switch `dc.platform.validation.enabled`): `platformValidator` interpolating messages
  through `platform-validation-messages.properties`, filtered method validation on (records
  excluded from proxying, as Boot does), banner line; backs off to any user `Validator` bean.
- Phase 4: `platform-validation-api` — common constraints with validators: `@NotBlankTrimmed`,
  `@Ulid` (Crockford base32, case-insensitive), `@SafeText` (rejects ISO control characters),
  `@FutureInstant` (clock-provider based); message keys resolve via the shipped
  `ContributorValidationMessages.properties`.
- Phase 4: `platform-starter-logging` — POM-only starter (autoconfigure +
  logstash-logback-encoder); `docs/modules/logging.md`.
- Phase 4: `platform-logging-autoconfigure` — `PlatformLoggingEnvironmentPostProcessor`
  (spring.factories; the one allowed legacy registration) defaulting `logging.config` to the
  shipped `logback-platform.xml` (logstash JSON, ECS-ish fields `@timestamp`/`level`/`logger`/
  `message`/`service`/`correlationId`/`stack_trace`) via the lowest-precedence
  `platform-logging-defaults` source; console fallback on the `local` profile; kill switch
  `dc.platform.logging.enabled`; banner line reports the effective format.
- Phase 4: `platform-logging-api` — `Kv.of(key, value)` structured-argument helper and the
  `LogSanitizer` customizer SPI-lite; pure JDK, no logstash/slf4j types in signatures.
- Phase 4: `platform-starter-errors` — POM-only starter for the errors capability (autoconfigure
  only; the service brings its own web stack); `docs/modules/errors.md`.
- Phase 4: `platform-errors-autoconfigure` — `PlatformErrorHandlingAutoConfiguration` registering
  the RFC-9457 advice pair (`platformExceptionHandler` + `platformValidationExceptionHandler`,
  kill switch `dc.platform.errors.enabled`): status from `HttpStatusHint`, `type` = base URI +
  code, `code`/`correlationId`/`timestamp` extensions, validation `errors[]` with
  password/secret/token redaction, generic 500 fallback `DC-CORE-0500`; plus the build-time
  error-code registry gate writing `target/error-codes.csv`.
- Phase 4: `platform-errors-api` — `BusinessException`/`NotFoundException`/`ConflictException`
  with `HttpStatusHint`, and the `ProblemDetailCustomizer` SPI-lite; sanctioned `spring-web`
  dependency because `ProblemDetail` is the RFC-9457 model (decision D11).
- Phase 4: `CapabilityDescriptor` report contract moved from core-autoconfigure to core-api so
  capability auto-configurations can register descriptors constitutionally (decision D12).
- Phase 3: `platform-starter-core` — the smallest useful chassis (POM-only starter: core-api +
  core-autoconfigure + `spring-boot-starter`); `docs/modules/core.md`; service-parent README
  usage snippet (capabilities stay explicit, ADR-007).
- Phase 3: `platform-core-autoconfigure` — `CoreContextAutoConfiguration` (correlation-id servlet
  filter at highest precedence, kill switch `dc.platform.core.enabled`) and
  `PlatformBannerAutoConfiguration` (`platform: core[ACTIVE], …` startup line collected from
  `CapabilityDescriptor` beans in `ae.gov.dubaicustoms.platform.core.report`).
- Phase 3: `platform-core-api` — `@PlatformApi`/`@PlatformInternal` markers, `PlatformException` +
  `ErrorCode` (`DC-<CAP>-<NNNN>`), `CorrelationId` + MDC-backed `RequestContext`; public API surface
  capped at 25 types by an executable test.
- Phase 1: reactor foundation — aggregator, `platform-parent`, `platform-service-parent`,
  `platform-bom`, `platform-dependencies`, optional local docker compose, repo hygiene.
- Phase 2: `platform-build-tools` — `PlatformLayerRule` enforcer rule (dependency constitution:
  layer matrix, fan-out ceilings, version-tag ban), `PlatformArchRules` ArchUnit library,
  shared checkstyle config, `ArchConstitutionTest` template.
- Phase 2: gates wired into `platform-parent` — checkstyle, `platformLayerRule` +
  `requireUpperBoundDeps`, jacoco line coverage ≥ 0.80 (halt on failure), japicmp configured
  (inert until first release).
- Phase 2: `platform-build-maven-plugin` — `new-module` scaffolding goal (registers new modules
  in the root reactor and `platform-bom`) and `check-bom` BOM-completeness goal.
- Phase 2: GitHub Actions CI — push/PR verify + check-bom, nightly `-Pdocker` job.
