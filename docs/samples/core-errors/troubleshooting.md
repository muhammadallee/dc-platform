# Troubleshooting

Each entry is marked **observed** (it happened during hardening or this phase) or **anticipated**.

| Symptom | Cause | Fix | Label |
|---|---|---|---|
| Error bodies have no `traceId` | No tracer: tracing disabled, or the core starter excluded | Keep `platform-starter-core` (it brings the OpenTelemetry bridge); do not set `management.tracing.enabled=false` | anticipated |
| An `@Async` task logs under another request's trace | A second `TaskDecorator` bean, or your own decorator that does not clear missing values | One decorator only; build yours with `ContextSnapshotFactory.builder().clearMissing(true)` | **observed** (Boot's default decorator, Phase 2 N1) |
| `@Async` work has no trace at all | Two `TaskDecorator` beans: Boot's executor builder then uses neither | Remove one | anticipated |
| An executor you built has no trace | It has no decorator | `executor.setTaskDecorator(decorator)` with the application's `TaskDecorator` bean | anticipated |
| A `@ResponseStatus` exception comes back as 500 | A platform version before Phase 2 | Upgrade | **observed** |
| An interceptor's field is missing from some bodies | An interceptor before it threw; Spring skips the rest silently | Make interceptors unable to throw; the error page logs a WARN | anticipated |
| `type` looks percent-encoded | Your code has a space or a special character | Expected: `code` and `title` keep the raw value | anticipated |
| Turning on `spring.mvc.problemdetails.enabled` changes nothing | Expected: Boot's handler backs off to the platform's | — | **observed** (Phase 2 N4, test) |
| Callers send `X-Correlation-Id` and correlate nothing | The header is ignored by design | Callers send `traceparent` | anticipated |
| "Port 8080 was already in use" in a container | Code calling `getWebServer().getPort()` during startup starts the connector early | Resolve the port on first use | **observed** (in the probe) |
| `LOGGING_LEVEL_…_CORRELATIONIDFILTER=DEBUG` has no effect | Environment relaxed binding lower-cases the class name | Set the package level (`LOGGING_LEVEL_AE_GOV_DUBAICUSTOMS_PLATFORM_CORE_AUTOCONFIGURE=DEBUG`) | **observed** |
| `rejectedValue` missing in validation errors | Off by default (personal data) | `dc.platform.errors.include-rejected-values=true` if no field holds personal data | anticipated |
