# Changelog

All notable changes to the DC Platform. Format: [Keep a Changelog](https://keepachangelog.com),
versioning: Semantic Versioning on the release train (all artifacts share one version).

## [Unreleased]

### Added
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
