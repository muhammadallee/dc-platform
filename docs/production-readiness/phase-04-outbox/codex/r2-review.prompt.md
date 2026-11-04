Commit under review: 311d488

# Phase 4 (Transactional Outbox) — review round 2

Fresh review of the revised code. Read-only checkout at the commit named below; no JVM or Docker in your sandbox. Your
round-1 findings (F1–F23) and the implementer's response are in `docs/production-readiness/phase-04-outbox/codex/` of the
checkout; read `r1-response.md`. Read the current main sources in `messaging/platform-messaging-outbox`,
`messaging/platform-messaging-outbox-autoconfigure`, `messaging/platform-messaging-rabbit`, and the relay in
`events/platform-events-autoconfigure`, plus their tests.

1. For each of F1–F23: **confirmed fixed / partial / not fixed**, one line each. Say whether you accept F7's downgrade, F17's
   partial disposition, F13's different fix and scope amendment A3.
2. Hunt regressions in the code the fixes added, especially: the `afterCommitPhase` stack check (`JdbcOutboxPublisher`) and
   its copy in `TransactionalDispatcher` (which differ: is that a defect?); the virtual-thread publish in
   `RabbitEventTransport` (channel handling, a `CorrelationData` confirm arriving late, `RabbitTemplate` thread-bound state,
   leaked senders under a long alarm, DLQ republish callers); `OutboxDrainer` start/stop races, the `stopping` flag and
   `inBatch`, the maintenance thread's connection use against `pool-size`, `failed = !stopping` when a stop arrives; the
   allow-list in `OutboxConnections` (a Hikari pool configured with `dataSourceClassName` or a `dataSource`, a hidden password
   getter, `schema`, `catalog`, transaction isolation, `readOnly`).
3. Any new finding, with **severity**, `file:line`, a concrete failing scenario and the fix. Do not repeat round-1 items that
   are fixed, and do not list style issues.
4. For AC1–AC18: met / partly / not met / pending Gate F, one line each.

Be brief, and rank findings by severity.
