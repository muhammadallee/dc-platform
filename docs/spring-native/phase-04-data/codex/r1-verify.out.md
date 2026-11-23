| Item | Assessment | Reason |
|---|---|---|
| **2 (rest): text-like JDK proxies** | **Resolved** | `opaque()` now guards the text shortcuts, nested walk and map-key classification. Ordinary `CharSequence` and `Path` proxies reach placeholders without invoking their handlers. |
| **3 (rest): application scalars** | **Partly resolved** | The supplied application classes are handled correctly, but `jdkClass()` trusts package names rather than JDK provenance. |
| **New 1: throwing entry accessors** | **Partly resolved** | `RuntimeException` and `LinkageError` are contained locally; other nonfatal failures still discard the enclosing structure. |
| **New 2: floating-point keys** | **Resolved** | Both exact `Float.class` and `Double.class` are now plain keys. |

Two remaining gaps in the fixes:

- **High — package prefixes do not establish JDK ownership.** [PlatformValueMasker.java:763](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1v-073229/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:763). An application can legally define `javax.example.Amount extends Number`, with a bean property holding an unloaded entity and a `toString()` that renders it. `jdkClass()` returns true, so the number shortcut still initializes that reference. An application exception in that namespace similarly bypasses its placeholder. **Fix:** establish actual JDK provenance or explicitly allow trusted concrete scalar classes; test application classes in an allowed-looking namespace.

- **Medium — entry failure containment remains narrower than other accessor guards.** [PlatformValueMasker.java:512](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1v-073229/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:512), also line 525. If either accessor throws `AssertionError`, `sanitize("outer", List.of("keep-me", entry))` still becomes entirely `[unrenderable]`. **Fix:** match `read()`/`guarded()` failure handling, including their fatal-error policy; test both accessors.

**New issues introduced by this diff:**

- **Medium — failed map entries bypass the traversal budget.** [PlatformValueMasker.java:512](C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1v-073229/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:512), also line 525. Both new catch branches return without decrementing `budget`. The map loop at line 451 relies on that budget to stop fetching entries. A custom map yielding entries whose `getKey()` or `getValue()` repeatedly throws can therefore exceed 10,000 entries—or hang the logging thread with an endless iterator. Previously, the first such failure terminated sanitization. **Fix:** charge every map entry before accessing it, including failed entries, and adjust existing charges consistently. Verify that repeatedly failing entries stop at the configured bound.

No other new issues established in this diff. This was a read-only source review of `9ad0b15..769e513`; I did not independently rerun the reported tests or reactor verification.
