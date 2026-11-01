# Troubleshooting

Each entry is labelled **observed** (it happened during hardening phase 4) or **anticipated**.

| Symptom | Cause | Fix | Label |
|---|---|---|---|
| Startup fails naming `platform_outbox.sql` | The table is missing, or the database user cannot read it | Copy the DDL into your migrations and grant the runtime user access; `dc.platform.messaging.outbox.enabled=false` runs without it, with no guarantee | **observed** |
| `IllegalStateException: OutboxPublisher.publish needs an active, uncommitted transaction` | No `@Transactional`, another database's transaction, or an `afterCommit` callback | Publish inside the transaction that writes the business data | **observed** (tests) |
| `IllegalArgumentException: Outbox rows must not hold credentials` | A header named like a credential (`*token`, `*password`, `*pwd`, `authorization`, `*cookie`…) | Do not put it in the event; the business write rolls back with the refusal | **observed** |
| Row `PARKED`, `platform_outbox_parked` above 0, WARN `parked (… rejections)` | No queue is bound for the destination, or the exchange does not exist | Fix the binding, then run the replay in `operations.md`; the event keeps its id | **observed** |
| Service on the RabbitMQ starter fails: `required a bean of type RabbitTemplate` | Boot 4 moved its Rabbit auto-configuration to `spring-boot-amqp` (HI-0083, fixed) | Upgrade | **observed** |
| Messaging service has no `jvm_*` or pool meters and no `service` tag | The handler registrar created the registry too early (HI-0084, fixed) | Upgrade; a custom `BeanPostProcessor` must not resolve `MeterRegistry` during registration | **observed** |
| Startup fails: `A send could be lost … Set spring.rabbitmq.publisher-confirm-type=correlated` | Confirms, returns or `mandatory` were turned off | Remove the override: the outbox depends on them | **observed** (tests) |
| Startup fails: `The outbox drainer needs its own connections` | The `DataSource` is not a URL-configured pool (wrapped, or Hikari with `dataSource`) | Define an `OutboxConnections` bean with a small pool of its own | **observed** (tests) |
| Startup fails: `pool-size must be at least 2` | One connection cannot run a batch and the metrics beside it | Set 2 or more | **observed** (tests) |
| Consumer sees an event twice | At-least-once: a kill or a timed-out send repeats it under the same `eventId` | Deduplicate by `eventId`; this is expected | **observed** |
| Consumer sees events out of order | Retries and `SKIP LOCKED` reorder | Design consumers to tolerate it, for example with a version on the entity | **anticipated** |
| `platform_outbox_oldest_pending_age_seconds` climbs, no parked rows | The broker is down or slow; the drainer is pausing | Fix the broker; recovery delivers the backlog | **observed** |
| Backlog metrics look frozen; `platform_outbox_gauges_age_seconds` high | The database is unreachable, so they cannot refresh | Fix the database; the drainer resumes by itself | **observed** |
| After rotating database credentials the drainer cannot connect | It reads them once (HI-0078) | Restart | **anticipated** |
| RabbitMQ crashes at boot in a test harness: `.erlang.cookie: eacces` | `rabbitmq-diagnostics` run during boot creates the cookie as root | Wait for the `Server startup complete` log line | **observed** (harness) |
| Install fails on a coverage check with tests skipped | A stale `jacoco.exec` after a class change (HI-0003) | `mvn -o -pl <module> clean` | **observed** |
