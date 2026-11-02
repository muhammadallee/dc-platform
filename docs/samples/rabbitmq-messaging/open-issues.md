# Open issues

| ID | Impact | Owner | Mitigation | Link |
|---|---|---|---|---|
| HI-0087 | A message returned by stopping consumers counts toward `delivery-limit`, so many interrupted drains could dead-letter a healthy one | accepted by the owner, 2026-11-02 | Default 20; replay recovers it | [issue register](../../production-readiness/issue-register.md) |
| HI-0088 | A main queue deleted during a replay loses what the shovel moves after that | accepted by the owner, 2026-11-02; owner reviews it again before the Phase 8 release decision | Both queues are checked first; documented | [issue register](../../production-readiness/issue-register.md) |
| HI-0093 | Consumers start before default-phase lifecycle beans and stop after them | accepted by the owner, 2026-11-02 | A lower phase for that bean; documented | [issue register](../../production-readiness/issue-register.md) |
| HI-0071, HI-0079, HI-0086 | Outbox and transaction edge cases | parked by the owner, to fix before production use | See the outbox sample | [issue register](../../production-readiness/issue-register.md) |
| — | Quorum failover across broker nodes unverified | Phase 8 environment | One node certified | [capability status](../../production-readiness/capability-status.md) |
| — | Handler time is unbounded by the platform | service | Use timeouts in the handler (amendment A1) | [scope](../../production-readiness/phase-05-rabbitmq-messaging/scope.md) |
| — | Kafka keeps its own retry layer on top of the registrar's, and its starter was not checked for HI-0083's gap | not certified | Do not rely on it | [messaging](../../modules/messaging.md) |
