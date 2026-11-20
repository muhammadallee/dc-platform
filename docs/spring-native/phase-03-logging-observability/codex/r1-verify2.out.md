**A, C, E, J and K are all partly resolved.** I reviewed `0df444a..474ceaa` read-only against the source and local Boot 4.1.2/Spring 7.0.10 bytecode. No files changed. I did not independently rerun the reported suites; a Java probe failed during JVM security initialization.

| Item | Disposition | Reason |
|---|---|---|
| A | **Partly resolved** | The added string spellings now work. However, converting the property to `String` first still differs from Boot binding the original typed value. |
| C | **Partly resolved** | Removing the speculative names helps, but the remaining five-name exemption is unconditional—it also applies in Boot’s Logstash format. |
| E | **Partly resolved** | Credential entries now consume budget, and `next()`/record reads have exhaustion checks. Failed accessors still bypass accounting, and `hasNext()` still runs after exhaustion. |
| J | **Partly resolved** | Different subsequent service values survive, and arbitrary-property deletion is fixed. The recorded value still predicts Boot’s assignment; a later environment change makes that prediction wrong. |
| K | **Partly resolved** | Removing the parser eliminates its implementation defects. However, removal also exposes masking regressions for flat customizer members, which remain inside the revised guarantee. |

**I accept revised decision 2 as a narrower contract**, including responsibility for customizer-generated nested output and the explicitly documented ECS raw-value exception. That acceptance applies to both K and the C remainder. The implementation does not yet meet the guarantees retained by that decision.

The remaining A, C and E cases are:

- **A — Critical:** At [PlatformObservabilityEnvironmentPostProcessor.java:86](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1c/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java:86), unquoted YAML `server.port: 8081.0` supplies a numeric value. Boot’s binder converts that number to integer **8081**. This guard instead obtains `"8081.0"`, fails String-to-Integer conversion, and permits the default management port 8081. **Fix:** bind `server.port` directly to `Integer`, preserving its original source type. Add a typed-property/YAML test.
- **C — High:** At [PlatformValueMasker.java:237](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1c/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:237), `writerInternal()` never checks the active format. With Boot’s `logstash`, `addKeyValue("log", writableJson)` can still emit `{"password":"hunter2"}` unchanged. This exceeds the accepted **ECS-only** exception. **Fix:** restrict this exemption to the actual ECS writer, accounting for console and file formats independently.
- **E — High:** Failed record accessors at [PlatformValueMasker.java:391](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1c/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:391) and bean getters at line 422 emit `UNRENDERABLE` without calling `copy()`, so their slots remain uncharged. Repeated objects with throwing getters can therefore exceed the claimed work/output budget. Additionally, the loops at lines 351 and 365 call `hasNext()` before checking exhaustion; a lazy filtering iterator can consume indefinitely there after the budget is spent. **Fix:** charge accessor slots before invoking them, and stop before another iterator callback once exhausted. I accept the explicitly stated **per-value** budget; an event-wide budget is not required.

**New issues introduced by this diff: four.** The A/C/E cases above are remaining defects, not newly introduced ones.

1. **High — Customizer name processors can expose credential fields.**  
   Location: [PlatformLogMasking.java:69](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1c/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogMasking.java:69), exposed by removing the formatter’s final pass.

   **Failure scenario:** A customizer adds the flat member `value="hunter2"` and installs a name processor renaming `value` to `password`. Boot’s value processor receives the original path, while `renamedToACredential()` consults only `logging.structured.json.rename`. The result is `"password":"hunter2"`. Previously, the final pass saw the emitted credential name and masked it.

   The same underlying mismatch lets a valid `spanId` containing `4111111111111111` retain its correlation exemption after being renamed to an ordinary field.

   **Fix:** evaluate credential names and correlation exemptions against the effective emitted name, including customizer name processors. Add both rename cases.

2. **High — Package-wide infrastructure exemptions now leak flat customizer values.**  
   Location: [PlatformValueMasker.java:226](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1c/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:226).

   **Failure scenario:** A customizer adds a flat `auditLogger` member whose value is `loggerContext.getLogger("alice@example.com")`. Every `ch.qos.logback.*` object is returned untouched. Boot renders the logger as text containing that email. The removed final pass previously masked the rendered string.

   This uses neither a nested processor nor an exempt ECS object name.

   **Fix:** exempt only infrastructure values at the positions where Boot needs them. Sanitize ordinary named values from logging-library classes, including their rendered text.

3. **High — A later factory customizer can undo masking at the root.**  
   Location: [PlatformLogstashFormatter.java:63](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1c/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:63).

   **Failure scenario:** A service registers a customizer through `spring.factories` that runs after `PlatformLogMasking`. Its root value processor converts a flat `contact` value from `alice(at)example.com` to `alice@example.com`. Boot executes processors in registration order; the platform has already inspected the harmless input. The final pass previously masked the resulting email.

   Property-listed customizers run before the platform’s factory customizer, but that does not establish final ordering among factory customizers. The revised exception covers **nested** processors, not this root processor.

   **Fix:** install masking after all supported root customizers, with an ordering regression test. Alternatively, explicitly extend the responsibility boundary to later root processors rather than promising their flat output is masked.

4. **Medium — Predicted ownership leaves stale structured formats across restarts.**  
   Location: [PlatformLoggingEnvironmentPostProcessor.java:88](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1c/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:88).

   **Failure scenario:** The post-processor records the platform formatter. An `ApplicationEnvironmentPreparedEvent` listener running after environment post-processing but before logging initialization changes the console format to `gelf`. Boot consequently writes `gelf` into the bridge property. On the next console-mode start, cleanup compares `gelf` with the recorded platform formatter, preserves it, and leaves GELF active. The previous name-based cleanup cleared this stale assignment.

   **Fix:** track confirmed Boot assignments at logging initialization, rather than predicting them from an earlier environment snapshot. Cover this listener sequence and initialization failures.

The parser-specific defects disappear with its removal; I found no other new issues in this diff.
