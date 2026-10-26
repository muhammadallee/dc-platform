# Open issues

| ID | Impact | Owner | Mitigation | Link |
|---|---|---|---|---|
| HI-0010 | Problem `type` URIs default to `https://errors.dc.com/`, a domain the platform does not own | repository owner | Set `dc.platform.errors.type-base-uri` per service; owner to supply the canonical namespace before Phase 8 | [issue register](../../production-readiness/issue-register.md) |
| HI-0004 | Book chapters 01/02/03/06 still narrate the `X-Correlation-Id` model in places | repository owner | Each carries an authoritative update note; module pages are current | [issue register](../../production-readiness/issue-register.md) |
| A1 | Deprecated properties have no `replacement` (no successor exists) | repository owner | Pending acceptance of scope amendment A1 | [scope](../../production-readiness/phase-01-core-errors/scope.md) |
| — | Messaging still propagates the trace through its own `correlationId` message header (value = trace ID) | repository owner | Converted to `traceparent` in Phase 5 (G5-07) | [gap inventory](../../production-readiness/phase-00-baseline/gap-inventory.md) |
| — | `retryAfter` not emitted | repository owner | Phase 6 (needs the REST client's `Retry-After` handling) | [scope](../../production-readiness/phase-01-core-errors/scope.md) |
