# Phase 12 — Test Kit & TCKs (P1 — begin after phase 7; size M, no Docker)

## Modules
`test/platform-test-api`, `test/platform-starter-test`, `test/tck/platform-tck-{messaging,storage,locking,flags,secrets,ratelimit}`.

## platform-test-api
- `@PlatformTest` — composed `@SpringBootTest` with platform test defaults (console logging, security
  mock-friendly, inmemory transports) via `@AutoConfigure…` composition; document each default in javadoc.
- Slices: `@PlatformMessagingTest` (messaging autoconfig + TestEventTransport only),
  `@PlatformDataTest` (H2 + platform JPA conventions), `@PlatformWebTest` (MockMvc + errors + validation + security mocks).
- `Containers` factory class: lazily-started singleton Testcontainers (kafka(), redis(), postgres(),
  vault(), localstack()) with `@ServiceConnection` helpers — used ONLY by `@Tag("docker")` tests;
  `DockerAvailable.check()` utility (assumption guard) lives here.
- JWT test helpers: `TestTokens.user("alice").roles("ADMIN").jwt()` post-processor sugar.
- AssertJ extensions: ProblemDetail assertions (`assertThatProblem(response).hasCode("ACME-ORD-0001")`).

## platform-starter-test — POM: test-api + spring-boot-starter-test + messaging-test + json-path etc. (test scope guidance comment).

## TCK pattern (identical per capability)
Abstract JUnit5 class, e.g.:
```java
/** Contract test for EventTransport providers. Extend, provide the transport, inherit all tests.
 *  A provider is platform-certified iff this class passes. */
public abstract class EventTransportTck {
    protected abstract EventTransport transport();
    @Test void roundTripsPayloadAndHeaders() { … }
    @Test void keyOrderingWithinPartitionOrQueue() { … }   // relaxed/skippable via override, documented
    @Test void redeliversOnListenerFailure() { … }
    @Test void concurrentPublishersAreSafe() { … }
    // 8–15 tests per TCK; enumerate the invariant list in the class javadoc
}
```
Immediately apply each TCK to existing providers: inmemory (docker-free proof) + kafka/rabbit/redis/s3/vault
under `@Tag("docker")`. TCK jars are published (Test Support category) so enterprise providers self-certify.

Acceptance: root verify green docker-free (all inmemory/fs/env/jdbc providers pass their TCKs locally);
`-Pdocker` full TCK matrix green; docs page testing.md (how to use slices, fixtures, TCKs); BOM; CHANGELOG.
