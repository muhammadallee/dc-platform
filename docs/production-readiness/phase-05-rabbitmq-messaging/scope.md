# Phase 5 — RabbitMQ Messaging: scope

| Item | Value |
|---|---|
| Phase start commit | `dfb3524` (Phase 4 closed on `03dee13`; its tag waits on push and CI) |
| Capability under test | `platform-messaging-rabbit`, `platform-messaging-autoconfigure`, `platform-starter-messaging-rabbit`, `EventTransport` SPI |
| Owner decisions | HD3, HD13: Oracle, prod 19c, certified on 23ai Free. HC7: no platform users, so breaking changes need only a CHANGELOG entry |
| Certified dependencies | core, errors, logging, observability, data. Outbox PASS with HI-0071, HI-0079, HI-0086 parked |
| Fixtures (not certified here) | security, openapi, validation. Kafka: §3.9 compile check only |
| Phase 0 gaps | G5-03 to G5-07 (G5-01, G5-02 closed in Phase 4). Carried: HI-0080, HI-0082 |

## 1. Baseline defects (reproduced on `dfb3524`, real RabbitMQ)

`RabbitHandlerFailureIT` with `max-attempts=3`: **`attempts=9 dlq=1 queue=0`** (`evidence-raw/baseline-handler-failure.log`).

The registrar tries 3 times, then republishes to `destination.dlq`. Since Phase 4 made sends `mandatory`, that republish is
unroutable (`no queue is bound for dc.failure:order.dlq`) and throws. Rabbit's own retry interceptor then repeats the whole
thing 3 times, and only then does the broker dead-letter it. So there are two retry layers, 3× the configured attempts, and a
platform DLQ destination that does not exist.

By reading (each gets a test at Gate B):

| Defect | Where |
|---|---|
| A payload that cannot be deserialized is retried, though it can never succeed | `EventHandlerRegistrar.handle` |
| A stop during a backoff sleep carries on, and could dead-letter a healthy message | `sleepBackoff` |
| Broker down at startup fails the app, although the outbox exists to absorb that: `subscribe` declares topology synchronously | `RabbitEventTransport.subscribe` (to confirm) |
| Topology declared once: a broker that lost its definitions is not redeclared | `declareTopology` |
| Containers close in `destroy`, with no order against the pool a handler uses | `EventHandlerRegistrar.destroy` |
| The fallback `new ObjectMapper()` fails on unknown fields | `platformEventSerializer` |
| No prefetch or shutdown timeout set; the consumer carries no trace | `subscribe`, `handle` |
| Direct publish accepts credential-named headers, which the outbox refuses | `DefaultEventPublisher` |

## 2. Requirements

### R1 Topology (G5-03)
1. Quorum queues by **default**, for main and dead-letter queues; classic stays available through `quorum-queues=false`.
2. Durable exchanges, a DLX per exchange, a DLQ per queue. Naming stays `<exchange>.<routingKey>.<group>` and `….dlq`.
3. `x-delivery-limit` (default 5) on quorum queues, so a message that keeps killing its consumer reaches the DLQ. In-process
   retries cannot count those.
4. Topology is declared on every new connection, so startup does not need the broker, and a broker that lost its definitions
   gets them back.

### R2 One retry layer, one dead-letter path (G5-04)
1. The registrar is the only retry layer. Rabbit's retry interceptor goes. `max-attempts` means what it says.
2. Backoff is exponential with a cap (`backoff`, `max-backoff`). Total time per message is bounded, and the bound is stated.
3. Exhausted on a transport with native dead-lettering: the listener throws, the broker dead-letters. New SPI method
   `EventTransport.deadLettersNatively()`, default `false`; other transports keep the republish.
4. Non-retryable, straight to dead letter on the first attempt: a payload that cannot be deserialized, and a version newer than
   the handler knows (R5).
5. A stop interrupts a backoff: the message is **requeued**, never dead-lettered.

### R3 Consumer
1. Container-managed acks (`AUTO`), documented: ack after the handler returns, including its transaction.
2. Prefetch default 10, configurable, so a blocked retry delays a bounded number of buffered messages.
3. Graceful stop: in-flight finishes within `shutdown-timeout` (default 30 s), buffered messages go back without a redelivery
   storm, subscriptions close before the pool. The registrar becomes `SmartLifecycle`.
4. Reconnects by itself after a broker outage.

### R4 Structured headers and context (G5-05, G5-07)
1. Every message carries `eventId`, `eventType`, `eventVersion`, `producer` (`spring.application.name`), `traceparent`, `tracestate`
   when present, on both paths.
2. `DefaultEventPublisher` sends `traceparent` and drops the `correlationId` header (Phase 1 made correlation the trace id).
   `correlation.propagate` is removed. Breaking under HC7.
3. The handler runs in a span continuing the message's trace, so its logs and its own publishes stay on that trace.
4. Inbound headers stay strings; `x-death` becomes `x-death-count`, Spring's internal `spring_*` headers are dropped.
5. A credential-named header is refused on direct publish too, as in the outbox.

### R5 Schema compatibility
1. Unknown fields are tolerated, whichever `ObjectMapper` is used.
2. `eventVersion` is checked against the payload's `@EventType` version: same or older is delivered, newer goes to the DLQ
   without retries (a replay after upgrading delivers it), with a WARN and a metric.

### R6 Publishing posture
Any publish tied to a database change goes through `OutboxPublisher`; `EventPublisher` is documented as non-guaranteed. Rabbit
confirms and `mandatory` are in place since Phase 4 and stay.

### R7 DLQ replay (G5-06)
A documented procedure and a script, `tooling/scripts/rabbit-dlq-replay.sh`, using the management API: a dynamic shovel from
`….dlq` back to the main queue, deleting itself after the current length. At-least-once; events keep their ids. Inspection
through the management API, and a metric for dead-lettered events.

### R8 Consumer deduplication (HD2)
The consumer's responsibility. The platform guarantees the id. The sample shows the pattern: insert the id in a processed-events
table with a unique constraint, in the same transaction as the business write, skipping on violation. Demonstrated, not certified.

### R9 Carried
- HI-0080: `platform-messaging-autoconfigure` must start without Micrometer or Jackson on the classpath.
- HI-0082: send on the caller's thread when possible, or carry the trace to the send thread, so template observation does not
  start a new root trace; a thread-context mandatory expression is refused or documented.

## 3. Exclusions

| Item | Where |
|---|---|
| Kafka | §3.9 compile check. The new SPI method's default leaves it unchanged |
| Exactly-once, ordering | Not offered |
| Delayed or parking-lot retry queues | Not offered: they need a plugin or more topology. In-process backoff, bounded by R2 and R3.2 |
| Topology policies and TLS on the broker | Deployment responsibility; `spring.rabbitmq.*` passes through |
| HI-0071, HI-0079, HI-0086 | Parked by the owner, 2026-11-01 |

## 4. Applicable §7 contracts

| § | Applies as |
|---|---|
| 7.1 override | Beans back off; `EventTransport` by type |
| 7.2 snapshot | `MessagingProperties` defaults snapshot, including the new ones |
| 7.3 kill switch | `dc.platform.messaging.enabled`: tested |
| 7.4 outage posture | Broker down, slow; consumer reconnect; startup without a broker |
| 7.5 health | The broker does not affect readiness or liveness; Boot's rabbit indicator stays in `dependencies` |
| 7.6 propagation | R4 |
| 7.7 error surface | Direct publish failure: `EventPublishException` |
| 7.8 redaction | R4.5; no payload in logs on failure |
| 7.9 observability | `platform.messaging.handled` with bounded tags; a dead-letter outcome |
| 7.10 graceful shutdown | R3.3 |
| 7.11 startup | Starts without a broker; connects when it arrives |

## 5. Acceptance criteria

| AC | Criterion | Level |
|---|---|---|
| AC1 | Publish and consume end to end: one trace across producer and handler; all R4 headers present | CONTAINER + CONSUMER |
| AC2 | Failing handler: exactly `max-attempts` attempts, then in the DLQ, with backoff; the main queue is empty | CONTAINER + CONSUMER |
| AC3 | Undeserializable payload: one attempt, then the DLQ, other messages keep flowing, no payload in the log | CONTAINER + CONSUMER |
| AC4 | Consumer killed repeatedly by a message: DLQ after `x-delivery-limit` | CONTAINER |
| AC5 | Broker down: the outbox absorbs, requests unaffected, consumer reconnects by itself, all delivered | CONSUMER |
| AC6 | Slow broker: publisher bounded, nothing lost, consumer keeps working | CONSUMER |
| AC7 | Broker down at startup: the app starts and is ready; consumes once the broker arrives | CONSUMER |
| AC8 | Broker that lost its definitions: topology redeclared on reconnect | CONTAINER |
| AC9 | Duplicate delivery: a naive consumer applies twice (documented risk), the pattern applies once | CONSUMER |
| AC10 | Direct publish that is unroutable: surfaced to the caller | CONTAINER + CONSUMER |
| AC11 | `SIGTERM` with messages in flight: the handler finishes and commits, exit 143, none lost, no message dead-lettered by the stop; restart delivers the rest | CONSUMER |
| AC12 | DLQ inspection and replay with the script: delivered, same event id | CONSUMER |
| AC13 | Unknown field tolerated; newer version goes to the DLQ without retries; replay after upgrade delivers | SLICE + CONTAINER |
| AC14 | Credential-named header refused on direct publish; no payload or token in logs and metrics | SLICE + CONSUMER |
| AC15 | Quorum queues by default on a real broker, including the DLQ; classic through the property | CONTAINER |
| AC16 | Starts without Micrometer and without Jackson (HI-0080); template observation does not start a new root trace (HI-0082) | SLICE |
| AC17 | Phases 1-4 consumers still pass; downstream compile; golden path; CI green | CONSUMER |

## 6. Composition checkpoint C1 (Gate G)

A second app with Core + Obs + DB + Outbox + RabbitMQ: HTTP → DB write + outbox → drain → Rabbit → consumer → DB write, with one
trace across all hops; DB slow, broker healthy; broker down, DB healthy; app killed mid-flow, no loss, bounded duplicates; drainer
and request threads under load do not exhaust the pool. Written up in `composition-c1.md`.

## 7. Baseline targeted tests (Gate A, before any change)

`mvn -o -T1 -B -pl messaging/platform-messaging-api,…-spi,…-rabbit,…-autoconfigure,…-outbox-autoconfigure -am clean verify` → exit 0,
BUILD SUCCESS, 0 failures, 0 skipped (raw log `evidence-raw/gate-a-baseline-tests.log`, sha256 `d24a7bd577a7abfa…`, not committed).

## 8. Decisions to record (decision log, at Gate B)

| ID | Decision |
|---|---|
| D120 | One retry layer in the registrar; native dead-lettering through the SPI (R2) |
| D121 | Quorum by default, with `x-delivery-limit` for consumer-killing messages only (R1) |
| D122 | Topology declared on connection, so startup does not need the broker (R1.4) |
| D123 | Newer `eventVersion` goes to the DLQ rather than being delivered or dropped (R5) |
| D124 | Replay through a dynamic shovel: no custom consumer that could lose a message mid-copy (R7) |
