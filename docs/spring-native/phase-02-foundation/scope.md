# Phase 2 — Foundation: core + errors, Spring-native — Scope

Gate A of the capability recipe (plan §8). Phase start commit: `516f54c` (`develop`, the Phase 1 merge). Branch
`native/phase-02-foundation`.

## 1. What this phase does

Service code stops using the platform's core and errors types. The behaviour they gave moves into auto-configuration,
and a service gets it through Spring types:

| A service used to… | Now it… |
|---|---|
| throw `BusinessException`, `NotFoundException`, `ConflictException` with an `ErrorCode` | throws Spring's `ErrorResponseException` (or a subclass) or `ResponseStatusException`, and sets its code with `getBody().setProperty("code", "ORD-0001")` |
| declare a `ProblemDetailCustomizer` bean | adds a Spring `ErrorResponse.Interceptor` through `WebMvcConfigurer.addErrorResponseInterceptors` |
| read `RequestContext.correlationId()` | reads the trace from Micrometer's `Tracer` (`tracer.currentSpan().context().traceId()`), or the `traceId` MDC key in a log line |

The platform keeps: the correlation filter, context propagation to executors, the `ProblemDetail` contract (`code`,
`traceId`, `timestamp`, `retryable`), Bean Validation mapping, data-access mapping and the servlet error page.

**Excluded:** logging (`Kv`, `LogSanitizer`: Phase 3), messaging and outbox APIs (Phases 5 and 6; here they only change
what they must to compile), the REST client's own redesign (Phase 9; here it is only adapted to compile and keep its
tests).

## 2. Decisions taken at this gate

Recorded as D138 in [`../../decisions/decision-log.md`](../../decisions/decision-log.md).

1. **Customizing an error body: Spring's `ErrorResponse.Interceptor`.** Evidence: a test registered an interceptor
   through `WebMvcConfigurer` and sent eight kinds of error ([`evidence/gateA-interceptor-spike.txt`](evidence/gateA-interceptor-spike.txt)):
   - With today's advice the interceptor fires on every Spring MVC path (a thrown `ErrorResponseException`, a
     `ResponseStatusException`, an unexpected exception, a data-access failure, a 404, a 405, a validation failure), but
     its `ErrorResponse` argument is always `null`: the advice returns a plain `ResponseEntity<ProblemDetail>`. It never
     fires for a `sendError` rendered by the error page.
   - When the advice returns an `ErrorResponseException` whose cause is the original exception, the interceptor receives
     it and `getCause()` is the original.

   So the advice returns, on every path, an `ErrorResponseException` wrapping the original exception as its cause, and
   `PlatformErrorController` applies the same interceptors (Spring's `RequestMappingHandlerAdapter` exposes the list) on
   the error dispatch, with the escaping exception as the cause (none for a bare `sendError`). That meets the plan's
   three requirements with Spring types only: interceptors compose (each `WebMvcConfigurer` adds to one list), each
   sees the originating exception, and framework and error-dispatch errors are covered. No platform type stays in service
   code, so this is not an owner stop (plan §5.6).
   - **Limit, written on the module page:** Spring stops at the first interceptor that throws and swallows the throwable
     without logging; the response is still sent. The platform's error controller does the same but logs a WARN.
2. **A service's own error code is the `code` property of its `ProblemDetail`** (spike S7). A `String` the thrower set is
   kept as it is; otherwise the platform sets `DC-CORE-0499` (4xx) or `DC-CORE-0500` (5xx). Neither a `type` URI
   convention nor a resolver bean: both need the service to learn a platform rule.
3. **`CapabilityDescriptor` moves into `platform-core-autoconfigure`** (package `ae.gov.dubaicustoms.platform.core.autoconfigure`).
   About ten capabilities contribute one for the banner and `/actuator/platform`; it is platform-to-platform, never service
   code. The enforcer's allowance "autoconfigure → `platform-core-api`" becomes "autoconfigure →
   `platform-core-autoconfigure`", the way every Boot auto-configuration module depends on `spring-boot-autoconfigure`.
   Every starter already brings core. `PlatformApi` and `PlatformInternal` go with `platform-core-api`; the ArchUnit
   rule that reads them is rewritten or removed.
4. **Trace identity comes from Micrometer `Tracer`.** The correlation filter and the error body read the current span; on
   an ERROR dispatch, where no scope is open, both fall back to the span of the observation stored on the request (as the
   filter does today). One public helper in `core.autoconfigure` does this for both.
5. **The platform exceptions of the parked REST client and of messaging become `ErrorResponseException` subclasses**
   (`RemoteCallException`, `EventPublishException`, `EventSerializationException`). They keep their codes
   (`DC-RCLIENT-0500`, `DC-MSG-0001`, `DC-MSG-0002`) as the `code` property and their statuses, so the error body is
   unchanged. Messaging's go in Phase 6, the REST client's is redesigned in Phase 9.
6. **The consumer floor is 77**, not the 92 printed in plan §10: rule 14 sets it to the v3 count minus the drops Phase 1
   listed (92 − 15 for JWT security, [`../phase-01-scope-cut/scope.md`](../phase-01-scope-cut/scope.md#drop-ledger)).

## 3. How the platform gives each behaviour (rule 12)

| Behaviour | Step | Mechanism |
|---|---|---|
| Correlation from `traceparent` | 3 | `FilterRegistrationBean<CorrelationIdFilter>`, backs off to a user registration or filter bean; kill switch `dc.platform.core.enabled` |
| Trace in the MDC on REQUEST, ASYNC, ERROR dispatch | 3 | the same filter |
| Context to `@Async` and Boot-built executors, isolated | 3 | a Micrometer `ThreadLocalAccessor` (internal, `META-INF/services`) that captures the MDC trace keys, "none" included |
| Error body contract | 3 | `@RestControllerAdvice` bean `platformExceptionHandler` (replaceable by name) and an `ErrorController` bean (backs off to the user's) |
| Customizing an error body | Spring | the service's own `ErrorResponse.Interceptor` |
| A service's error code | Spring | `ProblemDetail` property `code` |
| Property defaults (core) | 1 | `PlatformCoreEnvironmentPostProcessor`, lowest precedence |

No step-4 type remains in service code.

## 4. Contracts that apply (plan §7)

| # | Contract | Applies | How |
|---|---|---|---|
| 1 | Real override | yes | filter registration, `platformExceptionHandler`, `ErrorController`, banner runner, descriptors |
| 2 | Default snapshot | yes | `CoreProperties`, `ErrorsProperties` |
| 3 | Kill switch | yes | `dc.platform.core.enabled`, `dc.platform.errors.enabled`, both `DEFAULT_ON` |
| 4 | Outage posture | no | no backing dependency. Data-access failures are mapped (row C21) but the database is the data capability's |
| 5 | Health | no | no health indicator |
| 6 | Context propagation | yes | rows C5–C10 |
| 7 | Error surface | yes | rows C12–C24 |
| 8 | Redaction | yes | rows C2, C14, C15 |
| 9 | Observability | no | the capability adds no work of its own to observe; it reads the trace Boot's observation filter starts |
| 10 | Graceful shutdown | yes | AC18 in the consumer run |
| 11 | Startup under adversity | no | no dependency at startup |
| 12 | Spring types only | yes | the probe app and the archetype import no core or errors type |
| 13 | Back-off to the user's Spring bean | yes | a user `ErrorController` replaces the platform's; a user `FilterRegistrationBean<CorrelationIdFilter>` replaces the registration |
| 14 | Zero-config acceptance | yes | AC22 |

## 5. Acceptance criteria

The v3 criteria carry, except AC13 (security, cut). Each is tested.

| # | Criterion | Level |
|---|---|---|
| AC1 | Valid `traceparent` → the body's `traceId` and the MDC `traceId` equal the inbound trace ID | SLICE + CONSUMER |
| AC2 | No `traceparent` → a new 32-hex trace ID in the body and the MDC | SLICE + CONSUMER |
| AC3 | Malformed, oversized or control-character `traceparent` → new trace; raw value in no log; length-only marker at DEBUG | UNIT + CONSUMER |
| AC4 | `X-Correlation-Id` ignored: not echoed, trace unaffected, not sent outbound | SLICE + CONSUMER |
| AC5 | An outbound call through Boot's `RestClient.Builder` carries `traceparent` with the same trace ID | CONSUMER |
| AC6 | `traceId` in the body and the MDC with `management.tracing.sampling.probability=0.0` | CONSUMER |
| AC7 | An `@Async` method sees the caller's trace ID in `Tracer` and the MDC | SLICE + CONSUMER |
| AC8 | 200 parallel requests, three passes: no trace-ID bleed (response, MDC, async, body) | SLICE + CONSUMER |
| AC9 | MDC empty after each request, on request and pool threads | UNIT + SLICE |
| AC10 | Malformed JSON → 400 `DC-CORE-0410`, no parser internals | SLICE + CONSUMER |
| AC11 | Validation failure → 400 `DC-CORE-0400` with field paths, no `rejectedValue` by default | SLICE + CONSUMER |
| AC12 | An unexpected exception or a 5xx `ErrorResponseException` → generic detail; the log has the stack trace and trace ID | SLICE + CONSUMER |
| AC14 | `retryable` correct for 4xx, 5xx, 429, 503 | UNIT |
| AC15 | Deprecated properties in metadata at `level=error`; setting them has no effect | UNIT |
| AC16 | Default snapshot, override and kill-switch tests pass | SLICE |
| AC17 | `-amd test-compile` of every changed module green; golden path green | build |
| AC18 | SIGTERM with a slow request in flight: the request gets 200, shutdown completes within the grace period | CONSUMER |
| AC19 | An `ErrorResponse.Interceptor` sees the populated body and the originating exception (as the cause) on every path: thrown `ErrorResponseException`, `ResponseStatusException`, `@ResponseStatus` exception, unexpected exception, data-access failure, validation, 404, 405, unreadable body, `sendError` and an exception escaping a filter. Two interceptors compose in order | SLICE + CONSUMER |
| AC20 | A service's `code` property and its 4xx `detail` reach the body unchanged; a 5xx detail is replaced by the generic text | SLICE + CONSUMER |
| AC21 | An exception annotated `@ResponseStatus(CONFLICT)` → 409 with the platform contract, not 500 | SLICE + CONSUMER |
| AC22 | Zero-config: an app with only `platform-starter-core` and `platform-starter-errors` starts and meets AC1, AC2, AC10–AC12 | SLICE |
| AC23 | The consumer probe and the archetype import no `ae.gov.dubaicustoms.platform` type (contract 12) | CONSUMER (grep in the harness) |

## 6. Carry-over table

`C` rows are carried, `R` retired, `N` new risks. Proof names are the tests that must pass at Gate B; "port" means the
existing test is rewritten onto the new types.

| # | Guarantee | Source | Lives today in | Lives after in | Proof | Kind |
|---|---|---|---|---|---|---|
| C1 | `traceparent` is the only correlation header; nothing reads, generates or echoes another | HD5, AC4 | `CorrelationIdFilter` | same | `CorrelationIdFilterTest`, `TraceCorrelationIntegrationTest` (port) | CARRIED |
| C2 | A rejected `traceparent` is logged as a length-only marker; no header content in a log | HI-0007, HI-0020, AC3 | `CorrelationIdFilter`, `CorrelationId` | `CorrelationIdFilter` | `aTraceparentThePropagatorDidNotContinueIsMarkedWithoutItsContent` (port) | CARRIED |
| C3 | The filter runs after Boot's observation filter and before the security chain | HI-0006 | `CoreContextAutoConfiguration.FILTER_ORDER` | same | `CoreContextAutoConfigurationTest` | CARRIED |
| C4 | The filter backs off to a user registration or filter bean, by type | HI-0015, HI-0024 (D98) | `CoreContextAutoConfiguration` | same | `backsOffWhenUserRegistrationPresent`, `RegistrationOverrideContainerTest` | CARRIED |
| C5 | REQUEST, ASYNC and ERROR dispatches carry the trace; ERROR takes it from the request's observation | HI-0017 | `CorrelationIdFilter` + `RequestContext` | `CorrelationIdFilter` + the trace helper | `TraceCorrelationIntegrationTest` async and error-dispatch cases (port) | CARRIED |
| C6 | A context-free task runs with no context, even on a thread serving another caller (Boot's decorator does not clear missing values) | HI-0016, HI-0023 | `RequestContextThreadLocalAccessor`, `RequestContext.replace` | internal MDC accessor in `core.autoconfigure.internal` | `RequestContextThreadLocalAccessorTest` empty-snapshot cases (port) | CARRIED |
| C7 | Nested scopes restore the previous MDC exactly; an out-of-order close is tolerated | HI-0016 | `RequestContext` | internal MDC accessor | `RequestContextTest` nested cases (port) | CARRIED |
| C8 | MDC is empty after each request, on request and pool threads | AC9, contract 6 | filter + accessor | same | `TraceCorrelationIntegrationTest` (port) | CARRIED |
| C9 | 200 parallel requests show no bleed | AC8 | filter + accessor | same | `TraceCorrelationIntegrationTest` concurrency case; consumer AC8 | CARRIED |
| C10 | A trace ID that is not 128-bit is never used as the correlation ID | HI-0006 | `CorrelationIdFilter` | trace helper | `CorrelationIdFilterTest` (port) | CARRIED |
| C11 | The core starter brings a tracer (OTel bridge, no exporter) | HI-0018 | `platform-starter-core` | same | `example-minimal` `TraceCorrelationTest` | CARRIED |
| C12 | Every error body has `code`, `traceId`, `timestamp`, `retryable`, `instance`, including framework rejections and the error dispatch | HI-0014, HI-0021, HI-0027 | `ProblemDetailFactory` | same, trace from `Tracer` | `ErrorContractIntegrationTest`, `PlatformExceptionHandlerTest` (port) | CARRIED |
| C13 | Customization composes and sees the originating exception on every path | HI-0014 (`ProblemDetailCustomizer` contract) | `ProblemDetailCustomizer` | Spring `ErrorResponse.Interceptor` (D138.1) | new `ErrorResponseInterceptorTest` (AC19) | CARRIED |
| C14 | No 5xx body carries an exception message | HI-0008, AC12 | `ProblemDetailFactory` | same | `everyServerErrorHasTheGenericDetail` (port) | CARRIED |
| C15 | `rejectedValue` omitted by default; credential-named fields redacted when enabled | HI-0009, AC11 | `ProblemDetailFactory` | same | `requestBodyValidationOmitsRejectedValuesByDefault` | CARRIED |
| C16 | `type` defaults to `urn:dc:problem:<code>` | HI-0010 | `ErrorsProperties` | same | default snapshot | CARRIED |
| C17 | Spring Security exceptions are rethrown, not turned into 500 (basic auth returns in Phase 8) | HI-0012 | `PlatformExceptionHandler` | same | `PlatformExceptionHandlerTest` security case | CARRIED |
| C18 | Native method validation → 400 with `errors[]` | HI-0025 | `PlatformExceptionHandler` | same | `nativeMethodValidation*` | CARRIED |
| C19 | A framework 5xx is logged once with the throwable and the trace | HI-0026 | `PlatformExceptionHandler` | same | `frameworkServerErrorsAreLoggedOnceWithTheThrowableAndTheTrace` | CARRIED |
| C20 | A direct request to `/error` still has an `instance` | HI-0027 | `PlatformErrorController` | same | `aDirectRequestToTheErrorPathStillHasAnInstance` | CARRIED |
| C21 | Data-access failures map to 409 `DC-DATA-0409`/`0410` or 503 `DC-DATA-0503` with a fixed detail; a lost commit is never a retryable 503 | HI-0054, HI-0059, HI-0062, HI-0068, HI-0071 | `DataAccessFailure` | same | `DataAccessFailureTest`, `PlatformExceptionHandlerTest` | CARRIED |
| C22 | An unreadable body → 400 `DC-CORE-0410` without the parser's message | AC10 | `PlatformExceptionHandler` | same | `PlatformExceptionHandlerTest` | CARRIED |
| C23 | `retryable` is true only for 429, 502, 503, 504 | AC14 | `ProblemDetailFactory` | same | `ProblemDetailFactoryTest` | CARRIED |
| C24 | Platform error codes are unique across every platform module at build time; a non-literal declaration fails | HI-0005, HI-0022 | `ErrorCodeSourceUniquenessTest` (parses `new ErrorCode("…")`) | the same test, re-anchored to `String` literals of the form `DC-<CAP>-<NNNN>` in production sources, each value declared once, with a minimum count so an empty scan fails | `ErrorCodeSourceUniquenessTest` | CARRIED |
| C25 | A business outcome keeps its status and code: `BusinessException.statusHint`, `NotFoundException` 404, `ConflictException` 409 | errors API | `PlatformExceptionHandler.handlePlatformException` | Spring's `ErrorResponse` status and the `code` property (D138.2) | new cases in `PlatformExceptionHandlerTest` (AC20) | CARRIED |
| C26 | 4xx business outcomes log at DEBUG, 5xx at ERROR with the throwable | errors | `PlatformExceptionHandler` | same | `PlatformExceptionHandlerTest` log cases | CARRIED |
| C27 | Deprecated properties stay in metadata at `level=error` | AC15 | metadata | same | `DeprecatedPropertiesMetadataTest` | CARRIED |
| C28 | Capability descriptors are collected by name; a service's same-named bean replaces the platform's | HI-0024 (D97) | `core-api` `CapabilityDescriptor` | `core-autoconfigure` (D138.3) | `consumerCoreDescriptorReplac…` | CARRIED |
| C29 | The parked REST client and messaging keep their error codes and statuses in the body | HI-0008 and the modules' own tests | `PlatformException` subclasses | `ErrorResponseException` subclasses (D138.5) | their modules' existing tests | CARRIED |
| R1 | `CorrelationId` format check (32 lowercase hex) | HI-0007 | `CorrelationId` | Micrometer's `TraceContext`: the filter accepts only a 32-character trace ID from the tracer, never a header value | C10 | RETIRED |
| R2 | `ErrorCode` format check `DC-<CAP>-<NNNN>` for platform codes | `ErrorCode` | `ErrorCode` | the uniqueness scan rejects a platform literal of any other form | C24 | RETIRED |
| N1 | Trace bleed through `Tracer`: services now read the trace from `Tracer`. If Micrometer's observation accessor shows the executing thread's span to a context-free task (Boot's decorator does not clear missing values), the old isolation is lost for `Tracer` readers | new | — | Gate B tests what `tracer.currentSpan()` shows in that case. If it bleeds, the platform supplies a `TaskDecorator` bean built with a snapshot factory that clears missing values (rule 12 step 3, backs off to the user's) | new case in the accessor test | NEW RISK |
| N2 | A throwing interceptor is swallowed by Spring without a log, and the interceptors after it do not run | new | — | documented limit on the module page; the platform's error controller logs a WARN; a test shows the response is still the platform's | `ErrorResponseInterceptorTest` | NEW RISK |
| N3 | A service sets `code` to a non-`String` value or forgets it | new | — | the platform keeps only a `String`; otherwise sets `DC-CORE-0499`/`0500`. Tested | `PlatformExceptionHandlerTest` | NEW RISK |

N1–N3 get issue-register entries at Gate E with these mitigations.

## 7. Removal list

From the Phase 0 removal map. Every call site, from a `grep` at `516f54c`:

| Type | Module | Call sites outside its module | Action |
|---|---|---|---|
| `CorrelationId`, `RequestContext` | `platform-core-api` | core-autoconfigure (filter, accessor, tests), errors `ProblemDetailFactory`, outbox `PlatformOutboxContext.restore`, data `CorrelationIdConverter` and five data tests, test code in logging, messaging and the REST client, probes of v3 phases 1, 2, 7, 8 | filter and accessor rewritten (C5–C10); errors reads `Tracer`; outbox restores the trace into the MDC directly (redesigned in Phase 5); `CorrelationIdConverter` deleted and the data tests use a `String` column; test code puts MDC keys directly; the phase-01 probe ported here, the others in their phases |
| `ErrorCode`, `PlatformException` | `platform-core-api` | errors, messaging-api (2), restclient-api (1), test-api, examples minimal and golden path, MCP server, migrations recipe, phase-01 probe | `String` constants; subclasses per D138.5; test-api's `ProblemDetailAssert` takes a `String` code |
| `CapabilityDescriptor` | `platform-core-api` | about ten autoconfigure modules, observability endpoint | moved (D138.3), imports updated |
| `PlatformApi`, `PlatformInternal` | `platform-core-api` | build-tools ArchUnit rule, data-api | deleted; the rule is rewritten to name what it checks |
| `BusinessException`, `NotFoundException`, `ConflictException`, `HttpStatusHint` | `platform-errors-api` | errors handler, examples, OpenAPI (parked), test-api | Spring's `ErrorResponseException`, `ResponseStatusException` |
| `ProblemDetailCustomizer` | `platform-errors-api` | errors factory and tests | Spring's `ErrorResponse.Interceptor` |
| `EntityId`, `Money`, `PersistenceConventions` | `platform-data-api` | data `MoneyConverter`, data tests | deleted with their converter (ND7) |

**Modules removed:** `platform-core-api`, `platform-errors-api`, `platform-data-api`. They go into
[`../expected-removals.md`](../expected-removals.md), the BOM and `platform-dependencies`.
**Also updated:** the archetype (its `CLAUDE.md`, `AGENTS.md` and tests), `example-minimal`, `example-golden-path`, the
OpenRewrite recipe `adopt-platform.yml`, the MCP server's index, the docs.

## 8. Consumer run: port plan

`tooling/hardening/scenarios/phase-01-core-errors.sh` with the probe in `phase-01/probe/`. **Floor 77** (D138.6).
- The probe moves to Spring types: it throws `ErrorResponseException` with a `code` property instead of a
  `PlatformException`, reads the trace from `Tracer` instead of `RequestContext`, and calls downstream through Boot's
  `RestClient.Builder` instead of the parked `PlatformRestClientFactory`. The parked REST-client starter is no longer
  added: AC5 needs only Boot's instrumented client.
- The infrastructure, faults and assertions stay. A grep step fails the run if the probe imports a platform type (AC23).
- New assertions (above the floor): AC19 for an exception, a framework 404 and a `sendError`; AC20; AC21.
- Repository: `bash tooling/hardening/native-repo.sh native-02`. Runs land in `~/dc-hardening-runs/native-02/`.

## 9. Environment

Docker (the consumer runs the service in a container; no broker or database). JDK 25, Maven offline with the seed
repository. Nothing else.

## 10. Lab

"First service" (`docs/labs/first-service/index.md`, plan §10): generate a service, start it, call an endpoint that
fails, read the `ProblemDetail` with its `traceId`, find the same trace ID in the log. Runner: `tooling/labs/run-lab.sh`
(new in this phase; plan §5.10).
