I would not approve `c7d0bcf` yet. This was a read-only source and test review, including inspection of the installed Spring 7.0.10 bytecode. I did not run the Maven, consumer, or container suites.

1. **Critical — Shared exceptions can still expose another request’s data.**  
   [ProblemDetailFactory.java:128](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactory.java:128)

   `copyOf` copies the property map but shares its values. Suppose a reused exception contains a mutable `metadata` map and an interceptor adds the current customer’s information to that map. Concurrent responses share the same map: one caller can receive another caller’s information. This defeats C9 and the stated N5 mitigation.

   There is a second isolation gap: copying happens **after** `framework.map`. Spring’s `updateAndGetBody` can already have mutated the original body using the request’s locale and `MessageSource`. Concurrent localized responses can therefore race before the copy.

   **Test gap:** `aSharedExceptionInstanceNeverCarriesOneRequestsTraceIntoAnother` uses an immutable code string, without mutable extensions or message resolution.

   **Fix:** create an independent response representation before request-specific localization or interception, including nested mutable extension values. Test shared nested properties with coordinated concurrent requests, and shared exceptions with different locales.

2. **High — The mutable body overrides the exception’s HTTP status and sanitization policy.**  
   [PlatformExceptionHandler.java:112](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:112)

   Spring permits an `ErrorResponseException` whose status differs from its supplied `ProblemDetail.status`. For example, a 503 exception can carry a reused 400 body containing an internal failure message.

   `framework.map` preserves the exception’s 503 in its `ResponseEntity`, but this handler ignores that status. It decorates according to the body’s 400, retains the internal detail, and `wrap` constructs a new **400** exception. The failure’s status, code fallback, retryability and sanitization are consequently wrong.

   **Test gap:** C14/C23/C25/C26 tests always construct matching statuses.

   **Fix:** treat `entity.getStatusCode()` as authoritative, normalize the copied body to it, and use that status consistently for logging, sanitization and wrapping. Test both mismatch directions.

3. **High — Copied extension properties bypass the response-sanitization controls.**  
   [ProblemDetailFactory.java:77](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactory.java:77)

   A service can supply a 500 body with `setProperty("detail", exception.getMessage())` or a `stacktrace` extension. Both survive copying. Replacing the ordinary `detail` field does not remove the extension named `detail`; Spring’s Jackson problem-detail support flattens extension properties into the JSON object. This can emit a duplicate `detail` containing the internal message. A supplied `stacktrace` also survives when `include-stacktrace=false`.

   Similarly, a supplied `traceId` survives when tracing supplies no current ID, contradicting the documented omission behavior.

   **Test gap:** the tests sanitize the ordinary detail field and create fresh bodies; they do not exercise reserved extension names.

   **Fix:** remove collisions with standard problem fields and regenerate platform-owned extensions. Explicitly remove `stacktrace` when disabled and stale `traceId` when unavailable. Verify serialized JSON, including duplicate-key cases.

4. **Medium — Spring’s “already handled; write nothing” result becomes another 500.**  
   [PlatformExceptionHandler.java:108](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformExceptionHandler.java:108)

   `FrameworkErrors.map` uses `null` for an unrecognized exception, but Spring also returns `null` for `AsyncRequestNotUsableException`. A disconnected streaming client therefore falls through to the unexpected-exception branch, producing an ERROR and attempting another response after Spring deliberately suppressed writing.

   Additionally, constructing `ServletWebRequest` with only the request removes the response from Spring’s committed-response check. The previous inherited handler received the actual response.

   **Fix:** distinguish “unrecognized” from “recognized with no response,” pass the actual `HttpServletResponse`, and preserve Spring’s suppression behavior. Add disconnect and committed-response tests; the existing framework logging test does not cover them.

5. **Medium — A valid service `String` code can break error handling.**  
   [ProblemDetailFactory.java:74](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/ProblemDetailFactory.java:74)

   The published contract accepts a nonblank string code unchanged. A code such as `"ORDER ALREADY PAID"` or `"ORDER%FAILED"` passes `serviceCode`, then causes `URI.create(typeBaseUri + code)` to throw inside the exception handler. The intended business response loses its platform contract and service code.

   **Test gap:** AC20/N3 test only URI-safe strings and non-string/blank fallbacks.

   **Fix:** preserve the code property while safely encoding it into the generated type URI, or use a safe fallback type. Test spaces, percent signs and other URI-sensitive characters.

6. **Medium — Restored outbox correlation no longer propagates through task snapshots.**  
   [PlatformOutboxContext.java:74](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/PlatformOutboxContext.java:74)

   Previously, outbox restoration opened `RequestContext`, which its accessor captured. It now writes directly to MDC, while `MdcContextThreadLocalAccessor.getValue()` reads only `MdcContext.asMap()`.

   Consequently, work submitted through a context-decorated executor while an outbox record’s context is restored captures no restored correlation. On a clean worker, its logs lose the record’s trace. This is a behavioral regression beyond compilation and conflicts with contract 6.

   **Test gap:** `theContextRestoresTheStoredTraceAndIgnoresABadOne` checks only synchronous MDC visibility and cleanup.

   **Fix:** restore through the context mechanism captured by the accessor, with scoped cleanup. Test capture during restoration, execution on another thread, and cleanup afterward.

7. **Medium — C7’s out-of-order-close guarantee is not implemented or tested.**  
   [MdcContext.java:148](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/internal/MdcContext.java:148)

   Open outer scope A, then inner scope B with an additional MDC key. Closing A first discards B without restoring B’s shadowed entries, leaving B’s extra key behind. Closing B afterward restores A’s trace even though A is already closed.

   This defect was copied from the old implementation; it is **not a newly introduced regression**, but the mandatory carry-over claim remains false.

   **Test gap:** `MdcContextTest` has no out-of-order-close case.

   **Fix:** unwind each discarded scope’s restoration state and make subsequent closes of discarded scopes harmless. Assert exact MDC restoration after both closes, including inner-only keys.

8. **Medium — C24/R2’s replacement gate does not reject all malformed or non-literal codes.**  
   [ErrorCodeSourceUniquenessTest.java:115](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:115)

   The scanner examines only string literals starting with `DC-`. A new platform error using `setProperty("code", lookupCode())`, a malformed `"BROKEN"` value, or `"DC".concat("-MSG-0001")` is invisible. The existing ten declarations still satisfy the minimum-count check, so a dynamic duplicate can pass.

   The old gate identified `ErrorCode` construction independently of its argument and rejected non-literals. The new test’s partial-prefix fixture does not prove equivalent enforcement.

   **Fix:** anchor validation to platform code assignments/usages, resolve permitted constant references, and reject unsupported expressions. Add fixtures for dynamic values, malformed prefixes and runtime-built duplicates.

9. **Medium — The servlet error page does not implement Spring’s interceptor-failure behavior.**  
   [PlatformErrorController.java:94](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformErrorController.java:94)

   Spring’s `AbstractMessageConverterMethodProcessor` catches `Throwable` around the interceptor chain. This implementation catches only `RuntimeException`.

   An interceptor throwing `AssertionError` or `NoClassDefFoundError` is swallowed on the MVC response path but escapes during `/error`, preventing the promised problem body from being written. N2’s documented mitigation therefore differs between paths.

   **Test gap:** the throwing-interceptor test uses only `IllegalStateException`.

   **Fix:** match Spring’s failure boundary, or explicitly define and test a different policy. Exercise an `Error` on both MVC and servlet-error paths.

10. **Medium — Supported foundation overrides still require platform types.**  
    [CoreContextAutoConfiguration.java:52](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CoreContextAutoConfiguration.java:52), [CapabilityDescriptor.java:8](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/core/platform-core-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/core/autoconfigure/CapabilityDescriptor.java:8)

    Replacing the correlation filter requires `CorrelationIdFilter` or `FilterRegistrationBean<CorrelationIdFilter>`. Replacing a capability descriptor requires constructing `CapabilityDescriptor`; an ordinary same-named object is not collected by the descriptor provider.

    These are service-facing paths explicitly retained by C4/C28. Moving their types into `autoconfigure` does not make them Spring types. The scope’s assertion that descriptors are “never service code” also contradicts the descriptor’s own documentation and consumer-override test.

    **Fix:** provide Spring-only or property-based override paths and test them with a consumer importing no platform types. Otherwise record the required rule-12 step-4 exception explicitly.

11. **Low — Live foundation documentation still instructs readers to use deleted APIs.**  
    [docs/samples/core-errors/README.md:30](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/docs/samples/core-errors/README.md:30), [operations.md:57](C:/Users/Ali/AppData/Local/Temp/codex-p2r1/docs/samples/core-errors/operations.md:57)

    The sample still constructs `ErrorCode` and `NotFoundException`; operations still recommends `ProblemDetailCustomizer` and says its source is never null. Those instructions cannot be followed after this deletion. The operations page also says disabling core removes error-body `traceId`, whereas errors now obtains it independently from the tracer.

    Similar removed-type examples remain in `docs/book/crosscutting/extending.md`. A general “not yet updated” banner does not repair the foundation sample in its own certification phase.

    **Fix:** update the live foundation samples and extension instructions, and include their snippets in compilation checks.

For the remaining questions:

- **Carry-over:** I found no additional behavioral defect in C1–C5, C10–C11, C15, C17–C18, C20–C22, C27 or C29. C4/C28 retain their override behavior but have the Spring-type problem above. Normal scoped executor isolation is covered; that coverage does not establish the outbox or out-of-order cases.
- **Retirement:** R2 is not adequately replaced. I found no additional R1 defect on the bundled OpenTelemetry path.
- **Optional dependencies and adapted modules:** I found no additional startup defect in the absent-tracing/MVC/validation wiring, or additional behavioral regression in the messaging exception constructors, REST-client status calculations or authorized data-value-type removal.
- **Consumer imports:** the Phase-1 probe and default archetype production code have no core/errors platform imports. Examples and optional archetype messaging code still use messaging APIs, explicitly deferred by this phase’s scope. The foundation override paths remain the rule-12 gap.
