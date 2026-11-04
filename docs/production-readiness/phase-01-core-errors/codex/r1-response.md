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
