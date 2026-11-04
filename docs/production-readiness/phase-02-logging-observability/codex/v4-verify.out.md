| Item | Verdict | Reason |
|---|---|---|
| AC5 | **reject** | A newly reachable nested-marker diagnostic leaks `password=hunter5`: `kv("payload", wrap(kv("entity", bad), 16))`. The increased traversal depth reaches the inner marker, whose own rendering still invokes Logstash’s diagnostic catch. See [SanitizedMessageView.java:177][depth], [line 155][value] and regression 1 below. |
| Scheme-scan overlap change | **accept** | [PlatformValueMasker.java:369][scheme] skips a scheme-word token only when another scheme/token match follows. The extracted-pattern probe confirms `Bearer bearer` and `Bearer BEARER` become `Bearer ****`, while both supplied Bearer/Basic overlap examples retain masking of the final credential. |
| Compound credential names | **accept** | [PlatformValueMasker.java:75][keys] includes `cookie`. The probe confirms `sessionCookie=abc123` → `sessionCookie=****` and `{"sessionCookie":"sid=abc123"}` → `{"sessionCookie":"****"}`. |
| Previous regression 1 — scheme-word credentials | **accept** | Both reported case variants are masked by [PlatformValueMasker.java:371][scheme-mask]. |
| Previous regression 2 — ordinary values at depth 16 | **accept** | At [SanitizedMessageView.java:177][depth], `kv("payload", wrap(1793001600123L, 16))` now retains the epoch value. Masking still begins at depth 64, counting the root as zero; this remains the documented fail-closed exception. |
| Previous regression 3 — null-array diagnostic leak | **accept** | [SanitizedMessageView.java:121][render] renders the supplied `kv("items", new Object[]{null, bad})` through `valueText`; its throwing element reaches the [outer guard at line 75][guard]. The direct `kv("entity", bad)` and `fields(bad)` paths are likewise contained. |

## Regressions

1. **The larger depth limit exposes another status-manager leak.**

   Using the existing test’s `HostileBean`, whose `toString()` throws `IllegalStateException("password=hunter5")`, with no sanitizers and a fresh event:

   ```java
   Object bad = new HostileBean();
   encode(event("{}", new Object[] {
       kv("payload", wrap(kv("entity", bad), 16))
   }));
   ```

   At `f5ce14c`, traversal replaces the inner marker at depth 16 with `****`, so message rendering never invokes it.

   At `7a7c962`, [line 177][depth] permits that descent. The inner marker is an opaque scalar to `sanitizedTree` and returns unchanged. [Line 121][render] then renders the enclosing list through [line 155][value]. The list invokes the inner marker’s `toString()`.

   Encoder **8.1** bytecode establishes the remaining path:

   ```text
   LogstashMarker.toString()
     → SingleFieldAppendingMarker.toStringSelf()
     → StructuredArguments.toString(bad)
     → WarnStatus containing the original throwable
   ```

   That helper consumes the exception, so the platform guard never receives it. The message contains the inner marker’s `[FAILED toString()]` result, while `password=hunter5` enters the SLF4J-bound context’s status manager. An active `OnErrorConsoleStatusListener` can print it unmasked.

   This is a newly reachable message-rendering path, established from source and bytecode—not captured stderr.

2. **Unchanged chained markers lose child-marker text.**

   With no sanitizers:

   ```java
   var argument = new ObjectAppendingMarker("orderId", "ORD-42");
   argument.add(new ObjectAppendingMarker("phase", "ready"));

   encode(event("{}", new Object[] {argument}));
   ```

   Previously, the unchanged-value branch invoked `LogstashMarker.toString()`. Its encoder 8.1 bytecode explicitly appends referenced markers, so the message included both `orderId=ORD-42` and `phase=ready`.

   [SanitizedMessageView.java:121][render] now renders only the parent field. The message becomes exactly `orderId=ORD-42`; `phase=ready` disappears. The new `ObjectFieldsAppendingMarker` branch at [line 130][fields] has the same omission. Mirroring `toStringSelf()` does not preserve the full marker’s rendering.

No additional common-value false positive outside the documented depth/cycle exception, asymptotic text-scan regression, or stack-safety regression was established.

## Verification boundary

Verified checkout `7a7c962` and compared the relevant implementation against `f5ce14c`. Inspected requirements, D102, tests and cached encoder 8.1 bytecode. Ran a Python differential probe using extracted Java patterns, translating `\z` to `\Z`, for scheme overlaps, cookie forms and selected ordinary values.

**No Maven/JUnit run, Java timing measurement, live startup or captured-stderr reproduction completed.** The filesystem is read-only; JShell failed with `Error loading java.security file`. `javap` emitted the cited method bytecode before reporting archive-access errors. Verdicts therefore reflect source/bytecode evidence and pattern probes, not independently passing Java integration tests.

[depth]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:177
[value]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:155
[render]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:121
[guard]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:75
[fields]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:130
[scheme]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:369
[scheme-mask]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:371
[keys]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v3/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:75
