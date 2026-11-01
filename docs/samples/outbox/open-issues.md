# Open issues

| ID | Impact | Owner | Mitigation | Link |
|---|---|---|---|---|
| HI-0071 | The "lost connection while committing is never retryable" rule recognises the commit by a stack frame | repository owner (decision at Phase 4 closure) | Covers `JpaTransactionManager`, tested | [issue register](../../production-readiness/issue-register.md) |
| HI-0086 | On the non-guaranteed paths inside an `afterCommit` callback: a `REQUIRED` transaction's dispatch never runs (relay fallback); a disabled-mode publish inside `REQUIRES_NEW` goes out early | repository owner (decision at Phase 4 closure) | The outbox itself refuses loudly; Spring documents the misuse | [issue register](../../production-readiness/issue-register.md) |
| HI-0078 | Credentials rotated at runtime reach the drainer only after a restart | repository owner (acceptance at closure) | Restart, or a custom `OutboxConnections` bean | [issue register](../../production-readiness/issue-register.md) |
| HI-0079 | A pool with `auto-commit=false`, a second transaction manager and a read through the outbox's `DataSource` in its transaction defeats the ownership check | repository owner (acceptance at closure) | One transaction manager per outbox database | [issue register](../../production-readiness/issue-register.md) |
| HI-0080 | `platform-messaging-autoconfigure` needs Micrometer and Jackson on the classpath, though declared optional | Phase 5 | Both are in the baseline | [issue register](../../production-readiness/issue-register.md) |
| HI-0082 | A mandatory expression reading thread context; Rabbit template observation could start a new root trace | Phase 5 | Neither is configured by the platform | [issue register](../../production-readiness/issue-register.md) |
| HI-0081 | The API compatibility gate compares against nothing until a first release | Phase 8 | CHANGELOG `Breaking` entries | [issue register](../../production-readiness/issue-register.md) |
| HI-0057 / HD4 | Oracle RAC failover unverified | repository owner, `BLOCKED` | Single instance certified | [issue register](../../production-readiness/issue-register.md) |
| HD13 | Certified on Oracle 23ai Free in 19 mode, not on 19c | repository owner | The DDL is 19c-safe | [decisions](../../production-readiness/decisions.md) |
| — | RabbitMQ certified in Phase 5: topology, quorum queues, consumer retry, DLQ, `traceparent` on `EventPublisher` | Phase 5 | The outbox sends `traceparent` itself; direct publish still sends `correlationId` | [plan](../../production-readiness/plan-v3.md) |
| — | Kafka starter not checked for the `spring-boot-amqp`-style gap of HI-0083 | not certified (HD) | Do not rely on it | [capability status](../../production-readiness/capability-status.md) |
