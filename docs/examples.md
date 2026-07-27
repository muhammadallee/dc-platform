# Examples

Four runnable services under [`examples/`](https://github.com) build inside the platform reactor and
consume the platform exactly as a real service does — through `platform-service-parent` and the
`platform-starter-*` dependencies. They are never published (`maven.deploy.skip`) and never part of
the [BOM](reference/bom.md); their `example-` artifactId prefix marks them as demonstrations to the
dependency-constitution enforcer.

Run any of them from its module directory with `mvn spring-boot:run`, or boot-and-probe the whole
stack with `tooling/scripts/golden-path.sh` (archetype) and `tooling/scripts/smoke-matrix.sh`
(golden-path across capability combinations).

## example-minimal — the floor

`platform-starter-core` + `-errors` + `-logging`, and nothing else. It exists to prove the smallest
thing the platform guarantees:

- every request carries a correlation ID and structured JSON logs, with no code in the service doing
  it;
- a thrown [`NotFoundException`](modules/errors.md) renders as an RFC-9457
  `application/problem+json` 404 with a stable [error code](reference/error-codes.md) — there is
  deliberately no `@RestControllerAdvice` anywhere in the service.

`GET /ping` returns `{"status":"ok"}`; `GET /widgets/{id}` always throws, so the problem-response
path is always exercised. See [`ProblemResponseTest`](modules/errors.md) for the assertion.

## example-golden-path — the canonical reference

The service to read first. A small **orders** domain (`POST /orders`, `GET /orders/{id}`) wired through
the full golden-path stack: REST + [validation](modules/validation.md) + [security](modules/security.md)
(authenticated by default) + [OpenAPI](modules/openapi.md) + [observability](modules/observability.md)
+ [JPA/H2](modules/data.md) + [messaging](modules/messaging.md) (in-memory locally) +
[cache](modules/cache.md) + [resilience](modules/resilience.md) + [audit](modules/audit.md). Every
cross-cutting concern comes from a `platform-starter-*`; `OrderService` is a tour of the APIs a service
actually calls (`EventPublisher`, `RetryableOperation`, `@Audited`, `@Cacheable`, `@Transactional`),
and the class deliberately contains no error, security, or serialization code.

Run it with `mvn -pl examples/example-golden-path spring-boot:run`; build the prod-sim variant against
PostgreSQL with `-Ppg` (Kafka messaging is deferred until the infrastructure supports it — the
in-memory transport is the local default, and `example-event-driven` demonstrates a real broker over
RabbitMQ).

Tests, all through the platform slices and Docker-free:

- `OrderFlowTest` (`@PlatformWebTest` + `@AutoConfigureTestTransport`) — authenticated `POST /orders`
  persists, publishes `OrderPlaced` (asserted on `TestEventTransport`), and is read back;
- `OrderProblemResponseTest` — unknown order → 404 problem with code, invalid body → 400 problem;
- `PlatformSurfaceTest` — OpenAPI document served, `/actuator/platform` reports capabilities;
- `StartupBudgetTest` — cold-start wall-clock stays within the checked-in baseline + 15% (deliverable C).

## example-extension-provider — the extension model

A service that adds a **custom storage provider** without editing the platform. `EncryptingFsObjectStore`
implements [`ObjectStore`](modules/storage.md), encrypting content at rest (AES-CTR, which is
length-preserving so sizes and metadata still round-trip) and delegating persistence to the platform's
`FsObjectStore`. Its `EncryptingStorageAutoConfiguration` is ordered **before** the platform's
`FsObjectStoreAutoConfiguration`, so the platform default — guarded by
`@ConditionalOnMissingBean(ObjectStore.class)` — backs off. This is the [extension model](concepts/extension-model.md)
verbatim: extend by registering ahead of the default, never by forking the platform.

Two tests are the acceptance:

- `EncryptingFsObjectStoreTckTest extends ObjectStoreTck` — the provider is *platform-certified* iff
  the whole storage TCK passes against it;
- `StorageBackOffTest` — boots the app and asserts the single `ObjectStore` bean is the custom
  encrypting provider, proving the default stepped aside.

## example-event-driven — producer/consumer over messaging

Two services — `example-event-driven-producer` and `example-event-driven-consumer` — that communicate
only through platform [messaging](modules/messaging.md). The producer publishes `ShipmentRequested`
via `EventPublisher`; the consumer receives it with `@EventHandler`. Each service owns its own copy of
the event record, as separate deployables do; the platform matches them by JSON shape and the
`eventType` header.

The **retry/DLQ** behaviour is the headline. `ShipmentHandler` throws for a shipment whose id is
`poison`; `ShipmentRetryDlqTest` delivers one and asserts the platform made three attempts and then
republished the message to the DLQ destination (`dc.shipments.dlq`) — all Docker-free over the
in-memory `TestEventTransport`. `ShipmentRoundTripTest` covers the happy path.

The default transport is in-memory (the `local` Maven profile). Run against real **RabbitMQ** with the
`rabbit` profile after `docker compose up rabbitmq`:

```
mvn -Prabbit -pl examples/example-event-driven/producer spring-boot:run
mvn -Prabbit -pl examples/example-event-driven/consumer spring-boot:run
```

Kafka is deferred until the infrastructure supports it; the messaging API is transport-agnostic, so
adding it later is a starter swap, not a code change.
