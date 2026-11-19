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
the 1.0.0 stack: REST + Spring validation + [observability](modules/observability.md)
+ [JPA/H2](modules/data.md) + [messaging](modules/messaging.md) (in-memory locally). It has no web
security: in 1.0.0 the API gateway authenticates callers. Every
cross-cutting concern comes from a `platform-starter-*`; `OrderService` is a tour of the APIs a service
actually calls (`OutboxPublisher`, `@Transactional`, `NotFoundException`),
and the class deliberately contains no error, security, or serialization code.

Run it with `mvn -pl examples/example-golden-path spring-boot:run`. Messaging is in-memory here;
`example-event-driven` shows a real broker over RabbitMQ.

Tests, all through the platform slices and Docker-free:

- `OrderFlowTest` (`@PlatformWebTest` + `@AutoConfigureTestTransport`) — `POST /orders`
  persists, publishes `OrderPlaced` (asserted on `TestEventTransport`), and is read back;
- `OrderProblemResponseTest` — unknown order → 404 problem with code, invalid body → 400 problem;
- `PlatformSurfaceTest` — `/actuator/platform` reports capabilities;
- `StartupBudgetTest` — cold-start wall-clock stays within the checked-in baseline + 15% (deliverable C).

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

Both services run on **RabbitMQ**, the only transport (the tests need no broker). Start it, then the two services:

```
docker compose -f docker-compose.local.yml up -d rabbitmq
mvn -pl examples/example-event-driven/producer spring-boot:run
mvn -pl examples/example-event-driven/consumer spring-boot:run
```
