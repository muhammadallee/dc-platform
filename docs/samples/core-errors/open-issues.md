# Open issues

| ID | Impact | Owner | Mitigation | Link |
|---|---|---|---|---|
| HI-0110 | Book and sample pages of other capabilities still describe removed types | repository owner | Each carries a "not yet updated" banner; each capability phase rewrites its own | [issue register](../../production-readiness/issue-register.md) |
| — | Messaging still carries the trace in its own header through the platform messaging API | repository owner | Spring AMQP observation in Phase 6 | [plan](../../spring-native/plan.md) |
| — | A reused exception holding a mutable value other than a map or list shares it between responses | repository owner | Documented on the errors page: keep such values immutable | [errors](../../modules/errors.md) |
| — | `retryAfter` is not emitted | repository owner | With the REST client's `Retry-After` handling in iteration 2 | [plan](../../spring-native/plan.md) |
