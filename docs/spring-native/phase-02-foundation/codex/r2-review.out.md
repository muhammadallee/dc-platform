Reviewed read-only at `c209736`, against `516f54c`, including Spring 7.0.10 bytecode. Nine round-1 findings are resolved; **8 and 11 remain open**. I found no new Critical or High defect.

I did not run Maven, consumer or container suites. I reproduced the runner defect below with an in-memory Bash fixture. No repository files changed.

**Part 1 — Round-1 findings**

1. **RESOLVED.** Copying precedes localization; nested maps/lists are independent. `aSharedExceptionIsLocalisedPerRequestWithoutWritingItsOwnBody` and `nestedValuesOfASharedExceptionAreCopiedPerResponse` test both isolation gaps. The `ResponseStatusException` reason precedence, fallback and locale-aware arguments match Spring 7.0.10.
2. **RESOLVED.** The entity status governs decoration and wrapping. `theExceptionsStatusWinsOverItsBodysInBothDirections` checks both HTTP/body mismatch directions, sanitization and retryability. It does not explicitly assert logging for the mismatched cases.
3. **RESOLVED.** Standard-member collisions and stale trace/stacktrace extensions are removed; valid service codes survive. `extensionsNamedLikeStandardOrPlatformFieldsNeverReachTheBody` checks serialized occurrences, rather than hiding duplicates in a parsed map.
4. **RESOLVED.** Recognized/no-response is distinguished from unrecognized, the servlet response reaches Spring, and custom validation/unreadable-body paths reach its committed check. `aGoneClientOrACommittedResponseGetsNothingMoreAndNoUnexpectedErrorLog` covers disconnect and committed unexpected-exception paths; it **does not cover committed framework or validation exceptions**. A separate observability regression is below.
5. **RESOLVED.** URI encoding preserves the raw code. `aCodeWithURIUnsafeCharactersIsEncodedInTheTypeAndKeptRawElsewhere` covers spaces, `%`, `#` and Arabic text.
6. **RESOLVED.** Capture reads MDC trace keys; replacement hides and restores directly written trace/span keys. `workSubmittedDuringARestoreCarriesTheStoredTrace`, the direct-MDC accessor tests and `anIsolatingScopeHidesTraceKeysWrittenDirectlyAndRestoresThem` cover propagation, cleanup and trace-only isolation.
7. **RESOLVED.** Outer-first close unwinds inner scopes in reverse order and invalidates their handles. `closingAnOuterScopeFirstUnwindsTheInnerScopeExactly` also closes a stale handle while a newer scope exists.
8. **NOT RESOLVED.** [ErrorCodeSourceUniquenessTest.java:187](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:187) trusts initializers despite subsequent assignments; [line 235](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:235) recognizes factory receivers by spelling. The negative fixtures do not exercise either escape.
9. **RESOLVED.** The servlet-error interceptor boundary now catches `Throwable`. `anInterceptorThrowingAnErrorStopsTheRestButThePlatformBodyIsStillSent` checks `AssertionError`, response delivery and stopped composition on both paths.
10. **RESOLVED.** Spring-only named filter replacement and property-based capability replacement exist. `backsOffToABeanNamedLikeThePlatformRegistrationWithoutAPlatformType` and `propertiesReplaceOrAddAReportedCapability` prove those paths. Banner, endpoint and gauge use the shared override function; the gauge retains a strong reference.
11. **NOT RESOLVED.** The stale sample APIs and core-disable claim are corrected, but [UsageSnippetCompileTest.java:160](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/UsageSnippetCompileTest.java:160) scans only `modules` and `book`. Consequently, `snippet:sample-core-errors-not-found` in the rewritten sample is never compiled.

**Part 2 — Regressions and remaining defects**

1. **Medium — Dynamic platform codes still escape the gate.**  
   [ErrorCodeSourceUniquenessTest.java:187](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:187), [line 235](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/ErrorCodeSourceUniquenessTest.java:235).

   This passes the scanner’s code-value check:

   ```java
   String chosen = VALID_CODE;
   chosen = lookupCode();
   problem.setProperty("code", chosen);
   ```

   `locals` remembers the initializer and never invalidates it on assignment. The runtime value can therefore be malformed or duplicate another code while the existing declarations satisfy the count and uniqueness checks.

   Independently, `this.factory.create(500, lookupCode(), ...)` escapes because `isFactoryCall` accepts only a receiver that is the identifier `factory`, not `this.factory`.

   **Fix:** accept only demonstrably immutable local values, or invalidate values on assignment; recognize factory calls through their declared receiver type, including qualified access. Add negative fixtures for both examples.

2. **Medium — A failed lab-check can produce LAB PASS.**  
   [lab_script.py:35](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/tooling/labs/lab_script.py:35), [run-lab.sh:32](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/tooling/labs/run-lab.sh:32).

   Blocks are concatenated without checking each block’s final status. For example:

   ```bash
   false && echo should-not-run
   ```

   As a `lab-check`, this fails with status 1, but Bash exempts the left side of `&&` from `errexit`. The generated next-step assignment overwrites that status. I reproduced execution of the subsequent step and **exit 0**.

   **Fix:** capture and enforce each block’s exit status immediately after its body, while preserving the shared shell state. Add runner fixtures for failed `&&` lists and negated commands, alongside ordinary command failures.

3. **Medium — The first-service lab accepts incorrect HTTP statuses.**  
   [first-service/index.md:101](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/docs/labs/first-service/index.md:101), [line 113](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/docs/labs/first-service/index.md:113), [line 129](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/docs/labs/first-service/index.md:129).

   An endpoint returning HTTP 200 with the expected error JSON passes the business-error and unexpected-error checks. `curl -s` succeeds for HTTP errors and discards the status; the greps do not even assert the JSON `status`.

   This can certify precisely the status/body disagreement addressed by round-1 finding 2.

   **Fix:** capture `%{http_code}` separately and assert 409/500, then parse JSON and assert matching body status, code and required extensions.

4. **Medium — Cleanup reports success before confirming process termination.**  
   [run-lab.sh:43](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/tooling/labs/run-lab.sh:43), [first-service/index.md:164](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/docs/labs/first-service/index.md:164).

   `kill` confirms signal delivery, not termination. The lab immediately deletes `app.pid`; the runner then has nothing to clean up. A Java process still performing graceful shutdown—or stuck in a shutdown hook—can remain alive after LAB PASS. The runner’s fallback cleanup likewise sends one signal without checking exit.

   **Fix:** retain the PID until confirmed termination; wait with a bounded deadline, escalate if necessary, and make unsuccessful cleanup affect the final result. Emit LAB PASS after cleanup completes.

5. **Medium — Committed constraint violations disappear without observability.**  
   [PlatformConstraintViolationHandler.java:61](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/PlatformConstraintViolationHandler.java:61).

   A controller can flush part of a response and subsequently call a validated service that throws `ConstraintViolationException`. This branch returns `null`, so Spring treats the exception as handled, but no platform log records the failure. The caller retains the partial response, potentially with a successful status, and operations loses the exception.

   Suppressing another write is correct; silently swallowing the failure is not. Spring’s committed-response path warns, and the catch-all advice logs its corresponding case.

   **Fix:** log the suppressed exception with the current trace, while still writing nothing. Test a committed response for unchanged bytes and exactly one diagnostic.

6. **Low — The rewritten sample’s compilation promise is unenforced.**  
   [UsageSnippetCompileTest.java:160](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/docs/platform-docs/src/test/java/ae/gov/dubaicustoms/platform/docs/UsageSnippetCompileTest.java:160).

   A nonexistent type or method added to the tagged core-errors sample leaves the snippet suite green because that directory is never discovered.

   **Fix:** include `samples/core-errors` in discovery and assert that `sample-core-errors-not-found` is among the collected snippets.

I found **no additional regression** in Spring’s mapped exception paths, copied localization, status normalization or reserved-name removal: platform-built validation bodies retain `errors[]`, and enabled stacktraces are regenerated. I found no additional trace bleed or retained request-state leak in the MDC scope implementation, including Micrometer’s reverse restoration order, nor another defect in capability overrides or filter back-off.

**Part 3 — Carry-over table**

1. **Medium — C24/R2 remain unfulfilled.**  
   [scope.md:155](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/docs/spring-native/phase-02-foundation/scope.md:155).

   The reassigned-local and qualified-factory examples above introduce unchecked runtime codes while the named gate passes. The test exists, but does not establish the promised rejection of nonliteral/malformed codes. Apply Part 2 finding 1 and add those negative fixtures.

2. **Medium — C8’s named pool-cleanup proof masks the condition it checks.**  
   [TraceCorrelationIntegrationTest.java:314](/C:/Users/Ali/AppData/Local/Temp/codex-p2r2/core/platform-core-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/core/autoconfigure/TraceCorrelationIntegrationTest.java:314).

   The “leftover” probe is submitted through `applicationTaskExecutor`, which decorates it. If the previous task left trace state behind, the empty snapshot hides that state before `snapshot()` reads it. The assertion can pass while the worker remains contaminated between tasks.

   This is a **proof gap**, not a demonstrated cleanup defect in the current implementation. The raw-executor accessor/outbox tests are useful, but do not replace verification of Boot’s combined observation/MDC cleanup.

   **Fix:** inspect the same worker through its underlying undecorated executor after the decorated task finishes; assert both MDC and tracer state are empty.

I found **no other carry-over violation**. For C15, the named omission test covers only the default-off half; `requestBodyValidationMapsTo400WithRedactedErrorsWhenRejectedValuesAreOn` and `rejectedValuesOnlyWhenEnabledAndCredentialsAlwaysRedacted` provide the remaining proof and should also be named in the table.
