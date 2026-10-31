# Round 2 re-evaluation — DC Platform hardening Phase 1

You reviewed commit 952d0a1 (findings below, verbatim). The implementer responded. For each finding and AC note state **accept** (resolves the concern if implemented as described), **reject** (reasons), or **maintain concern**. You may read files. List anything critical under 'New concerns'.

## Your Round 2 review (verbatim)

Reviewed `952d0a1` against `f231689`, excluding `docs/production-readiness` and `docs/book`. Findings are based on source and cached dependency bytecode. Maven/container gates were not rerun; an attempted in-memory probe could not start under sandbox restrictions. No execution pass is claimed.

No Critical findings or new confirmed header/log-injection defects. No specific Kafka, audit, or messaging compile regression was established.

New finding numbers continue after Round 1’s F1–F10.

### F11 — Boot’s default decorator still lets empty snapshots inherit context
- Severity: High
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java:41`
- Failure scenario: Capture a context-free runnable through `ContextPropagatingTaskDecorator`, then execute it inside caller A’s context—for example, a previously decorated callback invoked during request processing. The runnable sees A’s trace ID and extras.
- Proof or reasoning: `getValue()` returns `null` for an empty context, so Micrometer omits the accessor from the snapshot. Cached Boot 4.1.0 bytecode shows its configured decorator uses the no-argument constructor; that constructor builds a snapshot factory whose `clearMissing` default is `false`. Consequently, neither `setValue()` nor the new isolating scope runs. The revised tests call the clearing method directly and therefore miss this integration path.
- Proposed fix: Configure the platform’s default decorator to use `clearMissing(true)`, respecting consumer overrides, or explicitly represent absence in captured platform context. Test the actual decorator with an empty capture applied inside an existing context, including restoration afterward.

### F12 — The core capability descriptor still cannot be overridden
- Severity: High
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/PlatformBannerAutoConfiguration.java:35`
- Failure scenario: A consumer supplies a `coreCapabilityDescriptor` bean → startup encounters competing bean definitions with Boot’s default override prohibition. Supplying the descriptor under another name leaves both descriptors active.
- Proof or reasoning: Unlike the revised errors descriptor, the core descriptor has no `@ConditionalOnMissingBean`. Its tests contain no descriptor-override case. This is an inherited gap that remains within the explicitly required Phase 1 override contract.
- Proposed fix: Add back-off for the consumer’s core descriptor and test bean identity and absence of duplicate descriptors. At minimum, support the documented-name replacement consistently with errors; reconcile any name-only exception with R4.3’s type-based requirement.

### F13 — Native MVC method-validation errors omit field details
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:165`
- Failure scenario: A controller without class-level `@Validated` declares `@RequestParam @Min(1) int quantity`; a request supplies `quantity=0` → the response is 400 but lacks the required field path and validation message.
- Proof or reasoning: Spring MVC raises `HandlerMethodValidationException`. The platform handles `MethodArgumentNotValidException` and `ConstraintViolationException`, but not this native MVC validation path. The inherited handler delegates to `handleExceptionInternal`, which assigns `DC-CORE-0499` and decorates the body without constructing `errors[]`. Cached Spring 7.0.8 bytecode confirms this delegation.
- Proposed fix: Override `handleHandlerMethodValidationException`, map input-validation results into field/message entries, and apply the existing rejected-value policy. Preserve 500 semantics for return-value validation failures. Add MVC tests for constrained query parameters and nested validated arguments.

### F14 — Framework 5xx errors lose their server-side diagnostic
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:162`
- Failure scenario: A `DeferredResult` times out, or MVC encounters a response-conversion failure before commitment → the client receives a generic 503/500, but normal production logging contains no corresponding exception stack trace.
- Proof or reasoning: Framework exceptions enter the inherited specific handlers and then `handleExceptionInternal`; they bypass both platform methods that call `log.error`. The new hook replaces details through `factory.decorate` but never logs the exception. Spring’s superclass does not log the normal, uncommitted-response path. Thus R3.2’s server-side diagnostic requirement remains unmet.
- Proposed fix: Log handled framework 5xx exceptions with their throwable while the request context is active, avoiding duplicate logging. Add captured-log assertions for an async timeout and a framework 500, including the trace ID.

### F15 — Direct requests to the error endpoint omit `instance`
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorController.java:66`
- Failure scenario: Request `/error` directly in the minimal service, or as an authenticated caller → a 500 ProblemDetail is returned without `instance`.
- Proof or reasoning: A normal REQUEST dispatch has no `jakarta.servlet.error.request_uri` attribute. The controller passes `null` to the factory, which consequently never sets `instance`. Its direct converter write bypasses MVC’s usual ProblemDetail instance completion.
- Proposed fix: Fall back to `request.getRequestURI()` when the error-dispatch URI attribute is absent. Test direct access as well as container ERROR dispatch.

## Round 1 disposition

| Round 1 finding | Status | Assessment |
|---|---|---|
| F1 — Rejected bearer tokens bypass writer | **Resolved** | Resource-server authentication and denied handlers now use the platform writers. Rejected-token tests and consumer scenarios were added. |
| F2 — Null customizer source | **Resolved** | Framework decoration now occurs in `handleExceptionInternal` with the originating exception; the integration customizer dereferences it. |
| F3 — Additional filter after registration override | **Resolved** | Filter creation now also backs off on `platformCorrelationFilterRegistration`. Actual servlet-registration assertions remain absent. |
| F4 — Empty snapshots retain caller context | **Partially resolved** | Explicit clearing and nonempty replacement now isolate scopes. Boot’s default empty-snapshot path still bypasses clearing: F11. |
| F5 — Async redispatch loses trace ID | **Resolved** | ASYNC dispatch registration and filtering are enabled. Consumer success/failure scenarios were added; timeout and redispatch-cleanup coverage remain absent. |
| F6 — Minimal service loses correlation | **Resolved** | Core starter now supplies the tracing bridge and Boot infrastructure; minimal-service tests cover valid and missing headers in error responses. |
| F7 — Security standard fields omitted | **Resolved** | Writers now emit `type` and `instance`, with full-contract assertions for 401/403. |
| F8 — Unsafe/incomplete rejection diagnostics | **Resolved** | Diagnostics use propagation outcome and a fixed length-only marker, without header content. |
| F9 — Servlet error dispatch outside contract | **Resolved** | The new error controller handles servlet dispatches, with security ERROR permission and consumer trace checks. F15 concerns a separate direct-request path. |
| F10 — Formatting-sensitive uniqueness gate | **Resolved** | Javac AST parsing handles formatting/qualified names and rejects unsupported nonliteral constructions; negative fixtures were added. |

## Acceptance criteria not met

- **AC1–AC2 — SLICE evidence incomplete:** The tracing slice asserts context/MDC, while the minimal-service tests assert ProblemDetails. No added test verifies the real tracing-to-ProblemDetail path and that same request’s logged MDC together. Consumer coverage is improved.
- **AC3 — Consumer assertions incomplete:** The log check excludes `INJECTEDVALUE`, but does not check absence of the oversized header value; marker presence is checked globally rather than for each rejected case. A2 appropriately permits unit-only control-character coverage.
- **AC8 — SLICE assertion incomplete:** The concurrent tracing test still checks only async `context`, not async `mdcTraceId` (`TraceCorrelationIntegrationTest.java:290`). Concurrent ProblemDetail coverage has been added.
- **AC9 — Incomplete isolation/cleanup evidence:** F11 remains. Tests also do not establish cleanup after actual async/error redispatches.
- **AC11 / R3.3:** Native MVC method-validation responses lack field/message entries, as described in F13.
- **AC12 — SLICE log assertions absent:** Consumer coverage now includes both exception categories, but slice tests still do not assert their stack traces and trace IDs in logs. F14 additionally violates R3.2 on framework failures.
- **AC16:** F12 remains. Per-type overrides are also not universally demonstrated: banner and registration overrides remain name-based, and registration tests do not inspect actual servlet registrations.
- **AC17 — Unverified, not demonstrated failed:** Downstream `-amd test-compile` and the golden-path gate could not be executed here.
- **R3.1:** F15 leaves a reachable error response without `instance`.
- **R4.1 / A1:** Replacement metadata remains absent. A1 explicitly makes this a **Low, non-blocking owner-acceptance item**; the new metadata tests verify level and migration reason.

No additional implementation or test-definition gaps were found for **AC4–AC7, AC10, AC13–AC15, or AC18**, subject to A1 above. In particular, AC18 now waits for handler entry and requires exit 143. This is not an execution certification.

## Implementer response (verbatim)

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
