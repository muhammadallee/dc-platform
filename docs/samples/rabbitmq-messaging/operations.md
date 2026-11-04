# Operations

## Posture

| Situation | Behaviour | Verified |
|---|---|---|
| Broker down | Outbox requests unaffected, direct publish fails, consumers reconnect by themselves | CONSUMER |
| Broker down at startup | Service starts and is ready; subscribes when it arrives | CONSUMER |
| Broker slow | Sends bounded by `confirm-timeout`; a late one may repeat under the same id | CONSUMER (through the outbox) |
| Broker lost its definitions | Redeclared on reconnect | CONTAINER |
| Handler keeps failing | `max-attempts`, then the DLQ. It holds the consumer meanwhile, and up to `prefetch` messages wait | CONSUMER |
| Pod stops | In-flight finishes, buffered go back | CONSUMER |
| Pod killed | Broker redelivers; `delivery-limit` catches a message that keeps doing this | CONTAINER (stand-in) |

## What to watch

| Signal | Alert when | Means |
|---|---|---|
| `platform_messaging_handled_total{outcome="dlq"}` | rising | messages are being dead-lettered: inspect the DLQ |
| `….dlq` queue depth (RabbitMQ) | above 0 | waiting for you; nothing replays them automatically |
| `platform_messaging_handled_total{outcome="retry"}` | rising fast | handlers are failing, possibly a dependency |
| `platform_messaging_handled_total{outcome="ignored"}` | unexpected | events nobody here handles arrive on this destination |
| `platform_messaging_published_total{outcome="failure"}` | rising | direct publishes are failing |
| Main queue depth | growing | consumers are behind |

## Dead-letter replay

1. `inspect` shows headers, including `x-death` and `eventId`, not payloads. The log has the reason.
2. Fix the cause: deploy the fix, or upgrade the service for a newer version.
3. `replay` moves what is there now, at-least-once, keeping ids. Don't delete the main queue meanwhile (HI-0088).

Needs the `rabbitmq_shovel` and `rabbitmq_shovel_management` plugins on the broker.

## Sizing

| Setting | Default | Consider |
|---|---|---|
| `handler.retry.max-attempts` | 3 | More attempts hold the consumer for longer |
| `rabbit.prefetch` | 10 | Lower with slow handlers: fewer messages wait behind a retry, and fewer return on a stop |
| `rabbit.shutdown-timeout` | 30 s | Above your slowest handler |
| Pod grace period | | **Above about 95 s with the defaults:** phases stop in turn (web 30 s, outbox 30 s and its joins, consumers 30 s) |
| `rabbit.delivery-limit` | 20 | Well above how many stops a message can see |

## Kill switch and replacement

`dc.platform.messaging.enabled=false` removes the capability. Define your own `EventPublisher`, `EventSerializer`, `EventHandlerRegistrar`
or `EventTransport` to replace them.

## Health

The broker does not affect readiness or liveness, since requests keep working and events wait. Boot's rabbit indicator stays in `dependencies`.

## Migrating existing queues

RabbitMQ does not convert queues. A queue that already exists as classic fails its subscription at startup, naming the mismatch: set
`quorum-queues=false`, or drain and delete it.
