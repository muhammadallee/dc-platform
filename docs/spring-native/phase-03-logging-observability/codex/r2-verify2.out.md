Reviewed `0d0dcf3..7b4aec8` read-only. **Two new issues found.** I did not rerun the reported 178 tests.

| Item | Verdict | Assessment |
|---|---|---|
| New 1 | **resolved** | Returning `SafeText` preserves the marker and prevents the processor’s third pass. |
| New 2 | **resolved** | Under the narrowed contract, startup rejection and runtime growth checks stop exponential expansion across patterns. |
| New 3 | **resolved** | Under the documented narrowed contract, mixed formats receive no deferred-object exemptions. |
| New 4 | **partly resolved** | Written-form matching is restored, but a match can skip the plain-digit check entirely. |
| New 5 | **resolved** | Returning the nullable masked value removes the null-template dereference. |
| 6 (rest) | **partly resolved** | Duplicate written names are eliminated, but the later entry does not always win. |

The new issues are:

1. **High — Written-form masking can bypass Emirates-ID masking.**  
   [PlatformValueMasker.java:524](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2v2-1520/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:524)

   **Failure:** Configure extra pattern `E14$` and log numeric `784199012345671d`. Its written form, `7.84199012345671E14`, becomes `7.84199012345671****`. Because `written != value`, the plain-digit check never runs. All identifier digits remain visible; previously the plain-digit Emirates-ID match masked the whole identifier.

   **Fix:** Evaluate both representations independently. A plain-digit sensitive-identifier match must take precedence over partial masking of the written representation. Add this combined-pattern regression test.

2. **Medium — Collision resolution uses first-insertion order rather than latest-entry order.**  
   [PlatformLogstashFormatter.java:124](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2v2-1520/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:124)

   **Failure:** With prefix `ctx`, log ordered key-value pairs `foo=one`, `_foo=two`, `foo=three`. Updating `foo` in the initial `LinkedHashMap` preserves its original position. The collision loop then removes `foo` and writes `ctx_foo=two`, silently discarding the latest value, `three`.

   **Fix:** Resolve written-name collisions while consuming MDC entries followed by key-value pairs, preserving the latest occurrence. Alternatively, remove and reinsert raw keys on each update before resolving written-name collisions. Add the three-entry regression test.
