# Codex round 1 — reassessment of Claude's answers (Spring-native Phase 2)

Your round-1 review of `516f54c..c7d0bcf` is `docs/spring-native/phase-02-foundation/codex/r1-review.out.md`. Below is
Claude's answer to each finding, with the fix Claude intends. The fixes are **not yet written**: judge the answers and
the intended fixes. Review read-only at `c7d0bcf`.

For each finding answer **ACCEPT** (the answer and fix are right), **ACCEPT WITH CHANGE** (say what to change), or
**REJECT** (say why), and say whether your severity stands.

1. **Critical, shared mutable values and localisation before the copy.** *Partly agree.*
   - The localisation race is real: `FrameworkErrors` is now a bean, so Spring sets its `MessageSource`, and
     `handleExceptionInternal` calls `updateAndGetBody`, which writes into the exception's own body. Fix: for an
     `ErrorResponse` exception, `FrameworkErrors.handleExceptionInternal` builds the copy itself and resolves the detail,
     title and type message codes into the copy, as `ErrorResponse.updateAndGetBody` does, so the exception's body is
     never written.
   - Nested values: the copy will also copy nested `Map` and `List` values recursively, so a service's JSON-shaped
     extension values are independent per response. Other mutable objects stay shared; the module page will say a
     reused exception must hold immutable values.
   - Severity: we read it as **High**, not Critical. No platform-owned field crosses requests after the copy; reaching
     another caller's data needs a service to reuse an exception holding a mutable non-JSON object *and* an interceptor
     that mutates it.
2. **High, the body's status overrides the exception's.** *Agree.* The `ResponseEntity`'s status is authoritative: the
   copy is set to it before decorating, and logging, sanitising, `retryable` and the wrapper all use it. Tests in both
   directions (503 exception with a 400 body; 400 exception with a 503 body).
3. **High, reserved extension names.** *Agree.* After decorating, the platform removes extension properties that
   collide with the standard members (`type`, `title`, `status`, `detail`, `instance`), drops a supplied `stacktrace`
   unless `include-stacktrace=true`, and drops a supplied `traceId` when the tracer has none (otherwise overwrites it).
   `code`, `timestamp` and `retryable` are always the platform's. Tests assert the serialized JSON has no duplicate key.
4. **Medium, `AsyncRequestNotUsableException` and the committed-response check.** *Agree.* `FrameworkErrors.map` will
   tell "not a framework exception" (rethrown) from "handled, write nothing" (`null` returned); the advice returns
   `null` for the latter, so nothing is written and nothing is logged as unexpected. The handler takes the
   `HttpServletResponse` and passes `new ServletWebRequest(request, response)`. Tests: an `AsyncRequestNotUsableException`
   writes nothing and logs no ERROR; a committed response is left alone.
5. **Medium, URI-unsafe service codes.** *Agree.* The code is percent-encoded into the `type` URI
   (`UriUtils.encode`); the `code` property and `title` keep the raw value. Tests with a space, `%` and `#`.
6. **Medium, outbox restoration not captured by the accessor.** *Agree.* The accessor will capture the MDC's own
   `traceId`/`spanId` (an empty map when absent) instead of only the platform's scopes, and still apply snapshots as
   isolating scopes. That captures the outbox's restored trace and any trace Boot's tracing put in the MDC. Test: during
   an outbox restore, a task submitted through the application's decorator sees the stored trace on another thread,
   and the MDC is clean afterwards.
7. **Medium, out-of-order close (C7).** *Agree.* Closing an outer scope first unwinds every inner scope's restoration in
   LIFO order and marks them closed; closing a discarded scope later does nothing. Test with an inner-only key.
8. **Medium, the code gate misses dynamic codes (C24, R2).** *Partly agree.* The gate will additionally:
   - reject any string literal that starts with `DC` and is not a whole code (this covers `"DC".concat(…)` and
     `"DC-MSG-" + n`);
   - require every `setProperty(<"code" or a CODE constant>, value)` in platform production code to pass a literal or a
     reference to a `static final String` constant initialised with a literal in the scanned sources; anything else is
     reported. The one generic setter, in `ProblemDetailFactory.decorate`, is named in an allowlist with its reason
     (it writes the code it was given).
   - A code passed through method parameters (`factory.create(…, code, …)`) is checked at the `DC-` literal level only.
     A fully general data-flow check is out of proportion; the property scan plus the literal scan cover every way a
     platform module can introduce a code today.
9. **Medium, interceptor failures on the error page.** *Agree.* Catch `Throwable`, as Spring does, log WARN, stop.
   Test with an `AssertionError` on both the MVC and the error-page path.
10. **Medium, overrides need platform types.** *Partly agree.*
    - Correlation filter: add a Spring-only path. The platform's registration also backs off to any bean **named**
      `platformCorrelationFilterRegistration` (for example a `FilterRegistrationBean` of the service's own filter, or
      one with `setEnabled(false)`), alongside the existing type-based back-off. Test with a consumer importing no
      platform type.
    - Capability descriptors: *disagree that this is a service path.* A descriptor reports a platform capability; a
      service has nothing to report. The by-name back-off exists so a service bean that happens to share the name
      cannot break startup (HI-0024) — that holds whatever the bean's type. We will remove the line in
      `CapabilityDescriptor`'s javadoc that invites a service to replace one, keep the test as a collision test, and
      reword `scope.md` C28 accordingly.
11. **Low, live foundation docs.** *Agree.* `docs/samples/core-errors/**` is this phase's sample and is rewritten at
    Gate H (plan §8 step 3), its snippets compiled. `docs/book/crosscutting/extending.md` is fixed in place now.

Then: does any intended fix introduce a new defect? Is anything you raised missing from these answers?
