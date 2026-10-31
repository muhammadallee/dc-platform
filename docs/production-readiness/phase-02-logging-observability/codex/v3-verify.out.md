| Item | Verdict | Reason |
|---|---|---|
| R2-F4 | **accept** | [SanitizedMessageView.java:143][depth] replaces unchecked deep values with `****`. The previous input—`kv("payload", Map.of("password", "correct horse battery staple"))` with the map wrapped in 16 successive `List.of(...)` calls—no longer exposes the password in `message`. |
| R2-F5 | **accept** | For `kv("customerName", wrap("Fatima", 16))` and the `customerName → C***` sanitizer, [line 143][depth] removes the unchecked message value. The message uses `****`; the JSON descendant uses `C***`. This accepts the leak fix, not identical representations. |
| R2-F8 | **accept** | [SanitizedMessageView.java:69][guard] catches both `AssertionError("boom")` and `StackOverflowError` from a structured argument’s rendering, substituting `[unrenderable]`. `OutOfMemoryError` explicitly remains excluded. |
| R2-F8 — stderr concern | **accept** | The **reported second-invocation path** is fixed at [SanitizedMessageView.java:128][once]. A fresh `Kv.of("entity", flaky)` whose first rendering returns `"ok"` and second throws `IllegalStateException("password=hunter2")` now supplies the rendered string to SLF4J. A separate diagnostic regression is described below. |
| AC5 / R3 | **reject** | `Bearer bearer` now remains visible at [PlatformValueMasker.java:155][scheme]. The deep common-value masking and newly reachable diagnostic leak below also prevent acceptance. |
| AC6 | **accept** | The reported nested `"Fatima"` input no longer leaks through the message, and `kv("items", new Object[]{"ok", null})` retains its readable representation. See [SanitizedMessageView.java:143][depth] and [line 158][array]. |
| Previous regression 1 — cookie forms | **accept** | [PlatformValueMasker.java:80][cookie] handles both `"cookie"="sid=abc123"` and `{"cookie":12345678}`. The extracted-pattern probe confirms their values become `****`. |
| Previous regression 2 — extra rendering | **accept** | [SanitizedMessageView.java:128][once] retains the first rendering. The supplied fresh-event, first-success/second-failure `Kv` reproduction no longer reaches the second call through SLF4J formatting. |
| Previous regression 3 — escaping errors | **accept** | [SanitizedMessageView.java:69][guard] contains the supplied assertion-throwing and recursively rendering `Kv` values. |
| Previous regression 4 — null-containing arrays | **accept** | [SanitizedMessageView.java:158][array] uses `Arrays.asList`. The supplied `kv("items", new Object[]{"ok", null})` can render as `items=[ok, null]`. |
| Trial change — scheme-scan overlap | **reject** | Both supplied overlap examples now mask the final credential, but [PlatformValueMasker.java:155][scheme] unconditionally exempts a token equal to `bearer`. Concrete regression: `Bearer bearer` previously became `Bearer ****`; it now remains unchanged. |
| Trial change — compound credential names | **reject** | Field handling works, and the supplied `session_token`, `db-password` and `smtpSecret` text examples work. However, `sessionCookie=abc123` and `{"sessionCookie":"sid=abc123"}` remain visible: [PlatformValueMasker.java:74][keys] omits cookie suffixes, while [line 80][cookie] only handles the original cookie names. This contradicts the field-or-text guarantee in [decision-log.md:680][compound-doc]. |
| Trial change — banner off / D108 | **accept** | [PlatformLoggingEnvironmentPostProcessor.java:58][banner] supplies `off` after resolving JSON mode; [line 70][precedence] gives it lowest precedence. Default JSON startup gets no Boot banner, explicit `spring.main.banner-mode=console` wins, and console/local mode returns before adding this default. |

## Regressions

1. **A previously masked Bearer credential now leaks.**  
   At [PlatformValueMasker.java:155][scheme], the input:

   ```text
   Bearer bearer
   ```

   changes from `Bearer ****` to `Bearer bearer`. The comparison is case-insensitive, so `Bearer BEARER` also leaks. The overlap repair must preserve masking when the credential itself equals the scheme word. Both differential results were confirmed with the extracted-pattern probe.

2. **The depth cutoff now masks ordinary R3.3 values.**  
   With no sanitizers, log through `"{}"`:

   ```java
   kv("payload", wrap(1793001600123L, 16))
   ```

   Here, `wrap(value, 16)` means 16 successive `List.of(value)` wrappers. At [SanitizedMessageView.java:143][depth], the epoch value becomes `****` in `message`; previously it remained readable. The structured JSON value remains numeric.

   The same occurs for a deeply nested UUID or ordinary order ID under `payload`. This is the explicit fail-closed trade-off introduced by the fix, but it creates an exception to R3.3’s common-value preservation requirement. It is independent of the intentional whole-field masking of compound credential names.

3. **The null-array repair exposes a diagnostic path that bypasses the outer guard.**  
   Using Logstash’s `kv`, no sanitizers, and a fresh event:

   ```java
   Object bad = new Object() {
       @Override
       public String toString() {
           throw new IllegalStateException("password=hunter2");
       }
   };

   encode(event("{}", new Object[] {
       kv("items", new Object[] {null, bad})
   }));
   ```

   Previously, `List.of(array)` rejected the null before rendering, and the outer guard substituted `[unrenderable]`. Now [SanitizedMessageView.java:158][array] allows traversal, and [line 115][marker-render] renders the original marker.

   Encoder **8.1** bytecode establishes that `SingleFieldAppendingMarker.toStringSelf()` calls `StructuredArguments.toString()`. That helper catches `Exception` internally and submits a `WarnStatus` containing the original throwable to Logback’s status manager. Consequently, the platform’s outer guard never receives the exception.

   With an active `OnErrorConsoleStatusListener` on the `LoggerFactory` context, the diagnostic prints `password=hunter2` to stderr without masking. Without that listener, the sensitive throwable still enters the status manager. This finding is source/bytecode-established, not a captured-stderr reproduction.

No additional asymptotic-time or stack-safety regression was established. The sampled ordinary text corpus remained unchanged by the modified patterns. The compound-cookie omission is an incomplete new guarantee, rather than newly introduced leakage.

## Verification boundary

Reviewed `e1d2842..f5ce14c`, surrounding implementation, tests, requirements and decision D108. Independently ran a Python probe using extracted Java regex strings, with `\z` translated to `\Z`, to compare the changed masking paths. Inspected cached encoder 8.1, SLF4J 2.0.18 and Logback diagnostic bytecode.

**No Maven/JUnit run, live startup, Java timing measurement or captured-stderr reproduction completed.** JShell failed with `Error loading java.security file`; `javap` emitted the relevant bytecode before reporting archive-access errors. Verdicts are therefore source/bytecode assessments supplemented by pattern probes, not independently passing Java integration tests.

[depth]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:143
[guard]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:69
[once]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:128
[array]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:158
[marker-render]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:115
[scheme]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:155
[keys]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:74
[cookie]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:80
[banner]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:58
[precedence]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java:70
[compound-doc]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v2/docs/decisions/decision-log.md:680
