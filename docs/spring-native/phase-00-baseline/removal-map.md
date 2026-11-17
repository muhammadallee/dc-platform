# Removal map

Plan §6 step 4.
- **Source:** the public types in every kept `*-api` module, listed from `src/main/java` (excluding `package-info.java`) on
  2026-11-17 at `bdf2845`. The plan's table matched the code; nothing was missing.
- **Replacements** are as verified by the spikes ([`spikes.md`](spikes.md)) where a spike covers them, and are otherwise
  proposals for the phase's Gate A.

| Module | Types (count) | Spring replacement | Spike | Phase |
|---|---|---|---|---|
| `platform-core-api` | `CorrelationId`, `RequestContext`, `ErrorCode`, `PlatformException`, `CapabilityDescriptor`, `PlatformApi`, `PlatformInternal` (7) | Micrometer `Tracer`; `RequestContextHolder`; the error code as a ProblemDetail property. `PlatformApi` and `PlatformInternal` may stay as internal markers for the japicmp/ArchUnit gates | S7 | 2 |
| `platform-errors-api` | `BusinessException`, `NotFoundException`, `ConflictException`, `HttpStatusHint`, `ProblemDetailCustomizer` (5) | `ErrorResponseException` (and service subclasses), `ResponseStatusException`, `@ResponseStatus`; a service's own `@RestControllerAdvice` | S7 | 2 |
| `platform-events-api` | `DomainEvent`, `DomainEventPublisher`, `DomainEventHandler` (3) | `ApplicationEventPublisher`, `@EventListener`, `@TransactionalEventListener` | none needed | 2 |
| `platform-data-api` | `EntityId`, `Money`, `PersistenceConventions` (3) | none (ND7) | — | 2 |
| `platform-logging-api` | `Kv`, `LogSanitizer` (2) | SLF4J fluent `addKeyValue`; masking via a structured-logging members customizer or the platform's Logback layout | S5 | 3 |
| `platform-restclient-api` | `PlatformRestClientFactory`, `PlatformRestClientCustomizer`, `RemoteCallException` (3) | `RestClient.Builder`, `RestClientCustomizer`, `@HttpExchange` groups (`spring.http.serviceclient.<group>.*`), `RestClientResponseException` / `ResourceAccessException` | S3 | 4 |
| `platform-resilience-api` | `RetryableOperation`, `ResilienceDefaults` (2) | Resilience4j annotations and registries, or Spring `@Retryable` / `@ConcurrencyLimit` enabled by platform auto-configuration | S4 | 4 |
| `platform-security-api` | `CurrentUser`, `CurrentUserAccessor`, `SecurityCustomizer` (3) | `@AuthenticationPrincipal Jwt`, `SecurityContextHolder`, a `Customizer<HttpSecurity>` bean | S2 | 5 |
| `platform-security-authz-api` | `RequiresPermission` (1) | `@PreAuthorize` (ND4) | S6 | 5 |
| `platform-messaging-api` | `EventPublisher`, `OutboxPublisher`, `EventHandler`, `EventType`, `EventEnvelope`, `EventHeaders`, `EventPublishException`, `EventSerializationException` (8) | `RabbitTemplate`, `@RabbitListener`, Spring AMQP `Message` and headers; the outbox per ND5 | S1 (outbox) | 7 |
| `platform-cache-api` | `CacheKeyConvention`, `CacheNames` (2) | `@Cacheable` with a `KeyGenerator` bean | — | 8 |
| `platform-locking-api` | `LockManager`, `LockException` (2) | ND9 | — | 9 |
| `platform-validation-api` | `NotBlankTrimmed`, `SafeText`, `Ulid`, `FutureInstant` and their four validators (8) | **kept**: custom Jakarta Bean Validation constraints are the standard mechanism | — | — |
| `platform-test-api` | `PlatformUsageRules`, `PlatformAssertions`, `ProblemDetailAssert`, `Containers`, `DockerAvailable`, `PlatformDataTest`, `PlatformMessagingTest`, `PlatformTest`, `PlatformWebTest`, `TestJwtIssuer`, `TestTokens` (11) | ND6: keep what Boot's test slices and Testcontainers do not give | — | 2–10 |

SPI modules (`platform-messaging-spi`, `platform-locking-spi`, `platform-security-authz-spi`) are reviewed in the phase that
rewrites their capability: an SPI stays only where a provider SPI still makes sense (ND3).
