# Verification pass 3 — DC Platform hardening Phase 2 (Logging + Observability)

Your working directory is a checkout of commit `7a7c962`. Your previous verification pass on `f5ce14c`
(below) accepted R2-F4, R2-F5, R2-F8 (+ stderr), AC6, previous regressions 1–4 and the banner change
(D108), and rejected AC5, the scheme-scan overlap change and the compound-credential-name change,
listing three regressions. The diff below addresses them:

- `Bearer bearer` / `Bearer BEARER`: a token equal to the scheme word is skipped only when another
  scheme + token follows it (`bearer Bearer <tok>` and `basic Basic <cred>` keep masking the real one).
- `sessionCookie=abc123` and `{"sessionCookie":"sid=abc123"}`: `cookie` added to the text key suffixes.
- Depth cutoff: raised from 16 to 64 with identity-based cycle detection (fail closed beyond 64 or on a
  cycle; D102 addendum). Your regression 2 input (`wrap(1793001600123L, 16)`) now stays readable.
- Status-manager leak: the message view renders every Logstash `SingleFieldAppendingMarker` and
  `ObjectFieldsAppendingMarker` (`fields(bean)`) itself, mirroring `StructuredArguments.toString`
  without its catch, so a throwing `toString` reaches the view's guard (`[unrenderable]`) instead of a
  `WarnStatus`. `docs/llms-full.txt` regenerated.

For EACH of: AC5, scheme-scan overlap change, compound credential names, regressions 1–3 from your
previous pass, state **accept** or **reject** with file:line and a concrete failing input for any
reject. Report any regression this diff introduces (R3.3 common-value false positives, linearity, stack
safety, leaks, message rendering changes for unchanged markers), to the same evidence standard. Output
a table `| Item | Verdict | Reason |`, then `## Regressions` (or "none established"), then
`## Verification boundary`.

## Your previous verification pass

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

## Fix diff f5ce14c..7a7c962 (docs/production-readiness and the tooling/hardening consumer harness excluded; PlatformValueMaskerTest change = 8334836, timing assertion → scaling)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 8eebde2..258ac9b 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -49,7 +49,9 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   addresses and phone numbers become `****` (D102). Turn off with `dc.platform.logging.redaction.enabled=false`.
 - `LogSanitizer` beans are now applied to MDC values and structured arguments, as documented: strings,
   numbers, maps, collections and arrays, in the JSON field and in the formatted `message` alike.
-  Credential-keyed arguments read `password=****` in `message`.
+  Credential-keyed arguments read `password=****` in `message`; values nested deeper than 64 levels,
+  or cyclic, read `****` there. A value whose `toString()` throws reads `[unrenderable]`, and its
+  exception is no longer reported to stderr or the Logback status manager.
 - `platform.capability.active` is now registered when Boot creates the meter registry (the
   auto-configuration was evaluated before Boot's registry existed and backed off).
 - The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index 4870bd6..1dadfdb 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -681,6 +681,10 @@ One line of context per decision; details live in the commit bodies referenced.
   ending in `password`, `passwd`, `secret`, `token`, `apikey`, `authorization` or `cookie` (ignoring
   case, `-`, `_`) is a credential too: `sessionToken`, `x-auth-token`, `db_password`. The consumer run
   showed an MDC key `session-token` written in clear; over-masking a `…Token` field is the safer error.
+  In text, `sessionCookie=…` masks its value like any credential key; only the exact `Cookie:` /
+  `Set-Cookie:` header masks to the end of the line. The message view traverses structured values to
+  64 levels and masks anything deeper or cyclic (fail closed): deep enough that ordinary payloads stay
+  readable, bounded so a hostile value cannot exhaust the stack (verification pass 2).
 - **D107 — console log format stays Boot's (hardening phase 2, A1, owner accepted 2026-10-26).** The
   `console` format (the `local` profile) is human-only: it is not masked and a CR/LF in a message can
   split a line. Overriding Boot's console pattern would fork its styling; JSON, used in every deployed
diff --git a/docs/llms-full.txt b/docs/llms-full.txt
index 927269a..3d7551a 100644
--- a/docs/llms-full.txt
+++ b/docs/llms-full.txt
@@ -800,13 +800,16 @@ is watching.
     around it allowed); whatever follows `Bearer` (so prose such as "Bearer token missing" reads
     "Bearer **** missing"); `Basic` credentials that decode to `user:password`;
   - the value after a credential key (`password`, `passwd`, `pwd`, `secret`, `client_secret`, `token`,
-    `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`) in `key=value`,
+    `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`, and compound keys
+    ending in one of them or in `cookie`: `session_token`, `db-password`, `smtpSecret`, `sessionCookie`)
+    in `key=value`,
     `key: value`, `"key":"value"` and `key='value'` text — quoted values whole (escapes included, an
     unterminated quote to the end), quotes kept;
   - everything after `Cookie:` / `Set-Cookie:` up to the end of the line (all pairs, quoted ones too);
   - the whole value — of any type (string, number, boolean, `byte[]`, object, array) — of an MDC entry or
     structured-argument field, at any depth, whose **name** is one of those keys or `cookie` /
-    `set-cookie`; in the formatted `message` such an argument reads `password=****` (`****` for a
+    `set-cookie`, or ends in one of them ignoring case, `-` and `_` (`sessionToken`, `x-auth-token`,
+    `db_password`); in the formatted `message` such an argument reads `password=****` (`****` for a
     value-only `v(...)`);
   - Emirates ID numbers (`784-YYYY-NNNNNNN-C`, dashes optional) and Luhn-valid card numbers starting
     2–6: 13–19 contiguous digits, or the printed layouts `4111 1111 1111 1111` (optionally `+1..3`
@@ -826,7 +829,8 @@ is watching.
   them; a card number your code logs as a top-level field named `traceId`/`spanId` that is also a
   16-character hex string is not masked.
 - **No forged lines** — JSON string escaping keeps CR/LF, quotes and embedded JSON inside one field, so
-  user input cannot add a line or a field.
+  user input cannot add a line or a field. In JSON mode Boot's startup banner is off
+  (`spring.main.banner-mode=off`, overridable), so every stdout line is one JSON object.
 - **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
 - **`LogSanitizer` beans** — your own scrubbing, applied in `@Order` before the platform masks run, to
   MDC values and to keyed arguments — `Kv.of`, Logstash `kv(...)`/`v(...)`/`value(...)` and
@@ -835,7 +839,10 @@ is watching.
   arrays, nested in any combination; each value is sanitized under its own key (map entries under
   theirs, array elements under the enclosing field's). Other objects (beans) are sanitized field by
   field in the JSON output only; in `message` they keep their `toString()`, over which the platform
-  masks still run. An argument whose rendering throws reads `[unrenderable]` in `message`.
+  masks still run (`fields(bean)` likewise). In `message`, a value nested more than 64 levels deep, or
+  a container that contains itself, reads `****`. An argument whose rendering throws reads
+  `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
+  status).
 
 ## Starter coordinates
 
@@ -4384,6 +4391,18 @@ One line of context per decision; details live in the commit bodies referenced.
   (international with `+`/`00`, UAE mobiles in local form; owner request 2026-10-26) become `****`. Extendable with
   `dc.platform.logging.redaction.patterns`; `LogSanitizer` beans run first, the platform patterns last.
   Implemented as a logstash `ValueMasker`, so it covers message, MDC, arguments and stack traces alike.
+- **D108 — no startup banner in JSON mode (hardening phase 2, Gate F trial).** The ASCII banner is
+  written to stdout outside logging, so a log shipper parsing stdout line by line saw non-JSON lines.
+  The logging EPP defaults `spring.main.banner-mode=off` whenever the JSON format is resolved
+  (lowest precedence; `console` or `log` set by the service wins). Console format keeps Boot's banner.
+- **D102 addendum — compound credential names (hardening phase 2, Gate F trial).** A field or text key
+  ending in `password`, `passwd`, `secret`, `token`, `apikey`, `authorization` or `cookie` (ignoring
+  case, `-`, `_`) is a credential too: `sessionToken`, `x-auth-token`, `db_password`. The consumer run
+  showed an MDC key `session-token` written in clear; over-masking a `…Token` field is the safer error.
+  In text, `sessionCookie=…` masks its value like any credential key; only the exact `Cookie:` /
+  `Set-Cookie:` header masks to the end of the line. The message view traverses structured values to
+  64 levels and masks anything deeper or cyclic (fail closed): deep enough that ordinary payloads stay
+  readable, bounded so a hostile value cannot exhaust the stack (verification pass 2).
 - **D107 — console log format stays Boot's (hardening phase 2, A1, owner accepted 2026-10-26).** The
   `console` format (the `local` profile) is human-only: it is not masked and a CR/LF in a message can
   split a line. Overriding Boot's console pattern would fork its styling; JSON, used in every deployed
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index 3d5e59c..1ffb838 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -21,7 +21,8 @@ is watching.
     "Bearer **** missing"); `Basic` credentials that decode to `user:password`;
   - the value after a credential key (`password`, `passwd`, `pwd`, `secret`, `client_secret`, `token`,
     `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`, and compound keys
-    ending in one of them: `session_token`, `db-password`, `smtpSecret`) in `key=value`,
+    ending in one of them or in `cookie`: `session_token`, `db-password`, `smtpSecret`, `sessionCookie`)
+    in `key=value`,
     `key: value`, `"key":"value"` and `key='value'` text — quoted values whole (escapes included, an
     unterminated quote to the end), quotes kept;
   - everything after `Cookie:` / `Set-Cookie:` up to the end of the line (all pairs, quoted ones too);
@@ -58,7 +59,10 @@ is watching.
   arrays, nested in any combination; each value is sanitized under its own key (map entries under
   theirs, array elements under the enclosing field's). Other objects (beans) are sanitized field by
   field in the JSON output only; in `message` they keep their `toString()`, over which the platform
-  masks still run. An argument whose rendering throws reads `[unrenderable]` in `message`.
+  masks still run (`fields(bean)` likewise). In `message`, a value nested more than 64 levels deep, or
+  a container that contains itself, reads `****`. An argument whose rendering throws reads
+  `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
+  status).
 
 ## Starter coordinates
 
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
index 667828f..b740799 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
@@ -69,10 +69,11 @@ public final class PlatformValueMasker implements ValueMasker {
     private static final String QUOTED = "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+(?:\"|\\z)"
             + "|'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+(?:'|\\z)";
     // key=value, key: value, "key":"value", key='value'; quoted values masked whole, quotes kept. The key
-    // may be compound (session_token=, db-password:, smtpSecret=); the prefix is lazy and starts a token,
-    // so scanning stays linear.
+    // may be compound (session_token=, db-password:, smtpSecret=, sessionCookie=); the prefix is lazy and
+    // starts a token, so scanning stays linear. A bare cookie key here masks its first value; the cookie
+    // patterns below then take a header's remaining pairs.
     private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![\\w-])([A-Za-z0-9_-]*?(?:password|passwd|pwd"
-            + "|secret|token|api[-_]?key|authorization))"
+            + "|secret|token|api[-_]?key|authorization|cookie))"
             + "(\"?\\s*[:=]\\s*)(" + QUOTED + "|(?:(?:Bearer|Basic)\\s+)?[^\\s\"'&,;}]+)");
     // A quoted key ("cookie":"…", "cookie"="…", "cookie":12345678): the value goes — a quoted one whole,
     // a bare one up to the next , } or line end. An unquoted key (Cookie: … header text, several pairs,
@@ -152,8 +153,8 @@ public final class PlatformValueMasker implements ValueMasker {
     String maskPatterns(String text) {
         String result = maskJwts(text);
         // RFC 6750 tokens may be letters only: whatever follows "Bearer" goes, prose included.
-        result = schemeMask(result, BEARER, token -> !"bearer".equalsIgnoreCase(token));
-        result = schemeMask(result, BASIC, token -> !"basic".equalsIgnoreCase(token) && basicCredential(token));
+        result = schemeMask(result, BEARER, token -> true);
+        result = schemeMask(result, BASIC, PlatformValueMasker::basicCredential);
         result = replace(result, KEY_VALUE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
         result = replace(result, COOKIE_FIELD, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
         result = replace(result, COOKIE_HEADER, m -> m.group(1) + m.group(2) + MASK);
@@ -357,12 +358,17 @@ public final class PlatformValueMasker implements ValueMasker {
     // scheme and its whitespace.
     private static String schemeMask(String text, Pattern scheme, Predicate<String> isCredential) {
         Matcher matcher = scheme.matcher(text);
+        Matcher next = scheme.matcher(text).useTransparentBounds(true).useAnchoringBounds(false);
         StringBuilder out = null;
         int copied = 0;
         int from = 0;
         while (from <= text.length() && matcher.find(from)) {
             String token = matcher.group(3);
-            if (isCredential.test(token)) {
+            // "bearer Bearer <token>": the scheme word is skipped only when a scheme + token follows it;
+            // "Bearer bearer" alone is a credential like any other.
+            boolean schemeWord = token.equalsIgnoreCase(matcher.group(1))
+                    && next.region(matcher.end(), text.length()).lookingAt();
+            if (!schemeWord && isCredential.test(token)) {
                 if (out == null) {
                     out = new StringBuilder(text.length());
                 }
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
index 8e64794..b1520a3 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -10,11 +10,14 @@ import java.text.MessageFormat;
 import java.util.ArrayList;
 import java.util.Arrays;
 import java.util.Collection;
+import java.util.Collections;
+import java.util.IdentityHashMap;
 import java.util.LinkedHashMap;
 import java.util.List;
 import java.util.Map;
-import net.logstash.logback.argument.StructuredArguments;
+import java.util.Set;
 import net.logstash.logback.marker.MapEntriesAppendingMarker;
+import net.logstash.logback.marker.ObjectFieldsAppendingMarker;
 import net.logstash.logback.marker.SingleFieldAppendingMarker;
 import org.slf4j.helpers.MessageFormatter;
 
@@ -27,8 +30,10 @@ import org.slf4j.helpers.MessageFormatter;
  * any combination, keyed as in the JSON output (an array element by its enclosing field). Other objects
  * keep their {@code toString}; the pattern masks still run over the whole message.
  *
- * <p>Values nested deeper than {@value #MAX_DEPTH} levels read {@code ****} in the message. Each structured
- * argument is rendered exactly once, under its own guard: one whose rendering throws becomes
+ * <p>Values nested deeper than {@value #MAX_DEPTH} levels, and a container that contains itself, read
+ * {@code ****} in the message. Each structured argument is rendered exactly once, here, under its own
+ * guard — never through Logstash's {@code StructuredArguments.toString}, which reports a throwing value's
+ * exception to the Logback status manager unmasked: one whose rendering throws becomes
  * {@value #UNRENDERABLE}, so neither the event nor a sibling's mask is lost and the exception text never
  * reaches a diagnostic channel. Returns an event-local view: the logged event and its argument array
  * are never mutated; when nothing changes, the original event is returned. Thread-safe.
@@ -37,10 +42,11 @@ public final class SanitizedMessageView {
 
     static final String UNRENDERABLE = "[unrenderable]";
 
-    private static final int MAX_DEPTH = 16;
+    private static final int MAX_DEPTH = 64;
     private static final Method FIELD_VALUE = accessor(SingleFieldAppendingMarker.class, "getFieldValue");
     private static final Field FORMAT_PATTERN = field(SingleFieldAppendingMarker.class, "messageFormatPattern");
     private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");
+    private static final Field OBJECT_FIELDS = field(ObjectFieldsAppendingMarker.class, "object");
 
     private SanitizedMessageView() {
     }
@@ -100,7 +106,7 @@ public final class SanitizedMessageView {
                 return kv.key() + "=" + PlatformValueMasker.MASK;
             }
             Object clean = sanitizedTree(kv.key(), kv.value(), 0);
-            return clean == kv.value() ? guardedToString(argument) : kv.key() + "=" + StructuredArguments.toString(clean);
+            return clean == kv.value() ? guardedToString(argument) : kv.key() + "=" + valueText(clean);
         }
         if (argument instanceof SingleFieldAppendingMarker marker) {
             String name = marker.getFieldName();
@@ -110,15 +116,19 @@ public final class SanitizedMessageView {
             if (FIELD_VALUE == null) {
                 return guardedToString(argument);
             }
-            Object value = read(FIELD_VALUE, marker);
-            Object clean = sanitizedTree(name, value, 0);
-            return clean == value ? guardedToString(argument) : render(marker, name, StructuredArguments.toString(clean));
+            // Rendered here even when unchanged: the marker's own toString goes through
+            // StructuredArguments.toString.
+            return render(marker, name, valueText(sanitizedTree(name, read(FIELD_VALUE, marker), 0)));
         }
         if (argument instanceof MapEntriesAppendingMarker entries && MAP_ENTRIES != null) {
             Object map = read(MAP_ENTRIES, entries);
             Object clean = sanitizedTree(null, map, 0);
             return clean == map ? guardedToString(argument) : String.valueOf(clean);
         }
+        if (argument instanceof ObjectFieldsAppendingMarker fields && OBJECT_FIELDS != null) {
+            // fields(bean): the JSON fields are sanitized one by one; the message keeps the bean's text.
+            return valueText(read(OBJECT_FIELDS, fields));
+        }
         return argument;
     }
 
@@ -128,41 +138,69 @@ public final class SanitizedMessageView {
         return String.valueOf(argument);
     }
 
+    // As StructuredArguments.toString (arrays by content), minus its catch: a throwing value reaches the
+    // caller's guard instead of the Logback status manager.
+    private static String valueText(Object value) {
+        return switch (value) {
+            case null -> "null";
+            case Object[] array -> Arrays.deepToString(array);
+            case byte[] array -> Arrays.toString(array);
+            case short[] array -> Arrays.toString(array);
+            case int[] array -> Arrays.toString(array);
+            case long[] array -> Arrays.toString(array);
+            case char[] array -> Arrays.toString(array);
+            case float[] array -> Arrays.toString(array);
+            case double[] array -> Arrays.toString(array);
+            case boolean[] array -> Arrays.toString(array);
+            default -> String.valueOf(value);
+        };
+    }
+
     /**
      * Applies credential masking and the {@code LogSanitizer} beans to a structured value: the same
      * instance when nothing changes, otherwise a sanitized copy (maps, collections and object arrays
      * become maps and lists).
      */
     static Object sanitizedTree(String key, Object value, int depth) {
+        return sanitizedTree(key, value, depth, Collections.newSetFromMap(new IdentityHashMap<>()));
+    }
+
+    // path: the containers being traversed, by identity, so a container holding itself ends the descent.
+    private static Object sanitizedTree(String key, Object value, int depth, Set<Object> path) {
         if (key != null && PlatformValueMasker.isCredentialKey(key)) {
             return PlatformValueMasker.MASK;
         }
         if (value == null) {
             return null;
         }
-        if (depth >= MAX_DEPTH) {
-            // Deeper than the traversal goes: shown masked rather than unchecked.
+        boolean container = value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof Object[];
+        if (depth >= MAX_DEPTH || container && path.contains(value)) {
+            // Deeper than the traversal goes, or a cycle: shown masked rather than unchecked.
             return PlatformValueMasker.MASK;
         }
         if (value instanceof Map<?, ?> map) {
+            path.add(value);
             Map<Object, Object> copy = new LinkedHashMap<>();
             boolean changed = false;
             for (Map.Entry<?, ?> entry : map.entrySet()) {
-                Object clean = sanitizedTree(String.valueOf(entry.getKey()), entry.getValue(), depth + 1);
+                Object clean = sanitizedTree(String.valueOf(entry.getKey()), entry.getValue(), depth + 1, path);
                 changed |= clean != entry.getValue();
                 copy.put(entry.getKey(), clean);
             }
+            path.remove(value);
             return changed ? copy : value;
         }
-        if (value instanceof Collection<?> || value instanceof Object[]) {
+        if (container) {
+            path.add(value);
             Iterable<?> elements = value instanceof Object[] array ? Arrays.asList(array) : (Collection<?>) value;
             List<Object> copy = new ArrayList<>();
             boolean changed = false;
             for (Object element : elements) {
-                Object clean = sanitizedTree(key, element, depth + 1);
+                Object clean = sanitizedTree(key, element, depth + 1, path);
                 changed |= clean != element;
                 copy.add(clean);
             }
+            path.remove(value);
             return changed ? copy : value;
         }
         if (key != null && (value instanceof CharSequence || value instanceof Number)
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
index 0d3f87f..6996df0 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
@@ -89,7 +89,59 @@ class EncoderRedactionTest {
                         "st-abcdef", "xa-1"),
                 row("credential 17 levels deep", "{}",
                         new Object[] {kv("payload", wrap(Map.of("password", "correct horse battery staple"), 16))},
-                        "horse", "staple"));
+                        "horse", "staple"),
+                row("credential below the traversal depth", "{}",
+                        new Object[] {kv("payload", wrap(Map.of("password", "correct horse battery staple"), 64))},
+                        "horse", "staple"),
+                // Verification pass 2: compound cookie names in text.
+                row("compound cookie key in text", "{}", new Object[] {"sessionCookie=abc123"}, "abc123"),
+                row("compound cookie JSON field", "{}", new Object[] {"{\"sessionCookie\":\"sid=abc123\"}"}, "abc123"));
+    }
+
+    @Test
+    void aSchemeWordIsItselfACredentialUnlessAnotherSchemeFollows() throws Exception {
+        // Verification pass 2: the overlap repair must not exempt "Bearer bearer".
+        assertThat(message("Bearer bearer")).isEqualTo("Bearer ****");
+        assertThat(message("Bearer BEARER")).isEqualTo("Bearer ****");
+        assertThat(message("bearer Bearer abcdefgh12345678")).isEqualTo("bearer Bearer ****");
+        assertThat(message("basic Basic YWxpY2U6czNjcjN0LTJiMDgwMA==")).isEqualTo("basic Basic ****");
+    }
+
+    private String message(String text) throws Exception {
+        return JSON.readTree(encode(event("{}", new Object[] {text}))).get("message").asText();
+    }
+
+    @Test
+    void ordinaryValuesNestedWithinTheTraversalDepthStayReadable() throws Exception {
+        // Verification pass 2: R3.3 common values are not masked merely for being nested.
+        String line = encode(event("{}", new Object[] {kv("payload", wrap(1793001600123L, 32))}));
+        assertThat(JSON.readTree(line).get("message").asText()).contains("1793001600123");
+    }
+
+    @Test
+    void aThrowingValueInsideALogstashMarkerNeverReachesTheStatusManager() throws Exception {
+        // Verification pass 2: StructuredArguments.toString reports the exception, unmasked, as a status.
+        HostileBean bad = new HostileBean();
+        int before = context.getStatusManager().getCount();
+        String line = encode(event("{} {} {}", new Object[] {
+                kv("items", new Object[] {null, bad}), kv("entity", bad), net.logstash.logback.argument.StructuredArguments.fields(bad)}));
+        assertThat(JSON.readTree(line).get("message").asText())
+                .isEqualTo("[unrenderable] [unrenderable] [unrenderable]");
+        assertThat(context.getStatusManager().getCopyOfStatusList().subList(before, context.getStatusManager().getCount()))
+                .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
+    }
+
+    /** Serializable as JSON ({@code {"id":"b-1"}}); its toString throws with a secret in the message. */
+    public static final class HostileBean {
+
+        public String getId() {
+            return "b-1";
+        }
+
+        @Override
+        public String toString() {
+            throw new IllegalStateException("password=hunter5");
+        }
     }
 
     private static Object wrap(Object value, int times) {
@@ -103,7 +155,7 @@ class EncoderRedactionTest {
     @Test
     void aSanitizedValueBelowTheTraversalDepthIsNotShownInTheMessage() {
         publish((key, value) -> "customerName".equals(key) ? "C***" : value);
-        String line = encode(event("{}", new Object[] {kv("customerName", wrap("Fatima", 16))}));
+        String line = encode(event("{}", new Object[] {kv("customerName", wrap("Fatima", 64))}));
         assertThat(line).doesNotContain("Fatima");
     }
 
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
index 4a2bbc1..8c54b81 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
@@ -183,14 +183,24 @@ class PlatformValueMaskerTest {
     // Round 1 F6: long inputs are scanned in linear time.
     @Test
     void longInputsWithoutMatchesAreScannedQuickly() {
-        String noAt = "a".repeat(200_000);
-        String trailingAt = "a.".repeat(100_000) + "@";
-        String digits = "1".repeat(200_000);
+        // Scaling, not a wall-clock budget (shared CI runners vary): 4x the input must cost far less than
+        // the 16x a quadratic scan would. The absolute ceiling only catches a pathological case.
+        scanTime(50_000); // warm-up (JIT)
+        long small = Math.max(scanTime(50_000), Duration.ofMillis(1).toNanos());
+        long large = scanTime(200_000);
+        assertThat(large).as("4x input: %d ns vs %d ns", large, small).isLessThan(8 * small);
+        assertThat(Duration.ofNanos(large)).isLessThan(Duration.ofSeconds(5));
+    }
+
+    private long scanTime(int length) {
+        String noAt = "a".repeat(length);
+        String trailingAt = "a.".repeat(length / 2) + "@";
+        String digits = "1".repeat(length);
         long start = System.nanoTime();
         assertThat(masker.maskPatterns(noAt)).isEqualTo(noAt);
         assertThat(masker.maskPatterns(trailingAt)).isEqualTo(trailingAt);
         assertThat(masker.maskPatterns(digits)).isEqualTo(digits);
-        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(750));
+        return System.nanoTime() - start;
     }
 
     @Test
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageViewTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageViewTest.java
new file mode 100644
index 0000000..18e491e
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageViewTest.java
@@ -0,0 +1,30 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import java.util.ArrayList;
+import java.util.LinkedHashMap;
+import java.util.List;
+import java.util.Map;
+import org.junit.jupiter.api.Test;
+
+class SanitizedMessageViewTest {
+
+    @Test
+    void aContainerHoldingItselfEndsTheDescentMasked() {
+        List<Object> list = new ArrayList<>();
+        list.add("ok");
+        list.add(list);
+        list.add(list);
+        Object clean = SanitizedMessageView.sanitizedTree("payload", list, 0);
+        assertThat(clean).isEqualTo(List.of("ok", PlatformValueMasker.MASK, PlatformValueMasker.MASK));
+    }
+
+    @Test
+    void aSharedButAcyclicValueIsTraversedAtEachPlace() {
+        Map<String, Object> shared = new LinkedHashMap<>();
+        shared.put("password", "hunter2");
+        Object clean = SanitizedMessageView.sanitizedTree("payload", List.of(shared, shared), 0);
+        assertThat(clean).isEqualTo(List.of(Map.of("password", "****"), Map.of("password", "****")));
+    }
+}
```
