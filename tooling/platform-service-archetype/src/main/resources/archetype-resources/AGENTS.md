# CLAUDE.md

This service is built on the **DC Platform** chassis (parent: `platform-service-parent`; the service
name is the `artifactId` in `pom.xml`). The platform provides cross-cutting behavior via starters +
auto-configuration. Your job is business logic; DO NOT re-implement what the platform already does.

## Non-negotiable rules

1. **Before adding ANY infrastructure dependency or cross-cutting code, check for a platform
   starter first.** Capability table: https://platform.dubaicustoms.gov.ae/docs/modules/ (or run
   `mvn dependency:tree` and look at existing `platform-starter-*`). If a starter exists, use it.
2. **Messaging:** publish via `ae.gov.dubaicustoms.platform.messaging.EventPublisher` and consume via
   `@EventHandler`. NEVER inject `RabbitTemplate` or write listener containers
   directly — retry, DLQ, correlation, serialization and metrics are already handled.
3. **Errors:** throw Spring's `ErrorResponseException` (or `ResponseStatusException`) and put this
   service's error code on its body: `problem.setProperty("code", "ORD-0001")`. NEVER write your own
   `@RestControllerAdvice` — the platform maps every exception to RFC 9457 and adds `traceId`,
   `timestamp` and `retryable`. To add a field to every error body, register a Spring
   `ErrorResponse.Interceptor` through `WebMvcConfigurer.addErrorResponseInterceptors`.
4. **Security:** the platform does not secure endpoints yet (basic auth arrives with the REST API
   capability). Do not add your own security scheme in the meantime; keep the service inside the
   cluster.
5. **Outbound HTTP:** the platform REST client arrives in the next release. Until then use Spring's
   `RestClient.Builder` bean, and set connect and read timeouts on every client.
6. **Config:** platform behavior is tuned ONLY via `dc.platform.*` properties (IDE-completed).
   Never copy platform defaults into application.yml "to be safe" — set a key only to deviate.
7. **Secrets:** read them from configuration properties (mounted secrets); NEVER `System.getenv`
   for secrets and never commit values.
8. **Persistence:** use platform JPA conventions (snake_case naming, auditing, Flyway under
   `db/migration`). `open-in-view` stays false.
9. **Anything else** (caching, scheduling, retries): the platform does not cover it yet; use Spring's
   own support and keep it simple.
10. **Logging:** SLF4J, parameterized, no secrets/PII. Trace IDs (the correlation id) are automatic — never log
    them manually or generate your own.

## Testing rules

- Use platform slices: `@PlatformWebTest` (MockMvc + errors),
  `@PlatformMessagingTest` (+ `TestEventTransport`), `@PlatformDataTest` (H2 + conventions),
  `@PlatformTest` (full boot). Real-HTTP tests extend `HttpIntegrationTestSupport`.
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
