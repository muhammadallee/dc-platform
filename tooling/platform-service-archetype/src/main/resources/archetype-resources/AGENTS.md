# CLAUDE.md

This service is built on the **DC Platform** chassis (parent: `platform-service-parent`; the service
name is the `artifactId` in `pom.xml`). The platform provides cross-cutting behavior via starters +
auto-configuration. Your job is business logic; DO NOT re-implement what the platform already does.

## Non-negotiable rules

1. **Before adding ANY infrastructure dependency or cross-cutting code, check for a platform
   starter first.** Capability table: https://platform.dubaicustoms.gov.ae/docs/modules/ (or run
   `mvn dependency:tree` and look at existing `platform-starter-*`). If a starter exists, use it.
2. **Messaging:** publish via `ae.gov.dubaicustoms.platform.messaging.EventPublisher` and consume via
   `@EventHandler`. NEVER inject `KafkaTemplate` / `RabbitTemplate` or write listener containers
   directly — retry, DLQ, correlation, serialization and metrics are already handled.
3. **Errors:** throw `PlatformException` subtypes (`BusinessException`, `NotFoundException`,
   `ConflictException`) with an `ErrorCode` (`DC-XX-NNNN`, where `XX` is this service's claimed
   namespace). NEVER write your own `@RestControllerAdvice` or build `ProblemDetail` by hand — the
   platform maps everything to RFC 9457 with correlation IDs.
4. **Security:** endpoints are authenticated by default. Use `@RequiresPermission("res:action")`
   for authorization and `CurrentUserAccessor` for identity. Do not define your own
   `SecurityFilterChain`; use `SecurityCustomizer` beans for adjustments.
5. **Outbound HTTP:** build clients from `PlatformRestClientFactory.builder("client-name")` —
   correlation, auth relay, timeouts, metrics and error mapping are pre-wired. No raw
   `RestTemplate`/`WebClient` unless a platform gap is documented in an issue.
6. **Config:** platform behavior is tuned ONLY via `dc.platform.*` properties (IDE-completed).
   Never copy platform defaults into application.yml "to be safe" — set a key only to deviate.
7. **Secrets:** reference via the platform secrets property source; NEVER `System.getenv` for
   secrets and never commit values.
8. **Persistence:** use platform JPA conventions (snake_case naming, auditing, Flyway under
   `db/migration`). `open-in-view` stays false.
9. **Resilience/scheduling/locking/idempotency/caching/flags/audit:** use the platform
   annotations and APIs (`@Retry`-style r4j config, `@LockedSchedule`, `@Idempotent`,
   `@Cacheable` + platform key convention, `FeatureFlags`, `@Audited`) — do not hand-roll.
10. **Logging:** SLF4J, parameterized, no secrets/PII. Correlation IDs are automatic — never log
    them manually or generate your own.

## Testing rules

- Use platform slices: `@PlatformWebTest` (MockMvc + errors + security mocks),
  `@PlatformMessagingTest` (+ `TestEventTransport`), `@PlatformDataTest` (H2 + conventions),
  `@PlatformTest` (full boot). JWTs via `TestTokens.user("alice").roles(...)` for MockMvc; for real HTTP
  tests, `TestJwtIssuer` (loopback JWKS) so the token is actually validated (see `HttpIntegrationTestSupport`).
- Default tests must run WITHOUT Docker. Real-infra tests: `@Tag("docker")`, run with `mvn -Pdocker verify`.
- Assert error responses with `assertThatProblem(...)` including the error code.
- `PlatformConformanceTest` runs the platform's consumer conformance rules over your code — keep it.

## Debugging platform behavior

`--debug` flag → condition report ·  `GET /actuator/platform` → active capabilities/providers ·
`/actuator/env` → property sources named `platform-*-defaults` (anything above them overrides).
Disable a capability: `dc.platform.<cap>.enabled=false`.

## Upgrading the platform

Bump the parent version, then:
`mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check -Dplatform.target=<version>`
Fix every reported deprecation. Read https://platform.dubaicustoms.gov.ae/docs/upgrade/<version>.

## Reference

Docs: https://platform.dubaicustoms.gov.ae/docs · Property reference:
https://platform.dubaicustoms.gov.ae/docs/reference/properties · Error codes:
https://platform.dubaicustoms.gov.ae/docs/reference/error-codes · Claim this service's code
namespace (`DC-XX-…`) in the platform error-code registry.
