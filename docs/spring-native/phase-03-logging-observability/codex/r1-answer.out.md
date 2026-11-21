Several fixes remain incomplete. I reviewed `7cf2476..29d8173` read-only, using the amended scope and original review found in the main checkout. This is a source and local-bytecode review; I did not rerun the suites. The attempted runtime probe failed during JDK security initialization.

The numbered issues below distinguish remaining round-1 defects from regressions introduced by the fixes.

| Round-1 finding | Disposition | Reason |
|---|---|---|
| 1 | **Partly resolved** | Literal `8081` is rejected, but numerically equivalent property values bypass the check. Issue A. |
| 2 | **Partly resolved** | Ordinary raw JSON becomes masked text in the platform format. Escaped credential names still leak, and other formats retain the raw-write bypass. Issues B–C. |
| 3 | **Partly resolved** | Structured arguments and ordinary throwing objects are handled. **I accept the reply about pre-existing stderr reporting**: the appender prepares the event before encoding. However, the new argument walk has unguarded rendering paths that can lose the JSON event. Issue D. |
| 4 | **Resolved** | Getter-backed beans retain property names; failed getters/accessors produce placeholders instead of falling back to unsafe object text. |
| 5 | **Resolved** | `Path` becomes masked text; `CharSequence` returns its checked snapshot. The new argument-path exception problem is separately covered by D. |
| 6 | **Partly resolved** | Atomic/custom numbers and non-finite primitives are improved, but subclasses of the trusted numeric classes remain unsafe. Issue G. |
| 7 | **Resolved** | `char[]` is processed as one text value, including nested values. |
| 8 | **Partly resolved** | Ordinary numeric values receive extra-pattern checks. Booleans still bypass them. Issue H. |
| 9 | **Resolved** | Configured credential-name renames are checked alongside original paths. |
| 10 | **Not resolved** | Nested processors can still produce sensitive values after masking. **I do not accept closure or the downgrade solely because the customizer is trusted service code.** Decision 2 still promises protection against accidental loss of masking. Issue K. |
| 11 | **Resolved** | Filtering occurs on consumer MDC/key-value entries before formatter fields are combined. |
| 12 | **Resolved** | The global scalar `service` addition is removed; ECS keeps its own schema. |
| 13 | **Resolved** | The global path filter is removed. Under amended decision 4, strict filtering applies only to the platform format. |
| 14 | **Partly resolved** | Map/list branching cycles and shared acyclic values are handled, but the budget does not stop iteration, and bean ancestor tracking is broken. Issues E–F. |
| 15 | **Resolved** | The generated probe POM adds `spring-boot-starter-restclient`. Consumer execution remains Gate F evidence. |
| 16 | **Resolved** | The golden path now rejects the removed encoder JAR. |
| 17 | **Resolved** | Both switches are checked before redaction-pattern binding and compilation. |
| 18 | **Partly resolved** | JSON-to-console sequencing is repaired, but other format transitions retain stale bridge properties; ownership is inferred from value alone. Issue J. |
| 19 | **Resolved** | Both instances retain their management URLs, and instance switches update both URLs. |
| 20 | **Resolved** | Removing the logging dependency swap removes the retained-version failure. |
| 21 | **Resolved** | Removing that swap avoids automatically breaking existing encoder configuration and imports. |

These are the remaining and newly introduced issues I found:

1. **A — Critical: port collision detection compares spelling rather than port numbers.**  
   [PlatformObservabilityEnvironmentPostProcessor.java:88](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformObservabilityEnvironmentPostProcessor.java:88) — incomplete finding 1.

   Set `server.port=08081`, with no management-port override. The string comparison does not reject it. Spring converts it to integer `8081`, and Boot’s `ManagementPortType` compares integer values, selecting the application port for actuator. Whitespace-padded or explicitly signed values provide similar cases.

   **Fix:** Read and compare the typed port value using Spring’s conversion rules. Preserve the explicit-management-port exception. Test equivalent numeric spellings through startup and HTTP assertions.

2. **B — High: converting `WritableJson` to text does not mask escaped credential names.**  
   [PlatformValueMasker.java:290](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:290) — finding 2 moved into textual processing.

   Log a `WritableJson` whose serialized content is `{"\u0070assword":"hunter2"}` under an ordinary key. `toJsonString()` preserves that JSON escape; the text patterns do not recognize the credential name, and `hunter2` reaches the output. Outer JSON escaping prevents line injection but does not remove the secret.

   **Fix:** Mask opaque application `WritableJson` values whole, or decode and structurally sanitize them under explicit bounds. Restore escaped-name/value and nested-raw regression cases; the existing plain-password test is insufficient.

3. **C — High: application raw writers still bypass masking in Boot’s alternative formats.**  
   [PlatformValueMasker.java:149](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:149), especially `writerInternal()` at line 194 — remaining finding 2.

   Select Boot’s `logstash`, ECS or GELF format and log the original `JsonWriter.standard().write(Map.of("password", "hunter2"))` example. Those formatters do not run `PlatformLogstashFormatter`’s preliminary sanitization. The processor exempts every `WritableJson`, and Boot invokes its writer directly. An arbitrary implementation can still inject raw newlines.

   **Fix:** Distinguish formatter-created deferred writers from application values by their actual boundary/provenance, rather than exempting all `WritableJson` and entire package prefixes. Sanitize application values for every supported format. Test each format with raw credentials and line injection.

4. **D — High: the new argument walk can throw instead of preserving the event.**  
   [PlatformValueMasker.java:281](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:281), also map-key rendering at line 309; [PlatformLogstashFormatter.java:99](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:99) — regression in the finding-3 fix.

   A message argument implementing `CharSequence` whose `toString()` throws reaches an unguarded call. A map argument with a throwing key renderer does likewise. The exception escapes the formatter, losing the JSON event and potentially placing the exception text in Logback status diagnostics. This is separate from the accepted, pre-existing SLF4J stderr report.

   **Fix:** Guard character snapshots, key rendering and traversal failures at the argument/value boundary. Substitute `[unrenderable]` locally without propagating sensitive exceptions into diagnostics. Test through the real appender.

5. **E — High: exhausting the walk budget does not bound traversal or output.**  
   [PlatformValueMasker.java:277](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:277), container loops at lines 308–325 — incomplete finding 14 and a new message-path risk.

   After 10,000 visits, the loops keep consuming elements and allocating placeholders. A million-element list still produces approximately a million output elements. An infinite lazy `Iterable` used as a message argument never finishes; its formerly ordinary `toString()` is now replaced by exhaustive traversal. Null values also return before consuming budget.

   **Fix:** Stop consuming the container when the budget expires, emitting one truncation marker or masking the remaining container whole. Count all entries and share an event-level budget across arguments and key-value pairs. Assert consumption/output bounds, not merely a five-second threshold for 50,000 elements.

6. **F — Medium: bean recursion removes an ancestor owned by an outer invocation.**  
   [PlatformValueMasker.java:338](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:338) — new walk defect.

   Beans are absent from the `container` classification, but the intervening `finally` nevertheless removes them from `ancestors`. For a bean whose `getSelf()` returns itself, the recursive invocation removes the outer invocation’s entry before `object()` checks it. The bean expands until the depth/budget limit instead of stopping at the cycle. Branching bean graphs repeatedly execute getters.

   **Fix:** Remove an ancestor only when that invocation successfully added it. Prefer one consistent ancestor lifecycle covering every recursively inspected object. Test self-referencing and mutually referencing beans.

7. **G — High: extensible numeric classes remain an unchecked serialization route.**  
   [PlatformValueMasker.java:374](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:374) — remaining finding 6.

   A `BigDecimal` subclass can hold numeric zero but override `toString()` to return `4111111111111111`. The card check examines the numeric value, the `instanceof BigDecimal` exemption preserves the subclass, and Boot later writes its overridden text unchanged. Other overridden text can produce invalid or injected JSON.

   **Fix:** Trust exact immutable numeric classes, not subclasses. Normalize other implementations from one guarded textual snapshot into a trusted number, check that representation, and never return the original subclass. Reuse the same snapshot on parse failure rather than rendering it again.

8. **H — Medium: Boolean values still bypass configured patterns.**  
   [PlatformValueMasker.java:293](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:293) — remaining portion of finding 8.

   With extra pattern `^true$`, `addKeyValue("sensitiveFlag", true)` still writes `true`; nested Boolean values behave identically. This was explicitly included in the original finding and remains outside the replacement for value sanitizers.

   **Fix:** Apply extra patterns to Boolean text, retaining the Boolean type only when unchanged. Cover direct and nested values.

9. **I — Medium: preliminary key-value sanitization defeats the trace/span exemption.**  
   [PlatformLogstashFormatter.java:81](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:81) — new formatter regression.

   `addKeyValue("spanId", "4111111111111111")` is a valid nonzero 16-character hexadecimal ID. Preliminary `sanitize()` masks it as a card number before the path-aware processor can apply the top-level exemption. The same value in MDC survives. Extra patterns can similarly destroy valid key-value trace IDs.

   **Fix:** Preserve the top-level correlation-ID rule at preliminary sanitization, using one checked immutable snapshot. Test MDC and fluent key-value IDs with digit-only values and matching extra patterns.

10. **J — Medium: format-property lifecycle handling remains incomplete.**  
    [PlatformLoggingEnvironmentPostProcessor.java:55](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:55) — remaining finding 18.

    Start a default JSON application, close it, then start another specifying `logging.structured.format.console=ecs`. Because platform JSON mode remains enabled, cleanup does not run. Boot leaves the existing platform-format system property unchanged, defeating the new override. Conversely, cleanup cannot distinguish a platform-created property from an explicitly user-supplied system property containing the same formatter name.

    **Fix:** Track ownership and reconcile owned console/file bridge properties with each new environment, including JSON-to-other-format transitions. Preserve user-owned settings. Extend sequential-start tests accordingly.

11. **K — High: nested customizer transformations still occur after masking.**  
    [PlatformLogMasking.java:47](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r1a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogMasking.java:47) — unchanged finding 10.

    A service customizer adds nested members whose processor normalizes `alice(at)example.com` into `alice@example.com`. The outer platform processor sees the original text; the nested processor creates the email afterward. It is written unmasked. No malicious customizer or deliberate secret-writing behavior is necessary.

    **Fix:** Apply masking after nested transformations at the final value boundary, and test a transforming nested customizer. Alternatively, explicitly revise the decision-2 guarantee and carry-over contract to accept that limitation. A future module-page disclaimer alone does not establish the unchanged guarantee.

For **tooling**, I found **none**: no additional regression in the REST-client dependency addition, golden-path assertion, outbox URL changes or recipe removal. Consumer and generator execution remain unverified in this pass.
