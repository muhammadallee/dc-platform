# Phase 5 review round 1: RabbitMQ messaging

You are reviewing an internal Spring Boot 4.1 / Spring 7 platform. Static review only: no JVM or Docker in your sandbox.
Review the change from `dfb3524` to HEAD (`git diff dfb3524 HEAD`), reading the surrounding code. The scope, with requirements
R1–R9 and acceptance criteria, is `docs/production-readiness/phase-05-rabbitmq-messaging/scope.md`. Read it first.

Measured on a real broker through an archetype-generated consumer (Oracle, RabbitMQ, Toxiproxy): 63 of 63 checks, covering exactly 3
attempts then DLQ, replay under the same id, one trace through outbox and broker, start with the broker down, reconnect.

Attack these, with concrete triggers:

1. **Loss.** Can a message be acked without its handler finishing? Consider `ImmediateRequeueAmqpException`, `RequeueException`, a
   non-runtime `Throwable`, an `Error` from a handler, the eventType filter returning normally, and a failed dead-letter route
   (quorum queues default to at-most-once dead-lettering: what if the DLX or DLQ is missing, or the broker fails over?).
2. **Delivery limit.** `x-delivery-limit` with the registrar's requeue on stop: can rolling restarts dead-letter a healthy message?
   Does the limit count messages a consumer merely had buffered when it stopped?
3. **Retry.** Total time a poison message blocks a consumer, with prefetch; interaction of `shutdown-timeout`, backoff, and Kubernetes
   grace. Is the stop's requeue reachable in every path?
4. **Topology.** `addConnectionListener` running `RabbitAdmin` declarations on a connection being created: deadlock, recursion, or a
   failure taking down the connection? A precondition failure when a queue exists as classic: what happens at startup and on reconnect?
   A subscription removed while a declaration runs?
5. **Lifecycle.** `EventHandlerRegistrar` as `SmartLifecycle`: `start()` only sets a flag while subscriptions start in
   `afterSingletonsInstantiated`, so consumers run before the context refresh ends. Handlers before the app is ready? Stop ordering
   against the web server, the outbox drainer, and the transport? `stop(Runnable)`? A restart after stop?
6. **Trace.** `continueFrom` with a malformed `traceparent`; scope leak on exception; `carry` on a virtual thread; whether `MDC` holds.
7. **Headers.** `fromRabbitHeaders` with null values, or a non-string name collision; the credential check on inbound and on the
   outbox's stored path; what a foreign producer can set (`producer`, `eventVersion`, `traceparent` of any size).
8. **Version rule.** Handlers taking a plain payload, `Object` or generic payloads; annotation inherited or absent; an `eventVersion`
   that does not parse being treated as 1.
9. **Optional jars.** Does anything still load Micrometer, tracing or Jackson without them (autoconfigure class reflection, the
   `RabbitEventTransport` config)? `@ConditionalOnBean(EventSerializer.class)` ordering against a user's bean from a later config.
10. **Replay script** `tooling/scripts/rabbit-dlq-replay.sh`: quoting of a queue name, a queue name needing URL-encoding, credentials,
    the shovel's behaviour on a quorum source and when messages keep arriving, loss on failure; `x-death` and `x-delivery-count` on replay.
11. **Kafka and other transports:** the §3.9 compile check is the only requirement, but does anything change behaviour for them
    unintentionally? In-memory `Subscription` with a thrown `RequeueException`?

Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Report concrete defects only, each with file, line, trigger, consequence
and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`. Say so plainly if you find nothing in an area.
