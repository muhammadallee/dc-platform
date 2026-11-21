**15 findings are resolved; 4 are partly resolved. I found four new issues introduced by this diff.**

Reviewed `0f17c18..8c3efef` read-only, including local Boot 4.1.2 and Logback 1.5.38 bytecode. No repository files changed. I did not independently rerun the reported Maven, Docker or documentation suites.

| # | Verdict | Reason |
|---|---|---|
| 1 | **Partly resolved** | `SafeMap`/`SafeList` stop recursive copying, and the original nested `\S` expansion is fixed. However, `outsideMasks` is not generally idempotent: empty-matching patterns introduce another exponential-growth path, described below. |
| 2 | **Resolved** | Ordinary Boot logging objects now undergo sanitization. Removing the exemption introduces a separate GELF regression below. |
| 3 | **Partly resolved** | The platform formatter sanitizes original MDC names correctly. Boot `logstash` still leaks `pwd` with prefix `ctx_`: Boot produces `ctx_pwd`, but `substring(prefix.length() + 1)` checks `wd`. GELF’s leading underscore also defeats the new prefix check. Match Boot’s actual joining rules and test both cases. |
| 4 | **Partly resolved** | The tested `Long` Emirates ID and configured `NaN` pattern are fixed. An integral `double`, such as `784199012345671d`, still passes: its text is `7.84199012345671E14`, which the Emirates-ID pattern cannot recognize. Check a bounded canonical integer representation as well; preserve the fractional-number exception. |
| 5 | **Resolved — documented limit accepted** | Logback prepares the original event before encoding. The formatter cannot bound that preceding pass. The documentation now distinguishes this limitation and recommends bounded key-value pairs. This accepts the limitation; it does not eliminate the runtime risk. |
| 6 | **Partly resolved** | Raw-name duplicates now merge deterministically. Collisions introduced by prefixing remain: prefix `stack` plus MDC key `trace` produces `stack_trace`, colliding when the event contains an exception. Collision handling must use effective output names. |
| 7 | **Resolved** | All four outage checks enforce the supplied p99 bound. An in-memory probe of `checks.py` rejects the original ten-second case. The one-second floor explicitly permits exceeding both relative tolerances for fast baselines. |
| 8 | **Resolved** | The carry-over table correctly distinguishes AC11’s collector-receipt proof from exporter-bean creation. |
| 9 | **Resolved** | The chapter uses `management.opentelemetry.tracing.export.otlp.endpoint`. |
| 10 | **Resolved** | The cited chapter and operational guidance now describe unauthenticated management access and actual exposure defaults. |
| 11 | **Resolved** | The chapter’s exercise, hints and monitoring advice now distinguish pod-local readiness from dependency health. |
| 12 | **Resolved** | The primer removes logging customizers from bean discovery and identifies the startup configuration property. |
| 13 | **Resolved** | The commands select existing modules, and the consumer setup names Boot’s REST-client starter. |
| 14 | **Resolved** | The cited recommendations no longer require the removed platform types. |
| 15 | **Resolved** | The chapter describes formatter member selection and direct `service` resolution. |
| 16 | **Resolved** | The exercise checks key-value pairs or encoded JSON. |
| 17 | **Resolved** | The operational description specifies HTTP 503, `DC-DATA-0503` and `retryable: true`. |
| 18 | **Resolved** | The chapter distinguishes recoverable rendering failures from invalid patterns that fail startup. |
| 19 | **Resolved** | The logging page identifies the tracer prerequisite. |

The new issues are:

1. **High — Extra-pattern masking can be bypassed with literal mask text.**  
   [PlatformValueMasker.java:309](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2b-1448/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:309)

   **Failure:** Configure `CASE-\S+` and log `CASE-****123456`. Previously the entire value matched and became `****`. Now the regex sees separate strings `CASE-` and `123456`; neither matches, so the complete input survives. Literal `****` is treated as trusted masking metadata. Splitting also changes anchors and lookarounds and prevents matches spanning a replacement produced by an earlier pattern.

   **Fix:** Preserve whole-input regex semantics. Track sanitized values or replacement spans explicitly instead of recognizing provenance from four literal asterisks. Add cases containing literal masks and matches spanning earlier replacements.

2. **High — Empty-matching patterns cause exponential expansion inside the new segmentation loop.**  
   [PlatformValueMasker.java:308](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2b-1448/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:308)

   **Failure:** `^` matches each empty substring around existing masks. Starting with `****`, successive applications produce lengths **12, 28, 60…**. Twenty configured `^` patterns produce **8,388,604 characters**, compared with 84 previously. Preliminary context sanitization followed by JSON-member processing repeats the expansion and can exhaust memory. The node budget does not constrain this string growth.

   **Fix:** Skip zero-length replacements or reject unsupported empty-matching patterns, and ensure already-sanitized scalar values are not processed repeatedly. Test empty matches and multiple configured patterns; `\S+` alone misses this case.

3. **Medium — GELF’s timestamp becomes `"****"`.**  
   [PlatformValueMasker.java:243](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2b-1448/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:243)

   **Failure:** Select Boot’s `gelf` format with redaction enabled. Boot’s `GraylogExtendedLogFormatStructuredLogFormatter.formatTimeStamp()` produces a deferred `WritableJson` containing numeric epoch seconds. Removing the package exemption sends this formatter-owned value through raw-JSON masking, producing `"timestamp":"****"`. This breaks GELF’s numeric timestamp contract and can cause ingestion failure. The existing alternative-format test only checks that the JSON is nonempty.

   **Fix:** Recognize this specific formatter-owned writer at its required position without restoring the broad package exemption. Assert that GELF’s timestamp remains numeric while ordinary Boot objects and application-supplied raw JSON remain masked.

4. **Medium — Collision filtering now silently discards noncolliding prefixed context fields.**  
   [PlatformLogstashFormatter.java:114](C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2b-1448/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:114)

   **Failure:** Configure `context.prefix=ctx` and log a context field named `service` or `message`. Previously it appeared as `ctx_service` or `ctx_message`, safely alongside the layout field. The new unconditional removal deletes it before prefixing, losing legitimate context.

   **Fix:** Sanitize under original names, then resolve collisions using effective output names. Preserve safely prefixed fields and reject actual output-name collisions; this also closes the remaining gap in finding 6.
