# Changelog

All notable changes to the DC Platform. Format: [Keep a Changelog](https://keepachangelog.com),
versioning: Semantic Versioning on the release train (all artifacts share one version).

## [Unreleased]

### Changed
- Secrets references reconciled to the sanctioned pattern instead of a platform module (decision D81;
  supersedes the secrets portion of D80). Services consume secrets from HashiCorp Vault via Spring
  Cloud Vault as ordinary `${...}` property placeholders, rotating static-KV values by rolling pod
  restart — so no `secrets` capability is built. The `noSystemGetenv` usage rule
  (`platform-test-api`, `testing.arch`) now names "Spring config placeholders (populated by Spring
  Cloud Vault)" as the alternative rather than a nonexistent "platform secrets property source"
  (rule message, javadoc, `PlatformUsageRulesTest`, the `EnvReader` fixture, and `docs/modules/dx.md`
  updated). `spring-cloud-vault` stays off the dependency ban list by design; the
  secrets-unresolvable-ref FailureAnalyzer and the `platform-tck-secrets` TCK (D57) remain absent by
  design. See `specs/phase-17-secrets.md`.
- Kafka messaging transport labelled **experimental**. It has Testcontainers round-trip tests but is
  not part of the supported golden path (no example, absent from the smoke matrix, platform infra does
  not yet provide Kafka — RabbitMQ is the supported broker). Noted in
  `platform-starter-messaging-kafka` (POM description) and `docs/modules/messaging.md` so adopters pin
  it deliberately.

### Fixed
- Build: `platform-parent` sets `useManifestOnlyJar=false` on surefire and failsafe. On Windows,
  when the project and the local Maven repo live on different drives, surefire could not relativize
  its manifest-JAR classpath across roots and the forked JVM silently dropped entries, surfacing as
  `NoClassDefFoundError` (archunit's `JavaClasses`) in `ArchConstitutionTest`. Passing the classpath
  directly avoids the manifest JAR; no effect on same-drive/Linux/CI builds.

### Added
- Usage-snippet rot gate: `UsageSnippetCompileTest` (platform-docs) compiles every `snippet:<id>`-tagged
  Java block in `docs/modules/*.md` in-memory against the platform types on the test classpath, so the
  examples the platform index / MCP server hand to agents can never silently drift from the current API
  (a renamed method or type fails the build, naming the page and snippet). The classpath is resolved from
  the Surefire isolated classloader so the gate works forked and in-process; authored snippets need no
  imports (a platform + Spring/Jakarta import preamble is prepended). Seeded on four capabilities
  (messaging, events, validation, redis); tagging a page's usage example also makes it the capability's
  published index snippet (`PlatformIndexGenerator` prefers tagged blocks). Untagged Java blocks stay
  illustrative fragments and are not compiled — coverage grows as pages adopt the tag.
- Phase 16 (A.1): apiguardian `@API(status, since)` stability markers on every public API/SPI contract
  type — a Layer-0 discovery signal read straight from the jar. New `org.apiguardian:apiguardian-api`
  pin in `platform-dependencies`; api-root types are `STABLE`, SPI types `EXPERIMENTAL`. Two new
  `PlatformArchRules`: every published contract type must carry `@API`, and apiguardian `DEPRECATED`
  must co-occur with `java.lang.@Deprecated`. `@PlatformApi` is retained alongside (decision D79).
- Phase 16 (A.2): `FailureAnalyzer`s that turn the top misconfigurations into Boot "Description /
  Action" diagnostics whose Action names the exact fix (starter, property, or doc anchor) —
  messaging-no-transport, security-no-issuer, data-jpa-missing-flyway, storage-fs-root-unwritable.
  Registered per capability in `META-INF/spring.factories`; each is unit-tested. (No
  secrets-unresolvable-ref analyzer: secrets are consumed via Spring Cloud Vault, not a platform
  module — see the Changed entry below and decision D81.)
- Phase 16 (B.1): dependency bans in `platform-service-parent` — an enforcer `bannedDependencies`
  (`searchTransitive=false`) fails a service that declares a wrapped library directly (spring-kafka,
  spring-rabbit, awssdk:s3, resilience4j-*, springdoc-*), each message naming the starter to use
  instead. Escape hatch `-Dplatform.bans.skip=true`. (spring-cloud-vault is intentionally NOT banned —
  it is the sanctioned mechanism for consuming secrets; see the Changed entry below and decision D81.)
- Phase 16 (B.2): finalized `PlatformUsageRules` (platform-test-api `testing.arch`) — added three
  consumer conformance rules: no `ResponseEntityExceptionHandler` subclass, no `new ObjectMapper()`,
  no `Thread.sleep` in production. Each violation message names the platform alternative + doc anchor.
- Phase 16 (C): a platform MCP server so agents query authoritative facts instead of guessing.
  `platform-docs` generates `platform-index.json` (capabilities + starter coords, config keys, error
  codes, API/SPI inventory with `@API` status, usage snippets) with a completeness gate against the
  reactor's starters. New `tooling/platform-mcp-server` (MCP Java SDK) bundles that index and serves
  six read-only tools over stdio — `find_capability`, `property_lookup`, `error_code_lookup`,
  `usage_example`, `list_starters`, `platform_version` — via `java -jar platform-mcp-server.jar --stdio`.
  Tool contract tests run against a fixture index. Added to the reactor and BOM; MCP SDK pinned in
  platform-dependencies.
- Phase 16 (D): agent enablement beyond the MCP server. New `tooling/platform-skill` generates a
  Claude Skill (`SKILL.md` + condensed resources) from the platform index and packages
  `platform-skill.zip`, with a staleness gate that its version equals the train version. New
  `tooling/platform-migrations` (OpenRewrite): `AdoptPlatform` swaps directly-declared wrapped
  libraries for the platform starters and flags hand-rolled `@ControllerAdvice` for review (with the
  rationale, not deleted); a `UpgradeTo_1_0` skeleton bumps the service parent. `platform-docs`
  generates `llms.txt` + `llms-full.txt` from the nav with Diátaxis labels. New
  `docs/concepts/discovery.md` explains the five discovery layers. Both modules added to the reactor
  and BOM; OpenRewrite pinned (with ASM/annotations convergence alignments) in platform-dependencies.
- Phase 16 (E): the service archetype now also generates `.mcp.json` (platform MCP endpoint +
  stdio-fallback note) and `catalog-info.yaml` (Backstage Component: name, `owner` param,
  `dc.platform/train` annotation). New `owner` archetype property (default `platform-team`).
  `golden-path.sh` asserts all four generated files (CLAUDE.md, AGENTS.md, .mcp.json,
  catalog-info.yaml) and that the train annotation was filtered.
- Phase 16 (F): adoption telemetry. New `CapabilityMetricsAutoConfiguration` emits
  `platform.capability.active{capability=...}` (0/1) from the `CapabilityDescriptor` beans (kill
  switch `dc.platform.observability.capability-metrics.enabled`). New `docs/operations/adoption.md`
  and importable `tooling/dashboards/platform-adoption.grafana.json`; `release.sh` release notes now
  carry an Adoption section (Grafana deep-link TODO until an instance exists).

## [1.0.0-RC1] - 2026-07-27

Milestone **M3**: full catalog, docs, examples, and release pipeline. Soak candidate for `1.0.0`.

### Added
- Phase 15: release automation (§D). `tooling/scripts/release.sh <version> [--rehearse|--notes-only]`
  runs verify → golden-path → smoke-matrix → release notes (from conventional commits since the last
  tag) → aggregate japicmp compatibility report, then either stages to a file:// repo (`--rehearse`,
  `-Plocal-release`) or tags `v<version>` (real). New `local-release` profile on the root aggregator
  and `platform-service-parent` (file:// `altDeploymentRepository` on the deploy plugin, reaching every
  deployable artifact). New `.github/workflows/release.yml`: tag-triggered deploy with
  CI secrets plus a nightly `compat-n-1` job (no-op until the first tag exists). Finalized
  `docs/runbooks/release.md` to match the script. japicmp degrades to a first-baseline report while no
  train has been deployed.
- Phase 15: reference examples. New `examples/example-minimal` (category Examples) — the platform
  floor: `platform-starter-core` + `-errors` + `-logging` only, proving correlation IDs, JSON logs,
  and RFC-9457 problem responses with no cross-cutting code in the service. Consumes the platform via
  `platform-service-parent`; `maven.deploy.skip=true` and enforcer-exempt via the `example-` prefix.
  Adds the `docs/examples.md` overview page. (Further phase-15 examples, the smoke matrix, and release
  automation land in subsequent commits.)
- Phase 15: `examples/example-golden-path` — the canonical reference service. A small orders domain
  (`POST /orders`, `GET /orders/{id}`) over the full golden-path stack: REST + validation + security
  + OpenAPI + observability + JPA (H2 local, `pg` profile) + messaging (in-memory) + cache +
  resilience + audit. `OrderService` demonstrates `EventPublisher`, `RetryableOperation`, `@Audited`,
  `@Cacheable`, and `@Transactional`. Tested through the platform slices (`OrderFlowTest`,
  `OrderProblemResponseTest`, `PlatformSurfaceTest`) plus a `StartupBudgetTest` startup-budget guard
  (deliverable C) with a checked-in, comment-governed baseline. Kafka deferred (infra supports
  RabbitMQ; see `example-event-driven`).
- Phase 15: `examples/example-event-driven` — a producer and a consumer communicating over platform
  messaging (aggregator + two `example-`-prefixed modules). Producer publishes `ShipmentRequested`
  via `EventPublisher`; consumer receives via `@EventHandler`. `ShipmentRetryDlqTest` demonstrates the
  retry-then-DLQ path (3 attempts → republish to `dc.shipments.dlq`) Docker-free over the in-memory
  `TestEventTransport`; `ShipmentRoundTripTest` covers the happy path. Default transport in-memory
  (`local` profile); a `rabbit` profile runs against real RabbitMQ. Kafka deferred (infra supports
  RabbitMQ).
- Phase 15: `examples/example-extension-provider` — demonstrates the extension model. A custom
  `EncryptingFsObjectStore` (AES-CTR, length-preserving, IV stashed in user-tags) implements
  `ObjectStore` and is registered by `EncryptingStorageAutoConfiguration` ordered before the
  platform's `FsObjectStoreAutoConfiguration`, which backs off via `@ConditionalOnMissingBean`.
  Certified by `EncryptingFsObjectStoreTckTest extends ObjectStoreTck`; back-off proven by
  `StorageBackOffTest`.
- Phase 14: documentation as a product. New `docs/platform-docs` module (category Documentation)
  with build-time generators for the config-property, error-code, and BOM references, plus two
  build-breaking gates — a completeness check (every `dc.platform.*` metadata key is documented and
  every `platform-starter-*` has a capability page) and an in-JVM broken-link check over the docs
  tree. Adds the site `index`, the four `concepts/` pages, the ten `decisions/adr-0NN` records, the
  four `runbooks/`, `reference/compatibility`, and `upgrade/0.2.0`. `mkdocs.yml` renders the HTML
  site via the opt-in `-Pdocs-site` profile; `-Papidocs` publishes aggregate javadoc under
  `docs/site/apidocs`. Decisions D68–D72.

### Fixed
- Phase 14: config metadata was silently absent platform-wide. Each `*-autoconfigure` module already
  declared `spring-boot-configuration-processor`, but under JDK 23+ javac no longer runs annotation
  processors discovered on the classpath by default, so no `spring-configuration-metadata.json` was
  ever emitted (breaking IDE autocomplete and leaving the phase-14 property reference empty). Added
  `<proc>full</proc>` to `platform-parent`'s `maven-compiler-plugin`; ~23 modules now emit metadata.
- Phase 7: `platform-starter-messaging-{inmemory,kafka,rabbit}` — added `jackson-databind` as a
  required (non-optional) dependency of each starter. It's optional on
  `platform-messaging-autoconfigure` itself (a consumer supplying their own `EventSerializer`
  shouldn't be forced to add it), but a bare service with only a messaging starter and no web
  starter had no transitive path to Jackson at all, breaking the default JSON `EventSerializer` at
  startup (`No qualifying bean of type EventSerializer`). Found by the phase-07 acceptance script's
  scratch-app round trip.

### Added
- Phase 13: docs — `docs/quickstart.md` rewritten around the archetype (generate → build → run → add a
  capability), and `docs/modules/dx.md` covering the archetype, `upgrade-check`, the consumer
  conformance rules + escape hatch, and the golden path. Decisions D63–D67 recorded.
- Phase 13: `tooling/scripts/golden-path.sh` — the executable DX contract: installs the platform,
  generates a service from the archetype (features=messaging), builds it (asserting `CLAUDE.md` and a
  run `PlatformConformanceTest`), boots it with `spring-boot:start`, probes `/actuator/health` and
  `/actuator/platform | grep messaging`, and stops it — failing past a 10-minute wall-clock SLA. Wire
  it into CI as a required job. Pins `maven-archetype-plugin:3.1.2` because 3.2.0+ made
  `archetype:generate` fork a lifecycle that fails project-less on Maven 3.9.x.
- Phase 13: `tooling/platform-service-archetype` — a Maven archetype (`maven-archetype` packaging)
  that generates a ready-to-run platform service: POM on `platform-service-parent` wired with the
  golden-path starters (core/errors/logging/validation/observability/openapi/security + test) and
  optional `-Dfeatures=messaging,data` slices (starters + sample code), a Hello controller/service,
  a profile-aware `application.yml`, `@PlatformWebTest`/`@PlatformTest` tests, a generated
  `PlatformConformanceTest` (runs `PlatformUsageRules`), and `CLAUDE.md`/`AGENTS.md` so coding agents
  use platform APIs. Parented to the bare root aggregator (escapes the enforcer/coverage gate like
  `build/*`). An opt-in `-Parchetype-it` profile generates + verifies basic/full projects; the
  default reactor build never runs it (it needs the platform installed first — golden-path does that).
- Phase 13: `platform-build-maven-plugin:upgrade-check` — a goal that reports what changes when a
  service upgrades to `-Dplatform.target=<version>`: resolves the target `platform-bom` and diffs its
  managed versions against the project's current ones, and scans the project's `application*` config
  for keys deprecated in the target version (read from the target platform jars'
  `spring-configuration-metadata.json`). Writes `target/platform-upgrade-report.md` + a console
  summary linking the train release notes. Also hardened `check-bom` to exempt `maven-archetype`
  generators (nothing depends on them through the BOM).
- Phase 13: `platform-test-api` — `PlatformUsageRules.all()` (package `…test.arch`), the consumer
  conformance ArchUnit rules a service runs over its own classes via the archetype's generated
  `PlatformConformanceTest`. Bans direct `KafkaTemplate`/`RabbitTemplate`/listener-container use
  ("use EventPublisher/@EventHandler"), user `@RestControllerAdvice extends ResponseEntityExceptionHandler`
  ("throw PlatformException subtypes"), and `System.getenv` ("use platform secrets"). Rules match
  banned types by FQN, so the kit adds no Kafka/Rabbit/MVC dependency of its own.
- Phase 12: `platform-test-api` — the platform test kit (Test Support). Composed slice annotations
  `@PlatformTest`, `@PlatformMessagingTest`, `@PlatformDataTest`, `@PlatformWebTest`; the `Containers`
  singleton-Testcontainers factory and `DockerAvailable` assumption guard for `@Tag("docker")` tests;
  `TestTokens` JWT post-processor sugar for the OAuth2 resource-server security; and AssertJ
  `assertThatProblem(...)` assertions over RFC-9457 `ProblemDetail` bodies (incl. the `code` extension).
- Phase 12: `platform-starter-test` — POM-only starter bundling the test kit + `spring-boot-starter-test`
  + the recording messaging transport + `json-path`; add it to a service in `test` scope.
- Phase 12: provider TCKs (published Test Support jars, extend-and-supply-your-provider) —
  `platform-tck-messaging` (`EventTransportTck`), `platform-tck-storage` (`ObjectStoreTck`),
  `platform-tck-locking` (`LockProviderTck`), `platform-tck-flags` (`FlagProviderTck`),
  `platform-tck-ratelimit` (`RateLimiterProviderTck`). Each is applied docker-free to the reference
  provider (in-memory / filesystem / JDBC-on-H2); broker/Redis/S3 certifications run under `@Tag("docker")`.
- Phase 12: `docs/testing.md` — how to use the starter, slices, fixtures, and TCKs.
- Phase 12: `PlatformLayerRule` now models the test-kit module shapes — `*-test-api` is Test Support,
  and the `test` starter may aggregate Test Support modules (still never another starter).
- Phase 11: `platform-starter-files` — POM-only starter: the files autoconfigure (magic-byte content
  validation, default `FileUploadPolicy`, servlet multipart limits). Add the storage capability to
  stream downloads via `StreamingDownloads`.
- Phase 11: `platform-starter-ratelimit` — POM-only starter: the ratelimit autoconfigure + the
  in-memory provider. Enable the all-requests filter with `dc.platform.ratelimit.http.enabled=true`;
  add `platform-ratelimit-redis` + a `StringRedisTemplate` for a cluster-wide limit.
- Phase 11: `platform-starter-audit` — POM-only starter: the audit autoconfigure + the log sink. Add a
  `DataSource` for the JDBC sink, or the messaging capability + `platform-audit-messaging-autoconfigure`
  to publish audit events to `dc.audit` (sink chosen messaging > jdbc > log).
- Phase 11: `platform-files-autoconfigure` — `PlatformFilesAutoConfiguration`: the default magic-byte
  `ContentTypeValidator` (pdf/png/jpg/zip/csv/txt; no Tika, D55), a default `FileUploadPolicy` from
  `dc.platform.files.*`, servlet multipart size limits (ahead of Boot's), and the public
  `StreamingDownloads` helper that streams a storage `ObjectStore` object to an HTTP response (guarded
  by the storage capability; D56). Every bean backs off on a user equivalent. Tests: ContextRunner
  matrix, sniffing table, policy/property binding, multipart, and streaming download (200/404).
- Phase 11: `platform-files-api` — safe file-handling primitives: `FileUploadPolicy` (size +
  content-type allow-list), `SafeFilename.sanitize` (path-traversal / null-byte / control-char safe
  filename), and the `ContentTypeValidator` contract (magic-byte content sniffing, not extension). No
  servlet or storage types leak here.
- Phase 11: `platform-ratelimit-autoconfigure` — `PlatformRateLimitAutoConfiguration` (+ per-provider
  `RedisRateLimiterAutoConfiguration` / `InMemoryRateLimiterAutoConfiguration`): a `RateLimiter` over the
  provider chosen by classpath (Redis over in-memory; WARNs in `prod` when the per-JVM provider is
  active), a plain (D26) AOP advisor enforcing `@RateLimited` (SpEL key, throws
  `RateLimitExceededException`), an optional all-requests servlet filter
  (`dc.platform.ratelimit.http.enabled=true`, keyed by user|ip) emitting 429 + `Retry-After` +
  problem+json, a Spring-MVC advice mapping `RateLimitExceededException` to 429 (D54), and Micrometer
  decision counters when present. `CapabilityDescriptor` names the provider. Tests: ContextRunner
  matrix, provider selection, `@RateLimited` behavior (incl. keyed), metrics, filter, and advice.
- Phase 11: `platform-ratelimit-api` (addendum) — `RateLimitExceededException` (carries `retryAfter`),
  thrown by `@RateLimited` and mapped to HTTP 429.
- Phase 11: `platform-ratelimit-redis` — a cluster-wide `RateLimiterProvider`: a fixed-window counter
  via an atomic INCR + PEXPIRE Lua script (hash-tagged key, retry-after = remaining TTL). Fails open on
  Redis errors. Command-level unit tests are Docker-free; a `@Tag("docker")` IT verifies real Redis.
- Phase 11: `platform-ratelimit-inmemory` — the default `RateLimiterProvider`: a per-JVM
  sliding-window counter backed by Caffeine (current + weighted previous window). Zero infrastructure;
  counters are not shared across instances (documented limitation — use Redis for a cluster-wide
  limit). Deterministically tested via an injected clock.
- Phase 11: `platform-ratelimit-api` — the rate-limiting contract: `RateLimiter`
  (`Decision tryAcquire(String key, int permits, Duration window)`), the
  `@RateLimited(name, permits=100, window="PT1M", keyExpression)` method annotation, and the
  `Decision` record (`allowed`, `retryAfter`) with `allow()` / `deny(Duration)` factories.
- Phase 11: `platform-ratelimit-spi` — the provider contract: `RateLimiterProvider` (same
  `tryAcquire` shape), implemented by the inmemory/redis backends; fail-open on backend errors.
- Phase 11: `platform-audit-messaging-autoconfigure` — the top link in the audit degradation chain: an
  `AuditSink` publishing audit events to the messaging transport (`dc.audit`), guarded by
  `@ConditionalOnBean(EventPublisher)`. A dedicated autoconfigure module (not an impl, not folded into
  the main audit autoconfigure) to satisfy the impl→other-cap ban and the fan-out ceiling, and to keep
  messaging-api off audit consumers who do not use messaging (decision D53).
- Phase 11: `platform-audit-autoconfigure` — `PlatformAuditAutoConfiguration` (+ per-sink
  `JdbcAuditSinkAutoConfiguration` / `LogAuditSinkAutoConfiguration` + `AuditSecurityAutoConfiguration`):
  the async `Auditor` over the `AuditSink` chosen by a messaging → jdbc → log degradation chain (with a
  startup WARN naming the active sink), a plain (non-AspectJ, D26) AOP advisor enforcing `@Audited`
  (actor from the security capability's `CurrentUserAccessor` when present, else `anonymous`; resource
  from the annotation's SpEL; outcome from the method's return/throw), a bounded-queue worker that
  drops-and-WARNs rather than block the request thread (D52), and the JDBC sink's Flyway location
  wiring. `CapabilityDescriptor` names the active sink. Tests: ContextRunner matrix, sink selection,
  actor resolution, and the `@Audited` success/failure behavior.
- Phase 11: `platform-audit-jdbc` — an `AuditSink` appending each `AuditEvent` to a single append-only
  `platform_audit` table, with the details map serialised to a JSON CLOB (decision D51). Ships its
  Flyway migration under `db/migration-platform-audit`; H2-tested, no Docker.
- Phase 11: `platform-audit-log` — the default `AuditSink`: writes each `AuditEvent` as one structured
  `key=value` line to a dedicated `AUDIT` logger at INFO, so the audit trail rides the JSON logging
  pipeline with zero infrastructure. The always-available fallback in the degradation chain.
- Phase 11: `platform-audit-spi` — the audit provider contract: `AuditSink` (`write(AuditEvent)`), the
  pluggable destination behind `Auditor`. Implemented by the log/jdbc/messaging sinks; applications
  never depend on it directly.
- Phase 11: `platform-audit-api` — the audit contract: `Auditor` (programmatic `record(AuditEvent)`),
  the `@Audited(action, resourceExpression)` method annotation, the `AuditEvent` record (action,
  actor, resource, outcome, at, correlationId, details) and the `Outcome` enum. No sink types leak
  here; applications depend only on this module.
- Phase 10: `platform-starter-flags` — POM-only starter: the flags autoconfigure + in-memory provider.
  Seed via `dc.platform.flags.static.*`, flip at runtime through the `platformflags` endpoint; add an
  OpenFeature `Client` bean to switch providers.
- Phase 10: `platform-flags-autoconfigure` — `PlatformFlagsAutoConfiguration` (+ per-provider
  `OpenFeatureFlagProviderAutoConfiguration` / `InMemoryFlagProviderAutoConfiguration` +
  `FlagsSecurityAutoConfiguration`): a `FeatureFlags` over the `FlagProvider` chosen by classpath
  (OpenFeature when a `Client` bean is present, else in-memory). A plain (non-AspectJ, D26) AOP advisor
  enforces `@FeatureGate` — skip + neutral return value by type. The evaluation context resolves the
  current user/tenant from the security capability's `CurrentUserAccessor` when present (guarded by
  `@ConditionalOnClass`). The `platformflags` actuator endpoint reads/sets/removes in-memory flags at
  runtime. `CapabilityDescriptor` names the provider. Tests: ContextRunner matrix, provider selection,
  static-flag coercion, the `@FeatureGate` behavior table, and the endpoint. `docs/modules/flags.md`.
- Phase 10: `platform-flags-openfeature` — a `FlagProvider` adapter over the OpenFeature SDK (1.9.1):
  reads flags type-agnostically via `getObjectDetails`, maps `FLAG_NOT_FOUND`/null to
  `Optional.empty()`, and maps the platform `EvaluationContext` (user → targeting key, tenant +
  attributes → context fields) to OpenFeature. The seam through which enterprise providers plug in.
- Phase 10: `platform-flags-inmemory` — the default `FlagProvider`: flags in a `ConcurrentHashMap`
  seeded from `dc.platform.flags.static.*` and mutable at runtime (`set`/`remove`/`snapshot`, driven by
  the `platformflags` endpoint). Zero infrastructure; evaluation is global (context-independent).
- Phase 10: `platform-flags-spi` — the flag provider contract: `FlagProvider`
  (`Optional<FlagValue> evaluate(String, EvaluationContext)`, total — empty for unknown flags) plus the
  `FlagValue` (opaque value + boolean view) and `EvaluationContext` (optional user/tenant + attributes,
  `anonymous()`) value types.
- Phase 10: `platform-flags-api` — the feature-flag capability contract: `FeatureFlags`
  (`boolean enabled(String)`, `<T> T value(String, T default)`) and the `@FeatureGate("flag")` method
  annotation (documented skip-value semantics: `false`/`Optional.empty()`/`null`/no-op by return type).
  Dependency-free.
- Phase 10: `platform-starter-storage-fs` / `platform-starter-storage-s3` — POM-only starters. The fs
  starter bundles the autoconfigure + filesystem provider (zero infra). The s3 starter bundles the
  autoconfigure + S3 provider + AWS SDK v2 S3 client; the S3 path activates once the app supplies a
  configured `S3Client` bean.
- Phase 10: `platform-storage-autoconfigure` — `PlatformStorageAutoConfiguration` (+ per-provider
  `S3ObjectStoreAutoConfiguration` / `FsObjectStoreAutoConfiguration`): an `ObjectStore` chosen by
  classpath (S3 when the AWS SDK and an `S3Client` bean are present, else the filesystem provider —
  same fallback pattern as locking). Decorated with a SHA-256 checksum-on-put (`sha256` user tag,
  default on, spooled through a temp file to stay streaming-safe — D49) and a `dc.platform.storage`
  Observation around every operation. `CapabilityDescriptor` names the provider. Tests: ContextRunner
  matrix, provider selection (mocked `S3Client`), checksum on/off, observation wrapping, end-to-end fs
  slice. `docs/modules/storage.md`.
- Phase 10: `platform-storage-s3` — the S3 `ObjectStore` over AWS SDK v2 `S3Client`: content type and
  user tags map to S3 object metadata; `list` follows continuation tokens. Command-level tests run
  against a mocked `S3Client` (no Docker); a `@Tag("docker")` LocalStack round-trip verifies real wire
  behavior under `-Pdocker`. AWS SDK v2 pinned via an imported BOM (2.28.16).
- Phase 10: `platform-storage-fs` — the default, Docker-free `ObjectStore`: objects are files under a
  configured root, each with a JSON metadata sidecar (Jackson, D48). Path-traversal-guarded by
  `KeyValidator` plus a resolved-path containment check; `put` streams content through a SHA-256 digest
  (the `ObjectRef` etag) and records the actual byte length. Tested against a temp dir (round-trip,
  prefix listing, sidecar-less reads, traversal rejection, IO error paths).
- Phase 10: `platform-storage-spi` — the storage provider-support layer. Deliberately has no provider
  interface (providers contribute an `ObjectStore` bean directly); ships only `KeyValidator`, the
  path-traversal guard (`.`/`..` segments, absolute keys, `\`/NUL) every provider must apply,
  hardened by a traversal-vector test matrix. Decision D47.
- Phase 10: `platform-storage-api` — the storage capability contract: `ObjectStore` (streaming-first
  `put`/`get`/`delete`/`list`, no `byte[]` convenience by design), the value types `ObjectMetadata`,
  `ObjectRef`, `StoredObject` (an `AutoCloseable` metadata+stream pair), `ObjectSummary`, and
  `ObjectStoreException` (`DC-STO-0400` invalid key, `DC-STO-0500` backend I/O). core-api only.
- Phase 9: `platform-starter-idempotency` — POM-only starter: the idempotency autoconfigure + Flyway
  (creates the default `platform_idempotency` table). Default store is JDBC over your `DataSource`; add
  Spring Data Redis to switch to the Redis store. Pair with the errors starter for 409 responses.
- Phase 9: `platform-idempotency-autoconfigure` — `PlatformIdempotencyAutoConfiguration` (+ per-store
  `RedisIdempotencyStoreAutoConfiguration` / `JdbcIdempotencyStoreAutoConfiguration`): an
  `IdempotencyStore` chosen by classpath (Redis `SET NX PX` when a `StringRedisTemplate` is present,
  else a JDBC `platform_idempotency` table with expiry-column TTL and appended Flyway location — same
  patterns as locking, D40/D41). A plain (non-AspectJ, D26) AOP advisor enforces `@Idempotent`:
  evaluates the SpEL `keyExpression`, records the key, and rejects duplicates with the errors
  capability's `ConflictException` (409), guarded by `@ConditionalOnClass`. An optional
  `Idempotency-Key` HTTP filter (off by default, plain `jakarta.servlet` filter) rejects duplicate
  POSTs with 409 (reject-only, no response replay). `CapabilityDescriptor` names the store. Tests:
  ContextRunner matrix, store selection, JDBC store behavior (accept/reject/TTL expiry with a clock),
  SpEL key eval + duplicate rejection, end-to-end `@Idempotent` over H2, and the HTTP filter.
  `docs/modules/idempotency.md`. Decisions D45–D46.
- Phase 9: `platform-idempotency-api` — the idempotency capability contract:
  `@Idempotent(keyExpression, ttl)` and `IdempotencyStore` (`boolean putIfAbsent(String, Duration)`,
  SPI-lite). Dependency-poor (jdk types only).
- Phase 9: `platform-starter-scheduling` — POM-only starter: the scheduling autoconfigure.
  `@EnableScheduling` on a virtual-thread scheduler + `@LockedSchedule`; combine with a locking starter
  for cluster-wide single execution. (Drops the spec's `spring-boot-starter-aop`, absent under Boot 4;
  the plain-advisor proxying needs only transitive `spring-aop` — D44.)
- Phase 9: `platform-scheduling-autoconfigure` — `PlatformSchedulingAutoConfiguration`:
  `@EnableScheduling` on a virtual-thread `SimpleAsyncTaskScheduler` (backs off to a user
  `TaskScheduler`), and the `@LockedSchedule(name, atMost)` annotation. A plain (non-AspectJ) AOP
  advisor — pointcut + `MethodInterceptor` + `@Role(ROLE_INFRASTRUCTURE)` advisor + auto-proxy
  registrar (reusing the D26 pattern, no `aspectjweaver`) — runs the method under
  `LockManager.withLock` when the locking capability is on the classpath, else unlocked with a
  one-time warning. Guarded by `@ConditionalOnClass(LockManager)`. Tests: ContextRunner matrix, the
  no-`LockManager` interceptor path, and a two-context single-execution proof over a shared H2 lock
  table. `docs/modules/scheduling.md`. Decisions D42–D43.
- Phase 9: `platform-starter-locking-jdbc` / `platform-starter-locking-redis` — POM-only starters
  selecting the provider: locking autoconfigure + the JDBC provider + Flyway (creates `platform_lock`),
  or + the Redis provider + `spring-boot-starter-data-redis`. Inject `LockManager` and call `withLock`.
- Phase 9: `platform-locking-autoconfigure` — `PlatformLockingAutoConfiguration` (+ per-provider
  `RedisLockProviderAutoConfiguration` / `JdbcLockProviderAutoConfiguration`): wires a `LockManager`
  over a `LockProvider` chosen by classpath — Redis when a `StringRedisTemplate` bean is present,
  otherwise JDBC when a `DataSource` is present (Redis wins via `@AutoConfigureAfter` ordering; both
  back off to a user `LockManager`/`LockProvider`). A `FlywayConfigurationCustomizer` appends the JDBC
  provider's `db/migration-platform-locking` location (append, not replace). `CapabilityDescriptor`
  names the provider. Tests: ContextRunner matrix, provider selection, `DefaultLockManager` lifecycle/
  exception mapping, the Flyway append, and an end-to-end non-reentrant `withLock` over H2.
  `docs/modules/locking.md`. Decisions D40–D41.
- Phase 9: `platform-locking-redis` — `RedisLockProvider`: `SET NX PX` to acquire, a token-fenced
  compare-and-delete Lua script to release. Command-level unit tests (Mockito) run without Docker; a
  `@Tag("docker")` Testcontainers IT verifies real mutual exclusion and release.
- Phase 9: `platform-locking-jdbc` — `JdbcLockProvider`: a single `platform_lock` table, expiry-column
  auto-expiry (reclaim-if-expired then insert), token-fenced release. Ships its Flyway migration under
  `db/migration-platform-locking`. H2-tested (acquire/skip, reclaim expired, token fencing, idempotent
  release); no Docker.
- Phase 9: `platform-locking-spi` — `LockProvider` (`Optional<LockHandle> tryAcquire(String, Duration)`)
  and `LockHandle` (auto-closeable, idempotent, non-throwing release): the pluggable provider contract.
- Phase 9: `platform-locking-api` — the locking capability contract: `LockManager`
  (`<T> Optional<T> withLock(String, Duration, Callable<T>)`, non-reentrant, auto-expiring,
  non-blocking) and `LockException` (`DC-LOCK-0500`, infra failures only).
- Phase 9: `platform-starter-resilience` — POM-only starter: the resilience autoconfigure plus
  `resilience4j-spring-boot4` (retry/circuit-breaker/time-limiter registries, annotation aspects, AOP,
  Micrometer binding). Add it and use `@Retry`/`@CircuitBreaker`/`@TimeLimiter` or `RetryableOperation`.
- Phase 9: `platform-resilience-autoconfigure` — `PlatformResilienceAutoConfiguration` +
  `PlatformResilienceEnvironmentPostProcessor`: contributes the platform's default Resilience4j
  instance tuning (retry 3/exp, breaker 50% over a 10-call window, time limiter 5s) as
  lowest-precedence `resilience4j.*` environment defaults so `application.yml` always wins, and a
  `RetryableOperation` bean over the retry registry (`@ConditionalOnBean(RetryRegistry)`, ordered
  `afterName` Resilience4j's `RetryAutoConfiguration`). Micrometer binding is left to
  `resilience4j-spring-boot4` to avoid double meter registration. `CapabilityDescriptor` reports the
  capability active. Tests: ContextRunner matrix, EnvironmentPostProcessor defaults/override,
  fail-then-succeed retry behavior, and a default-config circuit-breaker-opens behavior test.
  `docs/modules/resilience.md`. Decisions D37–D39.
- Phase 9: `platform-resilience-api` — the resilience capability contract: `ResilienceDefaults`
  (the platform's default retry/circuit-breaker/time-limiter tuning as constants — 3 attempts with
  exponential backoff, breaker at 50% over a 10-call window, 5s time limit) and `RetryableOperation`,
  a programmatic retry helper (`<T> T call(String name, Supplier<T>)`) for call sites that cannot use
  Resilience4j's declarative annotations. No wrapper annotations over Resilience4j (things-to-avoid
  #25). Pins `io.github.resilience4j:resilience4j-bom` 2.4.0 (first line with a Spring-Boot-4 module).
- Phase 8: `platform-starter-redis` — POM-only starter: the redis autoconfigure plus
  `spring-boot-starter-data-redis` (Lettuce). Completes the phase 8 redis slice.
- Phase 8: `platform-redis-autoconfigure` — `PlatformRedisAutoConfiguration`: direct-Redis client
  conventions (distinct from the cache capability). A `BeanPostProcessor` installs a prefixing key
  serializer on every `StringRedisTemplate`, namespacing keys with `dc.platform.redis.key-prefix`
  (default `<spring.application.name>:`) so services sharing a Redis don't collide; connection tuning
  stays on Boot's `spring.data.redis.*`. `CapabilityDescriptor` reports the prefix. Tests: serializer
  and post-processor units, ContextRunner matrix (prefix resolution), and a `@Tag("docker")`
  wire-level prefix round-trip IT. `docs/modules/redis.md`.
- Phase 8: `platform-starter-cache-caffeine` / `platform-starter-cache-redis` — POM-only starters
  selecting the provider: cache autoconfigure + `spring-boot-starter-cache` + (Caffeine) or
  (`spring-boot-starter-data-redis` + `jackson-databind` for JSON cache values). Add one and use
  `@Cacheable`. Completes the phase 8 cache slice.
- Phase 8: `platform-cache-autoconfigure` — `PlatformCacheAutoConfiguration`: a default
  `CacheKeyConvention` (app-name-prefixed), per-cache TTL/size from `dc.platform.cache.caches.*`, a
  `CaffeineCacheManager` when Caffeine is on the classpath, and a `RedisCacheManager` (String keys,
  JSON values) when Spring Data Redis is present (Redis wins when both are, via mutually exclusive
  class conditions). Ordered `before` Boot's `CacheAutoConfiguration` so the platform per-cache
  policy wins; both back off to any user `CacheManager`/`CacheKeyConvention`. Cache metrics come from
  Boot's own binder over the platform `CacheManager`. Tests: ContextRunner matrix (Redis filtered so
  the Caffeine default is exercised), a Caffeine TTL/size behavior test, key-convention units, and a
  `@Tag("docker")` Redis round-trip IT. `docs/modules/cache.md`.
- Phase 8: `platform-cache-api` — the cache capability contract: `CacheKeyConvention`
  (`key(cacheName, parts...)`, default composes `<appName>:<cacheName>[:<part>]*` so services don't
  collide in a shared backend) and `CacheNames` (dot-separated cache-name conventions with a
  validating `of(...)`). No custom cache annotation — the model stays Spring's `@Cacheable`.
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
