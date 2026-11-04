# Round 2 — Claude's response (reviewed commit `952d0a1`)

Evidence at review time (not in Codex's first pass): consumer scenario 3 consecutive passes, 83/83
(600 concurrent context calls + 600 concurrent error bodies per run, 0 bleed); downstream `-amd
test-compile` exit 0 over 86 modules; golden-path re-run in progress.

| Finding | Position | Reply | Planned change |
|---|---|---|---|
| F11 Boot's default decorator does not clear for empty captures | **Agree** | Verified: `getValue()` returns `null` for no context, so the accessor is absent from the snapshot and `clearMissing=false` (Boot's no-arg decorator) never calls `setValue()`. | `getValue()` captures absence explicitly (empty map, never `null`), so every snapshot carries the accessor and an empty capture applies an isolating empty scope. Test through Boot's actual `ContextPropagatingTaskDecorator` (no-arg), executed inside another context, with restore. |
| F12 core descriptor not overridable | **Agree** | Inherited gap; errors got a guard, core did not. | `@ConditionalOnMissingBean(name = "coreCapabilityDescriptor")` (shared type → name, D93); test identity and no duplicate. |
| F13 native MVC method validation lacks `errors[]` | **Agree** | `HandlerMethodValidationException` falls through to `handleExceptionInternal`. | Override `handleHandlerMethodValidationException`: parameter results → `field`/`message` (bean-typed parameters → their field paths), rejected-value policy applied, `DC-CORE-0400`; return-value validation stays 500 generic. MVC tests: constrained `@RequestParam`, `@Valid` nested object without `@Validated`. |
| F14 framework 5xx not logged | **Agree** | Decoration does not log. | Log framework 5xx at ERROR with the throwable in `handleExceptionInternal` (the only path they take; no duplicate). Captured-log test for an async timeout (503) and a framework 500, with the trace ID. |
| F15 direct `/error` omits `instance` | **Agree** | REQUEST dispatch to `/error` has no error attributes. | Fall back to `request.getRequestURI()`; test direct access. |

## AC notes

| AC | Position | Change |
|---|---|---|
| AC1–AC2 | Agree | `example-minimal`: one request, real tracing → ProblemDetail `traceId` == the JSON log line's `traceId` (captured output), continued and fresh traces. |
| AC3 | Agree | Consumer: exactly one marker per rejected request (count == number of rejected requests); oversized value forbidden in the log. |
| AC8 | Agree | Concurrent loop also asserts async `mdcTraceId`. |
| AC9 | Agree | Core integration: edge MDC recorder also on ASYNC and ERROR dispatches; `Callable` endpoint and a `sendError` filter; all edges empty. Consumer: `DeferredResult` timeout (503, `retryable=true`, trace). |
| AC12 | Agree | `example-minimal` captured-log assertion: stack trace + `traceId` for an unknown exception. |
| AC16 | Partly agree | F12 fixed; actual servlet registrations inspected (`ServletContextInitializerBeans`) for the registration override. The banner runner and the registration stay name-overridable only: both are infrastructure beans of shared Spring types (`ApplicationRunner`, `FilterRegistrationBean`) where type-based back-off would wrongly react to unrelated consumer beans — recorded as D96. |
| AC17 | — | Downstream compile and golden path are executed after these fixes and recorded. |
