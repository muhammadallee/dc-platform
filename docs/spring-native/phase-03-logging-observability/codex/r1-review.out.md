I found the following issues. References are to `7cf2476`. These are based on the committed sources and local Boot 4.1.2 bytecode; I did not run the build or consumer suites. The attempted in-memory probe could not initialize the JDK security configuration in this sandbox.

1. **Critical — An application-port change can silently expose actuator on that port.**  
   `observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java:57`

   Set only `server.port=8081`. The platform also defaults management to 8081, and Boot’s `ManagementPortType.get()` selects `SAME` for equal nonzero ports. Health, info and Prometheus consequently become accessible through the application route without an explicit management-plane opt-out. The tests cover random ports and a management override, not this collision.

   **Fix:** Detect a collision with the platform-supplied management default and choose a distinct port or fail startup clearly. Preserve an explicitly configured same-port choice. Add an HTTP test for `server.port=8081`.

2. **High — Boot’s `WritableJson` replaces the supposedly retired raw-JSON bypass.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:149`

   For example, log a key-value containing `JsonWriter.standard().write(Map.of("password", "hunter2"))`. The masker returns its `WritableJson` unchanged. Boot then invokes `WritableJson.to(out)` directly; that independent writer does not inherit the platform processor. The password reaches console and file output. An arbitrary implementation can also write unescaped newlines or additional members.

   Removing Logstash markers therefore does **not** justify retiring the raw-output guarantee or its adversarial tests.

   **Fix:** Sanitize opaque JSON output at a final serialization boundary, or safely decode/reprocess it while preserving Boot’s internal deferred-member traversal. Test nested raw values, escaped credential names and line injection.

3. **High — Message arguments are formatted before the masking guard.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogMasking.java:74`  
   `logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/StructuredLogMaskingTest.java:180`

   Boot obtains `ILoggingEvent.getFormattedMessage()` before invoking the JSON processor. A message argument such as `Map.of("password", "correct horse battery staple")` loses its structure first; the text pattern masks only `correct`, leaving the remaining password words. A throwing argument’s `toString()` is caught by SLF4J, which reports the exception directly through `Reporter`, outside the JSON masker.

   The ported throwing-argument tests now use `.pair(...)` with a constant message and no stderr assertion. They no longer establish the old `SanitizedMessageView` guarantee.

   **Fix:** Guard and sanitize arguments before message formatting, retaining field information and containing diagnostic output. Restore argument-path tests, including whitespace-bearing credentials and captured stderr.

4. **High — POJO credential fields lose their former field-name protection.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:158`

   Consider a bean with `getPassword()` returning a secret and `toString()` returning that secret without a label. Previously, a structured value was Jackson-serialized and `CredentialFieldMasker` masked its `password` property. The migrated `addKeyValue("login", bean)` now emits the unlabelled `toString()` result, which ordinary credential patterns cannot identify. An unreadable record also falls back to text.

   `objectValuesAreRenderedAsMaskedText` tests only a conveniently labelled POJO string. Requiring callers to substitute a `Map` does not preserve the unconditional CARRIED credential-field guarantee.

   **Fix:** Preserve structural credential masking for supported beans, or conservatively mask objects whose structure cannot be inspected safely. Test a getter-backed credential and inaccessible record components.

5. **High — Some values are rendered again, unmasked, after processing.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:136`

   Two concrete cases escape the stated “render once” protection:

   - `Path.of("password=hunter2")` passes through as an `Iterable`, but Boot explicitly excludes `Path` from iterable traversal and writes its `toString()` directly.
   - An unchanged `CharSequence` is returned as the original object. Boot calls `toString()` again, so a mutable or stateful implementation can produce different, sensitive text after the checked snapshot.

   **Fix:** Handle `Path` as guarded text and return the checked immutable string for `CharSequence`, including the trace-ID exemption. Extend the object/render-once tests to these types.

6. **High — Unsupported `Number` implementations bypass card masking.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:145`

   `addKeyValue("card", new AtomicLong(4111111111111111L))` passes through: `integralDigits()` recognizes only selected numeric classes and returns null for `AtomicLong`. Boot subsequently writes its decimal representation unchanged. The old Jackson serialization exposed an ordinary numeric value to the generator masker.

   The same unrestricted pass-through lets non-finite doubles produce bare `NaN`/`Infinity`, breaking the valid-JSON guarantee. Existing numeric tests cover neither case.

   **Fix:** Normalize supported numeric representations safely, inspect other numbers conservatively, and give non-finite values valid JSON representations. Add encoder-level tests.

7. **High — Character arrays expose data one character at a time.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:154`

   `addKeyValue("contact", "alice@example.com".toCharArray())` becomes an array of individually processed characters. No email or credential pattern matches, and the complete value remains recoverable. Previously Jackson’s default `char[]` serialization wrote a string through the masking generator.

   The primitive-array regression test exercises doubles only.

   **Fix:** Treat `char[]` as one guarded text value before generic primitive-array conversion. Test email, JWT and credential text in character arrays, including nested arrays.

8. **High — Extra patterns do not replace numeric `LogSanitizer` behavior.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:145`

   Configure an extra pattern matching `12345678`, then log `addKeyValue("accountNo", 12345678L)`. It remains visible because numbers return before `maskPatterns()`. Booleans similarly skip extra patterns. The deleted sanitizer tests explicitly covered numeric values.

   `redactionCanBeSwitchedOffAndExtended` checks only a string message, so it does not establish the claimed HI-0034 replacement.

   **Fix:** Apply configured patterns to safe scalar representations, retaining the original JSON type only when unchanged. Restore the nested numeric sanitizer cases using the replacement mechanism.

9. **High — Renaming a member into a credential name does not trigger masking.**  
   `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:208`

   Configure `logging.structured.json.rename.auth=password`, then log `addKeyValue("auth", "correct horse battery staple")`. Boot’s path retains the original name `auth` while its name processor emits `password`. The platform therefore writes an unmasked credential-named output field.

   Boot evaluates path filters against original paths too. Merely changing customizer registration order does not change that behavior.

   **Fix:** Protect both original and effective output credential names, ideally at the final serialization boundary. Add rename tests in both directions and at nested paths.

10. **High — Nested service processors can run after platform masking.**  
    `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogMasking.java:74`

    A service customizer can add nested members with their own value processor—for example, converting `alice(at)example.com` into `alice@example.com`. Boot applies outer processors before nested processors. The platform sees the harmless original text; the nested processor produces the email afterward, and Boot writes it without another platform pass.

    Property-named customizers run before the platform at the root, but that does not make the platform processor last throughout the tree. The current customizer test only adds `"region":"dxb"`.

    **Fix:** Ensure masking observes final values after nested processing. Add a nested-transforming customizer test rather than relying on root registration order.

11. **High — Strict MDC mode admits unlisted consumer fields with reserved names.**  
    `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogMasking.java:36`

    With `mdc.include=tenant`, put sensitive application data under MDC key `stack_trace` and log an event without an exception. That unlisted MDC entry survives because the filter treats its name as proof that Boot owns it. `tags` has the same problem when Boot contributes no marker field.

    The old MDC filter distinguished consumer entries from encoder metadata. The new test checks only an unreserved `internalDebug` key.

    **Fix:** Apply the allow-list to consumer MDC/key-value entries before combining them with formatter-owned fields. Add reserved-name collision tests.

12. **High — Selecting ECS creates duplicate `service` members and loses log events.**  
    `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:60`

    Set `logging.structured.format.console=ecs`. Boot’s ECS formatter already adds a `service` object; the platform independently adds a scalar `service`. Boot’s JSON writer rejects duplicate member names when writing the event.

    `userStructuredLoggingValuesAlwaysWin` verifies only the Environment property, not successful ECS encoding. A service can therefore make the documented override and lose its logs.

    **Fix:** Make schema additions format-aware, including mixed console/file formats. Test actual ECS output with the platform defaults active.

13. **High — Strict mode removes essential ECS and GELF fields.**  
    `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogMasking.java:36`

    The “Boot’s formats” allow-list is actually Logstash-specific. With GELF and `mdc.include=tenant`, it removes `version`, `host`, `timestamp`, `short_message`, exception fields and `_traceId`/`_spanId`. ECS loses `log`, `process`, `error` and `ecs`, independently of the duplicate-service issue.

    This breaks format overrides and removes essential diagnostic information. No strict-mode test selects either format.

    **Fix:** Separate consumer-field filtering from formatter-owned metadata, or implement and test format-specific handling.

14. **High — Depth limiting does not bound branching cyclic values.**  
    `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:133`

    A map containing itself under both `left` and `right` is passed through repeatedly. Each branch continues until depth 64, producing exponentially many branches before masking the leaves. Encoding can exhaust memory long before finishing.

    `aContainerHoldingItselfEndsTheDescentMasked` tests only single-branch cycles. The removed traversal used identity-based cycle detection.

    **Fix:** Use event-local ancestor identity tracking and an overall traversal/output budget. Test branching cycles and shared-but-acyclic values; the latter retired test remains relevant to a correct cycle fix.

15. **High — The ported consumer cannot obtain its injected `RestClient.Builder`.**  
    `tooling/hardening/scenarios/phase-02-logging-observability.sh:53`  
    `tooling/hardening/scenarios/phase-02/probe/ObservabilityProbe.java:70`

    The script removes the parked REST-client starter without adding `spring-boot-starter-restclient`. The generated service’s Boot 4.1.2 `spring-boot-starter-web` does not provide Boot’s REST-client auto-configuration. The probe compiles against Spring Web but fails startup because its constructor requires a missing builder bean.

    The observability module’s unit-test classpath includes the REST-client starter explicitly, hiding this consumer-classpath gap.

    **Fix:** Add Boot’s REST-client starter to the generated probe POM. Then rerun the consumer criteria, especially AC4, AC8, AC11 and AC12.

16. **High — The golden-path gate still requires the removed encoder JAR.**  
    `tooling/scripts/golden-path.sh:236`

    `assert_jar()` requires `BOOT-INF/lib/logstash-logback-encoder-`. A correctly generated Phase 3 service no longer contains it, so the generator gate fails before HTTP probing. This directly prevents rule 15’s green golden path/CI requirement.

    **Fix:** Replace that presence assertion with Boot logging assertions and assert that the removed encoder is absent. Run the generator gate against a fresh repository.

17. **Medium — Disabled masking still validates and compiles its patterns.**  
    `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogMasking.java:61`

    An invalid pattern such as `[` prevents structured logging initialization even with `redaction.enabled=false`. It also does so with `logging.enabled=false` when the service independently enables Boot structured logging. Both switches are checked only after constructor-time binding and compilation.

    **Fix:** Skip redaction configuration processing when redaction or the capability is disabled. Test invalid patterns with each switch off, and a clear startup failure when masking is enabled.

18. **Medium — JVM-wide format properties survive into subsequent applications.**  
    `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:50`

    Start and close a default JSON application, then start a `local`, console-format or logging-disabled application in the same JVM. Boot’s `CONSOLE_LOG_STRUCTURED_FORMAT` and `FILE_LOG_STRUCTURED_FORMAT` system properties remain set. Contributing nothing does not restore pattern logging; Boot’s bridge does not overwrite an existing system property.

    The tests restore the entire system-properties snapshot between runs, masking this restart/context-sequencing failure.

    **Fix:** Handle the lifecycle of platform-origin format bridge properties without clearing user-owned settings. Add sequential-application tests without intervening test cleanup.

19. **Medium — The outbox run now measures the second instance twice.**  
    `tooling/hardening/scenarios/phase-04-outbox.sh:51`

    `metric()` now reads `HH_MGMT_BASE`, but AC8 switches instances by changing only `HH_BASE` at lines 279 and 284. After starting the second instance, both “first” and “second” metric deltas come from its management port. The assertion can claim that both instances drained even if the first drained nothing.

    **Fix:** Save and switch each instance’s management URL together with its application URL, or pass the scrape URL explicitly.

20. **Medium — The adoption recipe retains the encoder’s version on the platform starter.**  
    `tooling/platform-migrations/src/main/resources/META-INF/rewrite/adopt-platform.yml:16`

    A directly declared `logstash-logback-encoder:8.1` becomes `platform-starter-logging:8.1`. In the installed OpenRewrite 8.66 implementation, version adjustment/removal is inside the `newVersion != null` branch; this recipe supplies none. Even importing the platform BOM does not neutralize the retained explicit version.

    **Fix:** Remove the old version and use the platform BOM, or supply the intended platform version through a properly tested recipe. Test transformed POMs with explicit and managed source versions; loading the recipe is insufficient.

21. **Medium — The dependency swap leaves ordinary Logstash consumers uncompilable or unable to start.**  
    `tooling/platform-migrations/src/main/resources/META-INF/rewrite/adopt-platform.yml:16`

    A typical source service has a Logback file referencing `net.logstash.logback.encoder.LogstashEncoder`, and may import `StructuredArguments`. The new recipe removes their dependency but leaves those references intact. After correcting the version problem, compilation or logging startup still fails.

    **Fix:** Migrate those call sites and encoder configuration, or detect them and require explicit migration before performing the destructive dependency replacement. Add a representative service fixture, not just a recipe-composition assertion.

For **question 1**, the CARRIED rows are not all established: the findings above affect credential/PII coverage, numeric and object values, message rendering, strict MDC, valid JSON and console fallback. I found no additional source-level regression in ordinary Logstash string messages, ordinary marker tags, stack traces, default file masking, field names, banner suppression, the platform’s MDC writers, tracing setup, pod-local readiness, exposure defaults, common tags, capability gauges or descriptor back-off. Consumer-level concurrency, collector-adversity and shutdown proof remains blocked by finding 15.

For **question 2**, neither retirement is fully justified: Boot still supplies a raw-output route, and extra patterns do not cover the retired numeric sanitizer case. The Logstash dependency and its application call sites themselves have been removed.

For **question 3**, the findings above cover the established new risks. I found no additional incorrect application-port actuator reads in the updated smoke script, first-service lab or Phase 2 probe helpers. The custom-Logback bypass and unmasked readable-console output remain explicit scope limitations.

For **question 4**, I found **no new Phase 3 service-facing platform-type requirement**. The ported probe imports none. The archetype and examples still import platform test-kit and messaging types, but those are existing uses outside this logging API replacement; they should not be described as globally free of platform imports.
