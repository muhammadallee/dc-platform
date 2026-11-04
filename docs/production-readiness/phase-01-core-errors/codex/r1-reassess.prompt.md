# Round 1 reassessment — DC Platform hardening Phase 1

You reviewed commit 70a04ce and produced the findings below. The implementer has responded. For EACH finding and each AC note, state one of: **accept** (response resolves the concern if implemented as described), **reject** (with reasons), or **maintain concern** (what remains). Be specific; you may read files in the working directory. Do not introduce new unrelated findings here; list any you consider critical at the end under 'New concerns'.

## Your Round 1 findings (verbatim)

Reviewed `70a04ce` against `f231689`, excluding `docs/production-readiness`. Findings below include regressions and unfulfilled Phase 1 requirements. No Critical or Low findings. Maven and container gates could not be rerun in this read-only workspace; no build pass is claimed.

### F1 — Invalid bearer tokens bypass the platform error writer
- Severity: High
- Location: `security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/PlatformSecurityAutoConfiguration.java:58`
- Failure scenario: Send `Authorization: Bearer not-a-jwt` → authentication fails with a response lacking the required ProblemDetail body, `code`, and `traceId`.
- Proof or reasoning: The platform handlers are configured only through `exceptionHandling`. Spring Security’s `OAuth2ResourceServerConfigurer` separately installs its own authentication entry point on `BearerTokenAuthenticationFilter`. Inspection of the cached 7.1.0 bytecode confirms that token-resolution and authentication failures use that separate path. The new tests exercise missing credentials, not rejected credentials.
- Proposed fix: Configure the platform entry point on `oauth2ResourceServer` as well, preserving required authentication headers. Test malformed, expired, and untrusted tokens for the complete body contract. This changes error formatting, not JWT validation semantics.

### F2 — Framework errors violate the customizer’s non-null exception contract
- Severity: High
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:166`
- Failure scenario: A consumer customizer calls `source.getClass()` to classify an error. A framework-generated 404 or 405 now invokes it with `null`, causing an exception while rendering the error and losing the platform response.
- Proof or reasoning: `createResponseEntity` calls `factory.decorate(problem, code, null)`. The factory passes that null directly to every customizer. The stable `ProblemDetailCustomizer` API explicitly guarantees that `source` is the originating exception and never null.
- Proposed fix: Decorate framework errors at a handler stage that still receives the exception, such as `handleExceptionInternal`, and pass it through. Add a framework-error test using a customizer that dereferences `source`.

### F3 — Registration override leaves an additional platform filter active
- Severity: High
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java:50`
- Failure scenario: A consumer supplies `platformCorrelationFilterRegistration` containing its own filter, possibly disabled or restricted to selected URLs → the platform still creates another `CorrelationIdFilter`, which Boot automatically registers.
- Proof or reasoning: The registration bean backs off by name, but the filter bean only backs off when a separate filter bean exists. A filter contained inside a registration is not such a bean. Boot’s `ServletContextInitializerBeans` automatically registers remaining filter beans. The new override test constructs exactly this configuration but asserts only registration identity.
- Proposed fix: Coordinate filter creation and registration back-off so a consumer-owned registration cannot leave an unregistered default filter bean. Verify actual servlet registrations, URL mappings, and disabled-registration behavior.

### F4 — Empty context snapshots retain the executing caller’s context
- Severity: High
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/RequestContextThreadLocalAccessor.java:59`
- Failure scenario: Capture an empty snapshot, then apply it with `clearMissing(true)` while executing on a thread carrying caller A’s context → the supposedly context-free work still sees caller A’s trace ID.
- Proof or reasoning: Micrometer calls parameterless `setValue()` to clear an absent value. This implementation merely pushes `NONE`; it does not clear or suspend `RequestContext`. The existing unit test explicitly expects the outer context to remain visible. Applying a nonempty snapshot also merges scopes through `RequestContext.open`, allowing absent extras to survive from the executing context.
- Proposed fix: Implement snapshot replacement and temporary context suspension, restoring the exact prior context on close. Test empty snapshots and snapshots lacking the previous caller’s extras, including execution on an already-contextual thread.

### F5 — MVC async redispatch loses the ProblemDetail trace ID
- Severity: Medium
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java:62`
- Failure scenario: A controller returns a `Callable`, `DeferredResult`, or `CompletableFuture` that subsequently fails → the error is handled on an ASYNC redispatch without `RequestContext`, so its ProblemDetail omits `traceId`.
- Proof or reasoning: The initial filter scope closes when the initial dispatch returns. `CorrelationIdFilter` inherits `OncePerRequestFilter.shouldNotFilterAsyncDispatch() == true`; Spring’s observation filter overrides it to false. Consequently, tracing can be active on redispatch while the platform context is absent. Current async tests block on `.get()` inside synchronous controllers and never exercise redispatch.
- Proposed fix: Reestablish the platform context on ASYNC dispatch after the observation filter. Add embedded-server tests for asynchronous success, exceptional completion, and timeout, including cleanup.

### F6 — The minimal supported service loses correlation entirely
- Severity: Medium
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java:46`
- Failure scenario: Run `example-minimal`, or an equivalent core/errors/logging service → requests have no platform request context, and error responses omit `traceId`, regardless of inbound `traceparent`.
- Proof or reasoning: Tracing dependencies are optional in core-autoconfigure. Neither `platform-starter-core` nor the minimal example supplies the tracing bridge and observation infrastructure. The filter is absent without `Tracer`, or uses `Tracer.NOOP` without a tracer bean. Documentation acknowledges this exception, but the supplied phase requirements do not authorize it.
- Proposed fix: Supply the tracing infrastructure needed by the supported baseline, without requiring trace export. Test valid and missing `traceparent` against the minimal consumer and its error responses.

### F7 — Security ProblemDetails omit required standard fields
- Severity: Medium
- Location: `security/platform-security-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/security/autoconfigure/internal/ProblemResponses.java:33`
- Failure scenario: An unauthenticated request reaches the platform entry point → its JSON contains neither `type` nor `instance`.
- Proof or reasoning: The handwritten writer emits status, title, detail, code, optional trace ID, timestamp, and retryable only. Its signature does not receive the request needed to populate `instance`. R3.1 explicitly requires both fields on every error response.
- Proposed fix: Pass request context into the writer and emit the complete shared contract. Assert all required fields for both 401 and 403.

### F8 — Invalid-trace diagnostics neither reliably mark rejection nor exclude raw values
- Severity: Medium
- Location: `core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CorrelationIdFilter.java:100`
- Failure scenario: An all-zero trace ID or parent ID receives no rejection marker; malformed `traceparent: deadbeef` is logged verbatim.
- Proof or reasoning: The regex accepts zero IDs because it checks only character shape. Conversely, `sanitize` preserves every hexadecimal character and hyphen, so malformed inputs composed of those characters remain unchanged. Both contradict AC3’s marker and raw-value requirements.
- Proposed fix: Emit a fixed rejection marker with bounded diagnostic metadata rather than header contents. Base validity diagnostics on the tracing propagator’s behavior, including zero IDs and supported versions. Add negative tests for malformed inputs containing only allowed characters.

### F9 — Servlet error dispatch remains outside the error contract
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:152`
- Failure scenario: A filter calls `response.sendError(409)`, as `IdempotencyKeyFilter` does for duplicate requests → Boot’s `/error` handling returns its default error representation rather than the required platform ProblemDetail.
- Proof or reasoning: The new decoration hook covers bodies produced through this exception advice. It does not cover servlet error dispatch or `BasicErrorController`; the errors configuration supplies no fallback for those paths. Thus “every error response” remains unmet even within existing platform integrations.
- Proposed fix: Provide a platform-compatible servlet error fallback with the required fields and trace continuity. Test a real container `sendError` path with JSON and HTML Accept headers.

### F10 — Error-code uniqueness depends on source formatting
- Severity: Medium
- Location: `errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:28`
- Failure scenario: Another module declares a duplicate using ordinary multiline formatting, such as `new ErrorCode(` followed by `"DC-CORE-0500");` → the uniqueness gate passes.
- Proof or reasoning: The scanner processes individual lines and recognizes only the exact spelling `new ErrorCode("…")`. Multiline constructors, qualified names, and constant arguments are invisible. The classpath registry does not cover the unrelated modules this scanner was introduced to protect.
- Proposed fix: Use a compilation/AST-based check or an aggregated registry of compiled declarations. Add negative fixtures proving duplicates fail across modules and formatting variants.

## Acceptance criteria not met

- **AC1–AC2:** Baseline correlation fails as described in F6. The tracing integration slice also never creates ProblemDetails; the consumer missing-header check verifies context/MDC but not a newly generated ProblemDetail trace ID.
- **AC3:** F8 violates the diagnostic contract. The consumer scenario does not exercise control-character headers.
- **AC8:** Both concurrency suites exercise successful context responses only. Neither verifies concurrent ProblemDetails; the slice also omits an async MDC assertion within its concurrent loop.
- **AC9:** Tests cover initially clean workers but not context replacement or clearing on already-contextual threads. F4 exposes that gap.
- **AC12:** The consumer tests only an unknown exception, not a 5xx `PlatformException`. Slice tests do not assert the required stack trace and trace ID in logs.
- **AC13:** Rejected-token responses bypass the writer (F1), and neither the added slice nor consumer scenario tests a 403 body.
- **AC15:** No metadata assertion verifies the deprecated entries. All three entries lack the replacement required by R4.1. The removed REST-client property has no no-effect test.
- **AC16:** F3 breaks registration override. Coverage is not per bean: capability descriptors lack override tests/guards, and the validation-advice replacement test substitutes an `Object`, not a functioning consumer handler. Advice back-off also remains name-based rather than satisfying the stated type-based contract.
- **AC17:** **Unverified, not demonstrated failed.** No specific Kafka/audit compile regression was established by inspection; downstream compilation and golden-path execution could not be rerun here.
- **AC18:** The harness accepts exit **0 or 143**, rather than requiring 143. It also uses a fixed sleep instead of confirming the slow request entered the handler before SIGTERM.

Additional requirement gaps are **R3.1** (F5–F7, F9) and **R3.7** (F10). No additional gaps were found for **AC4–AC7, AC10–AC11, or AC14** in the inspected implementation and test definitions; this does not constitute an execution pass.

## Implementer response (verbatim)

# Round 1 — Claude's response to Codex findings (reviewed commit `70a04ce`)

Evidence available to the implementer at the time of the review (not given to Codex in the first pass):
targeted module builds green (core 42, errors 44, security 13, restclient 26, observability 28 tests);
downstream `-amd test-compile` over 86 modules incl. Kafka and audit exit 0
(`evidence-raw/downstream-compile.log`); golden-path gate 8/8 (`evidence/golden-path-summary.tsv`);
consumer scenario 3 consecutive passes, 50/50 (`evidence/consumer/run-{1,2,3}`).

| Finding | Position | Reply and evidence | Planned change |
|---|---|---|---|
| F1 invalid bearer token bypasses the writer | **Agree** | Verified: `PlatformSecurityAutoConfiguration` only sets `exceptionHandling(...)`; `oauth2ResourceServer` keeps `BearerTokenAuthenticationEntryPoint` for rejected tokens. The golden-path gate asserts only the 401 status for malformed/expired/untrusted tokens. | Set the platform entry point on `oauth2ResourceServer` too; the entry point emits `WWW-Authenticate: Bearer` without an error description (no rejection reason). Slice tests for malformed, expired and untrusted-key tokens asserting the full body; consumer asserts malformed-token body. |
| F2 customizer receives null source | **Agree** | Verified: `ProblemDetailCustomizer#customize` javadoc says `source` is never null; `createResponseEntity` passes null. | Decorate framework bodies in `handleExceptionInternal(ex, body, …)`, passing the exception; test with a customizer that dereferences `source` on a 405. |
| F3 registration override leaves a second filter | **Agree** (severity: High accepted) | A consumer `platformCorrelationFilterRegistration` with its own (or disabled) filter still gets the platform `CorrelationIdFilter` bean, which Boot auto-registers. | Platform filter bean also backs off when a bean named `platformCorrelationFilterRegistration` exists; test inspects the actual `ServletContextInitializerBeans` registrations incl. a disabled registration. |
| F4 empty snapshot keeps the caller's context | **Agree** | Caller-runs execution (or nested propagation) on a thread with context A applying an empty snapshot keeps A; a non-empty snapshot merges A's extras. | Add `@PlatformInternal RequestContext.replace(CorrelationId-or-null, extras)`: an isolating scope that hides outer scopes (reads and MDC) and restores them exactly on close. Accessor uses it for both `setValue` forms. Tests on an already-contextual thread. |
| F5 async redispatch loses traceId | **Agree** | `OncePerRequestFilter.shouldNotFilterAsyncDispatch()` is true for our filter; Boot's observation filter re-enters on ASYNC. | Filter ASYNC dispatches too (re-open from the current span); embedded-server tests for `Callable` success, exceptional completion, timeout. |
| F6 minimal service loses correlation | **Agree** | `platform-starter-core` does not bring a tracer; `example-minimal` has none. HD5 makes the trace ID the only correlation id, so core must ship a tracer. | `platform-starter-core` adds the Micrometer Tracing OTel bridge + Boot's tracing auto-configuration (no exporter). Test: `example-minimal` with and without `traceparent`, ProblemDetail `traceId`. |
| F7 security body lacks `type`/`instance` | **Agree** | Writer has no request. | Pass the request; emit `type` (`dc.platform.errors.type-base-uri` + code, same default) and `instance`; assert full field set on 401 and 403. |
| F8 diagnostics: zero ids not marked; hex-only input logged raw | **Agree** | Regex checks shape only; sanitizer keeps the traceparent alphabet. | Marker carries no header content at all: fixed text + length + reason category (`format`, `zero-id`, `version`, `oversized`); validity also rejects zero trace/parent ids. Negative tests incl. `deadbeef`. |
| F9 servlet error dispatch outside the contract | **Partly agree** | `sendError` from filters (e.g. idempotency, out of scope but shipped) reaches Boot's `BasicErrorController`. In-scope platform paths (security, MVC) already render ProblemDetail. Still a gap against R3.1 "every error response". | Platform `ErrorController` (before `ErrorMvcAutoConfiguration`, backs off on a consumer `ErrorController`) rendering ProblemDetail from the error attributes; filter also runs on ERROR dispatch, resolving the trace from the observation stored on the request. Test: real container `sendError` with JSON and HTML `Accept`. |
| F10 uniqueness gate is format-sensitive | **Partly agree** | Line-based regex misses multi-line/qualified declarations. An AST/compiled scan is disproportionate for a test gate. | Whole-file scan with comments stripped, tolerant of whitespace/newlines and qualified `ErrorCode`; fixtures prove multi-line, qualified and duplicate cases are caught. Constant-argument declarations (`new ErrorCode(CONST)`) fail the gate as "not a literal" instead of being skipped. |

## Acceptance-criteria notes

| AC | Position | Change |
|---|---|---|
| AC1–AC2 | Agree on test gap | Consumer: missing-header request also asserts a fresh `traceId` in a ProblemDetail; slice adds an error endpoint. |
| AC3 | Agree | F8; control-character headers cannot be sent over HTTP/1.1 by curl/JDK clients (Tomcat rejects them before the app); covered at unit level. |
| AC8 | Agree | Concurrent ProblemDetails in the consumer check; async MDC asserted in the slice loop. |
| AC9 | Agree | F4 tests. |
| AC12 | Agree | Consumer probe adds a 5xx `PlatformException`; slice asserts stack trace + trace ID in the log. |
| AC13 | Agree | F1; 403 slice test with an authenticated principal lacking authority. |
| AC15 | Partly agree | Add a metadata test (entries present, `level=error`, reason) and a no-effect test for the REST-client key. **Disagree on `replacement`:** no successor property exists; scope R4.1 is amended to "replacement where one exists". |
| AC16 | Partly agree | Real override test for the validation advice; capability descriptors get name-based `@ConditionalOnMissingBean`. **Disagree on type-based back-off for the advices:** name-based replacement is the documented contract (`docs/modules/errors.md`), it lets a consumer subclass replace exactly one advice; scope R4.3 wording is clarified to "per bean, by its documented override mechanism". |
| AC17 | Evidence exists | `evidence-raw/downstream-compile.log` exit 0, 86 modules; will be re-run after fixes. |
| AC18 | Agree | Require exit 143 only; probe logs "slow request entered" and the harness waits for that line before SIGTERM. |
