# Troubleshooting

Each entry is labelled **observed** (during hardening phase 5) or **anticipated**.

| Symptom | Cause | Fix | Label |
|---|---|---|---|
| Startup fails: `PRECONDITION_FAILED … x-queue-type` | The queue exists as another type | `quorum-queues=false`, or drain and delete it | **observed** (tests) |
| `@EventHandler methods … both handle …` at startup | Two handlers could receive the same message, or two beans of one handler class | One handler per event type per destination | **observed** (tests) |
| `@EventHandler … is on a bean created after consumers started` | A lazy, prototype or later-created bean has a handler | Put handlers on singletons created at startup | **observed** (tests) |
| Messages in the DLQ, log says `exhausted 3 attempt(s)` | The handler kept failing; the log gives exception classes and where | Fix it, then replay | **observed** |
| Log says `cannot read a message` | The payload does not match the handler's type | Fix the producer; replay or discard | **observed** |
| Log says `understands up to version` | The producer sends a newer version | Upgrade this service, then replay | **observed** |
| `outcome="ignored"` rising | No handler declares that `eventType` here | Expected on a shared destination; otherwise add a handler | **observed** (tests) |
| Direct publish fails: `unroutable: no queue is bound` | No consumer has declared its queue yet | Start the consumer first, or use the outbox, which retries | **observed** |
| Consumer sees an event twice | At-least-once | Deduplicate by `eventId` | **observed** |
| Healthy message in a DLQ after many restarts | A stopping consumer's returns count against `delivery-limit` (HI-0087) | Replay it; lower `prefetch` | **anticipated** |
| Handler fails just after startup, or during shutdown | It depends on a default-phase lifecycle bean (HI-0093) | Give that bean a lower phase | **anticipated** |
| Pod killed before messages return | Grace period below the phases' total | Raise it; see `operations.md` | **anticipated** |
| `replay` fails with HTTP 400 | Shovel plugins are not enabled | `rabbitmq-plugins enable rabbitmq_shovel rabbitmq_shovel_management` | **anticipated** |
