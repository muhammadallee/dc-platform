**Six issues are resolved; five are partly resolved.** I reviewed `29d8173..0df444a` read-only against source and local Spring/Boot bytecode. I did not rerun the reported 160/47 tests; a Java probe failed during security initialization. No files were changed.

| Issue | Disposition | Reason |
|---|---|---|
| A | **Partly resolved** | The tested decimal spellings work. Spring also accepts hexadecimal ports and embedded whitespace; `Integer.valueOf(value.strip())` does not. |
| B | **Resolved** | The walk masks application `WritableJson` whole, closing the escaped-credential example. The remaining processor exemption belongs to C. |
| C | **Partly resolved** | Ordinary names such as `raw` are protected. Names such as `event` still allow application raw writers through. |
| D | **Resolved** | Throwing character snapshots and map-key renderers are guarded; the enclosing walk also contains traversal failures. |
| E | **Partly resolved** | Ordinary lists and null elements are bounded. Credential-named entries escape budget accounting; record traversal and event-wide accounting remain incomplete. |
| F | **Resolved** | Container and bean ancestor removal now matches successful insertion. |
| G | **Resolved** | Exact-class checks and normalization from one snapshot close the numeric-subclass serialization bypass. |
| H | **Resolved** | Boolean values now receive configured-pattern checks through `scalar()`. |
| I | **Resolved** | `sanitizePair()` preserves the correlation-ID exemption before preliminary masking. |
| J | **Partly resolved** | Normal sequential transitions work, and properties present before the first tracked start survive. The marker still deletes subsequently supplied properties and accepts arbitrary property names. |
| K | **Partly resolved** | The supplied `contact` case is masked whole. Later numeric/Boolean transformations still escape the final pass; alternative formats retain the allowed-name nested-processor route. |

The remaining defects and new issues follow. Parser examples show the completed JSON passed to `FinalLinePass`. They are reachable through a service customizer adding a `WritableJson` member named `event`, which the new exemption permits.

1. **Critical — A remains: port conversion differs from Spring.**  
   [PlatformObservabilityEnvironmentPostProcessor.java:103](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java:103)

   **Scenario:** Set `server.port=0x1f91`, without `management.server.port`. This helper catches its parsing failure and returns null, so the collision check passes. Spring’s `StringToNumberConverterFactory` delegates to `NumberUtils.parseNumber`, which accepts that value as **8081**. Actuator can therefore share the application port. `#1f91` and `"8 081"` expose the same conversion mismatch.

   **Fix:** Use Spring’s typed conversion/binding for the comparison. Do not assume that failure of Java’s decimal parser means Boot will reject the property. Add startup coverage for these spellings.

2. **High — C remains: field names do not establish writer ownership.**  
   [PlatformValueMasker.java:233](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:233)

   **Scenario:** Select Boot’s `logstash` format and log `addKeyValue("event", writableJson)`, where the writer emits `{"password":"hunter2"}`. `event` is in `FORMAT_OBJECTS`, so the application writer is returned unchanged. Boot invokes it directly; the platform formatter’s preliminary sanitization and final pass are absent.

   The exemption also covers descendants of those roots and runs before credential/depth checks.

   **Fix:** Sanitize application context values at their input boundary for every supported format. Exempt only writers with established formatter provenance, not arbitrary writers under selected names. Test every exempt name, nested credential paths, and raw line breaks.

3. **High — E remains: several paths evade the traversal budget.**  
   [PlatformValueMasker.java:309](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:309)

   **Scenario:** A map containing a million distinct keys ending in `Password` still produces a million masked entries. The credential-name return precedes `--budget`, so these entries never exhaust it. An endless map-entry iterator with such keys never terminates.

   Additional gaps: the record loop has no exhaustion break; failed accessors bypass `copy()` accounting; each argument/pair gets a fresh `Walk`. Enhanced-for loops also fetch the next element before checking exhaustion.

   **Fix:** Charge every visited entry before credential/accessor handling, check exhaustion before consuming another element or invoking an accessor, apply truncation consistently to records, and share the intended event-level budget.

4. **High — K remains: the final pass does not mask numbers or booleans.**  
   [FinalLinePass.java:69](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:69)

   **Scenario:** A nested processor under `event` transforms an initially harmless number into `4111111111111111`. The resulting JSON number is copied unchanged by `FinalLinePass`. Likewise, a processor producing Boolean `true` bypasses an extra pattern `^true$`.

   Under alternative formats, a nested processor beneath an exempt object can still produce an unmasked email because those formats have no final pass.

   **Fix:** Apply the existing scalar policy to validated final numeric and Boolean tokens, and protect post-transformation values in every supported format. The current `contact` test masks its enclosing object before exercising this boundary.

5. **High — New parser defect: arbitrary bare tokens are accepted and emitted.**  
   [FinalLinePass.java:69](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:69)

   **Scenario:** `{"event":password=hunter2}` passes through unchanged, including the credential text. So do invalid JSON values such as `undefined`, `NaN`, `+1`, and `01`. Finding a delimiter is not validation of a JSON literal or number.

   **Fix:** Accept only `true`, `false`, `null`, or the complete JSON number grammar. Reject every other token and replace the whole line with the masked fallback.

6. **Medium — New decoder defect: malformed strings are repaired instead of rejected.**  
   [FinalLinePass.java:146](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:146)

   **Scenario:** An unescaped newline inside a quoted string is accepted and subsequently escaped. `{"event":"\u+041"}` is accepted as `"A"` because `Integer.parseInt("+041", 16)` permits a sign. Non-ASCII digits accepted by Java’s number parser are also outside JSON’s escape grammar.

   These inputs do not trigger the promised malformed-line replacement.

   **Fix:** Reject unescaped characters below U+0020 and require exactly four ASCII hexadecimal digits after `\u`.

7. **Medium — New depth-tracking defect: discarded containers are not validated.**  
   [FinalLinePass.java:117](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:117)

   **Scenario:** `{"password":[},"ok":1}` becomes `{"password":"****","ok":1}`. `skipContainer()` counts opening and closing brackets without matching their types. It also accepts invalid object contents, missing separators, and invalid primitive tokens inside discarded containers.

   **Fix:** Validate discarded values using the same complete grammar as retained values, with matching container types. Suppress their output, not their validation.

8. **High — New availability regression: final parsing has unbounded recursion.**  
   [FinalLinePass.java:58](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:58)

   **Scenario:** An allowed raw writer emits an `event` containing thousands of nested arrays. Writing that raw text need not recurse, but the new `value()`/`container()` traversal does. Its `depth` argument is never limited. `StackOverflowError` escapes the `RuntimeException` fallback, losing the event or disrupting logging.

   **Fix:** Enforce a nesting limit before descending, preferably with an iterative parser. Apply it to both retained and discarded containers and fail closed when exceeded.

9. **Medium — New encoding regression: Boot’s escaped C1 controls become literal controls.**  
   [FinalLinePass.java:180](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:180)

   **Scenario:** An ordinary message contains U+0085. Boot’s writer escapes ISO control characters, including this character. The final pass decodes it and emits it literally because it only escapes characters below U+0020. Consumers using Unicode line splitting can split that event.

   **Fix:** Preserve Boot’s control-character escaping, including U+007F–U+009F; test the encoded output, not only parsed field values.

10. **Medium — New encoding regression: decoding escapes can corrupt character data during byte conversion.**  
    [FinalLinePass.java:183](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:183), [PlatformLogstashFormatter.java:84](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:84)

    **Scenario:** With US-ASCII logging, a raw JSON string containing `\u00e9` previously survived as ASCII escape bytes. The final pass converts it to literal `é`; `getBytes(charset)` replaces it with `?`. An escaped isolated surrogate such as `\uD800` similarly becomes a raw surrogate and is replaced during UTF-8 encoding.

    **Fix:** Re-escape characters that cannot be represented by the output charset, or emit non-ASCII characters as Unicode escapes. Preserve isolated surrogate escapes or explicitly reject them; do not silently replace them.

11. **Medium — New final-pass gap: valid multiline JSON remains multiline.**  
    [FinalLinePass.java:191](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/FinalLinePass.java:191)

    **Scenario:** A customizer supplies a pretty-printed raw object. `whitespace()` copies its internal CR/LF characters, so the result is one valid JSON object spread across several physical log lines. Leading and extra trailing blank lines also survive.

    **Fix:** Consume structural whitespace without emitting it, and append exactly one configured record terminator after the object.

12. **Medium — New ownership-marker defect / remaining J: subsequent service settings are deleted.**  
    [PlatformLoggingEnvironmentPostProcessor.java:63](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:63)

    **Scenario:** Application A starts with unset bridge properties and records their names. After A closes, application B explicitly sets `CONSOLE_LOG_STRUCTURED_FORMAT=gelf` before starting. B’s post-processor deletes that service-owned value because A’s marker still contains its name.

    Ownership is also recorded before Boot actually writes anything, so failed starts or intervening writers can create false ownership.

    **Fix:** Track actual owned assignments, including their values and lifecycle, and clear only assignments still owned by the platform. Preserve replacements made before the next start. Add this sequence without manually clearing the marker.

13. **Medium — New ownership-marker defect: arbitrary JVM properties can be cleared.**  
    [PlatformLoggingEnvironmentPostProcessor.java:63](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:63)

    **Scenario:** A malformed or externally supplied marker containing `user.timezone` causes the post-processor to clear that unrelated property—even when platform logging is disabled. The marker’s contents are executed as an unrestricted deletion list.

    **Fix:** Restrict cleanup to the two known bridge-property names and validate ownership separately. Prefer internal lifecycle state over an externally mutable list of property names.

**Other new issues in the character guards, bean ancestry fix, numeric normalization, Boolean handling, correlation-ID fix, or observability changes: none found.**
