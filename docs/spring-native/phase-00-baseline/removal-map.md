# Removal map

Plan v1 §6 step 4; phases updated to plan v2 (2026-11-19).
- **Source:** the public types in every `*-api` module, listed from `src/main/java` (excluding `package-info.java`) on
  2026-11-18 at `bc58c4a`, whose code is identical to `bdf2845`. The listing is
  [`evidence/api-types.txt`](evidence/api-types.txt). The plan's table matched the code; nothing was missing.
- **Replacements** are as verified by the spikes ([`spikes.md`](spikes.md)) where a spike covers them, and are otherwise
  proposals for the phase's Gate A.

| Module | Types (count) | Spring replacement | Spike | Phase |
|---|---|---|---|---|
| `platform-core-api` | `CorrelationId`, `RequestContext`, `ErrorCode`, `PlatformException`, `CapabilityDescriptor`, `PlatformApi`, `PlatformInternal` (7) | Micrometer `Tracer`; `RequestContextHolder`; the error code as a ProblemDetail property. `PlatformApi` and `PlatformInternal` may move into the autoconfigure module as internal markers for the japicmp/ArchUnit gates | S7 | 2 |
| `platform-errors-api` | `BusinessException`, `NotFoundException`, `ConflictException`, `HttpStatusHint`, `ProblemDetailCustomizer` (5) | `ErrorResponseException` (and service subclasses), `ResponseStatusException`, `@ResponseStatus`; a service's own `@RestControllerAdvice` | S7 | 2 |
| `platform-data-api` | `EntityId`, `Money`, `PersistenceConventions` (3) | none (ND7) | — | 2 |
| `platform-logging-api` | `Kv`, `LogSanitizer` (2) | SLF4J fluent `addKeyValue`; masking via a structured-logging members customizer or the platform's Logback layout | S5 | 3 |
| `platform-messaging-api` | `EventPublisher`, `OutboxPublisher`, `EventHandler`, `EventType`, `EventEnvelope`, `EventHeaders`, `EventPublishException`, `EventSerializationException` (8) | `RabbitTemplate`, `@RabbitListener`, Spring AMQP `Message` and headers; the outbox per ND5 | S1 (outbox) | 6 |
| `platform-restclient-api` | `PlatformRestClientFactory`, `PlatformRestClientCustomizer`, `RemoteCallException` (3) | `RestClient.Builder`, `RestClientCustomizer`, `@HttpExchange` groups (`spring.http.serviceclient.<group>.*`), `RestClientResponseException` / `ResourceAccessException` | S3 | 9 |
| `platform-resilience-api` | `RetryableOperation`, `ResilienceDefaults` (2) | Resilience4j annotations and registries, or Spring `@Retryable` / `@ConcurrencyLimit` enabled by platform auto-configuration | S4 | 9 |
| `platform-test-api` | `PlatformUsageRules`, `PlatformAssertions`, `ProblemDetailAssert`, `Containers`, `DockerAvailable`, `PlatformDataTest`, `PlatformMessagingTest`, `PlatformTest`, `PlatformWebTest`, `TestJwtIssuer`, `TestTokens` (11) | ND6: keep what Boot's test slices and Testcontainers do not give. `TestJwtIssuer` and `TestTokens` stay only if Phase 9 uses them as the token endpoint (ND11b) | — | 2–9 |

**Removed whole in Phase 1 (cut, ND2), with no replacement in the platform:**
- `platform-events-api` (3 types): services use `ApplicationEventPublisher`, `@EventListener`, `@TransactionalEventListener`;
- `platform-security-api` (3), `platform-security-authz-api` (1): replaced by basic auth in Phase 8 (ND4);
- `platform-cache-api` (2), `platform-locking-api` (2): cut (ND9);
- `platform-validation-api` (8): cut; Spring's Bean Validation stays and errors maps its failures.

**SPI modules:** `platform-messaging-spi` goes in Phase 6 (one provider, ND15); `platform-locking-spi` and
`platform-security-authz-spi` go in Phase 1.

**Module merges (ND15):** each capability ends with an autoconfigure module and a POM-only starter. The artifacts that
disappear in a merge (for example `platform-resilience-autoconfigure` into the REST client's) are added to
[`../expected-removals.md`](../expected-removals.md) by the phase that merges them.
