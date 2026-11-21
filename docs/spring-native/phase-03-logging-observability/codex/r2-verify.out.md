Reviewed `8c3efef..0d0dcf3` read-only. **Five new issues found.** I did not rerun the reported 174 tests.

| Item | Verdict | Assessment |
|---|---|---|
| Original 1 | **Partly resolved** | `SafeText` prevents repeated processing of ordinary sanitized text, but messages lose that marker, and the pattern loop can still expand exponentially. |
| Original 3 | **Resolved** | The prefix handling covers Boot’s joining rule and GELF’s leading underscore. |
| Original 4 | **Resolved** | The reported integral-double Emirates ID is checked as plain digits. A separate regression affects configured patterns below. |
| Original 6 | **Partly resolved** | Layout collisions use written names, but collisions between two context entries still use raw names. |
| Previous new 1 | **Resolved** | Whole-text matching fixes the literal-mask bypass and restores anchors and lookarounds. |
| Previous new 2 | **Partly resolved** | The twenty-`^` case is fixed, but other configured patterns still cause exponential expansion. |
| Previous new 3 | **Partly resolved** | GELF’s timestamp survives, but the exemption also admits application-supplied raw JSON. |
| Previous new 4 | **Resolved** | Noncolliding `ctx_service` and `ctx_message` entries are preserved. |

The remaining original-6 gap is **Medium**, at `PlatformLogstashFormatter.java:119–121`: with prefix `ctx`, context keys `foo` and `_foo` both become `ctx_foo`. Both survive filtering, leaving a duplicate output name. Merge context entries by their joined names with deterministic precedence before writing them. This gap predates this diff.

The new issues are:

1. **Medium — Message masking discards its safety marker.**  
   [PlatformLogstashFormatter.java:101](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2v-1508/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:101)

   **Failure:** `.toString()` converts the final `SafeText` back into an ordinary string. Boot’s processor therefore masks it again. With extra pattern `^`, template `"{}"` and argument `"x"`, the message receives three passes and becomes twelve asterisks followed by `x`, rather than the intended eight. Expanding patterns amplify the extra work.

   **Fix:** Return the masked object directly, changing `message()`’s return type to accommodate `SafeText`. Test the exact encoded message.

2. **High — Sequential extra patterns can exponentially expand replacement text.**  
   [PlatformValueMasker.java:304](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2v-1508/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:304)

   **Failure:** Configure repeated `\S` patterns and log `"x"`. Each pattern replaces every asterisk produced by its predecessor with four more: lengths become 4, 16, 64, … . Sixteen patterns demand over four billion characters within a single masking pass. `SafeText` is created only afterward; the node budget does not help. The previous segmentation implementation avoided this particular expansion.

   **Fix:** Bound intermediate text growth before allocating replacements, failing closed to a single mask when the bound would be exceeded. Apply the same protection to `extraOnly()`.

3. **High — Configuring GELF exempts arbitrary raw JSON under `timestamp`.**  
   [PlatformValueMasker.java:266](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2v-1508/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:266)

   **Failure:** Configure console `logstash` and file `gelf`. Log a key-value pair named `timestamp` whose value is an application `WritableJson` containing `{"password":"hunter2"}`. The shared format-name exemption accepts that writer in the Logstash output, allowing its raw JSON to bypass masking. The check establishes only the root name and interface, not Boot ownership.

   **Fix:** Recognize the specific formatter-owned timestamp writer at its exact position. Do not grant other formatters a name-based exemption merely because another destination uses GELF.

4. **High — Plain-digit checking replaces matching against the number’s actual representation.**  
   [PlatformValueMasker.java:516](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2v-1508/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:516)

   **Failure:** Configure extra pattern `^123\.0$` and log numeric `123.0d`. Previously its textual representation matched and was masked. Now only `"123"` is checked; the unchanged double is returned and written as `123.0`, exposing a value the configured rule explicitly masks.

   **Fix:** Check canonical digits **in addition to** the original representation. Preserve configured-pattern matching against the representation actually written.

5. **Medium — A null message with arguments now throws.**  
   [PlatformLogstashFormatter.java:101](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3r2v-1508/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformLogstashFormatter.java:101)

   **Failure:** With redaction enabled, `logger.info((String) null, "x")` reaches this branch. SLF4J’s `basicArrayFormat()` returns null; `mask(null, null)` also returns null. The newly added `.toString()` throws `NullPointerException`, preventing normal encoding of the event.

   **Fix:** Return the nullable masked object directly and add a null-template-with-arguments regression test.
