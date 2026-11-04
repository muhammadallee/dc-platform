# Verification pass 2 — DC Platform hardening Phase 2 (Logging + Observability)

Your working directory is a checkout of commit `f5ce14c`. Your previous verification pass on `e1d2842`
(below) accepted R2-F1–F3, F6, F7, F9, F10, R5.1/AC7 and R7.2, and rejected R2-F4, R2-F5, R2-F8 (+ stderr),
AC5 and AC6, listing four regressions. The commit below addresses them; it also contains three changes
from the first consumer-harness trial run (scheme-scan overlap, compound credential names, banner off in
JSON mode — decision D108). For EACH of R2-F4, R2-F5, R2-F8, the stderr concern, AC5, AC6, regressions
1–4, and the three trial-run changes, state **accept** or **reject** (file:line and a concrete failing
input). Report any regression this diff introduces (false positives on R3.3 common values — note that
compound credential names now mask `…Token`/`…Password`/`…Secret` fields whole —, linearity, stack
safety, leaks), with the same evidence standard. Output a table `| Item | Verdict | Reason |`, then
`## Regressions` (or "none established"), then `## Verification boundary`.

## Your previous verification pass
| Item | Verdict | Reason |
|---|---|---|
| R2-F1 | **accept** | Both quote styles now use possessive, unrolled repetition. The reported length-dependent regex recursion is removed. `PlatformValueMasker.java:63`. Encoder tests cover long plain, escaped and unterminated values. |
| R2-F2 | **accept** | Raw cookie headers now consume embedded quotes and subsequent pairs through the line ending. `PlatformValueMasker.java:73`. The reported input is covered; a separate cookie regression is listed below. |
| R2-F3 | **accept** | `PlatformJsonEncoder.java:79` installs `CredentialFieldMasker`; its whole-field replacement covers boolean, binary and container serialization. Confirmed against encoder 8.1’s masking-generator bytecode. |
| R2-F4 | **reject** | Direct credential arguments are fixed, but [SanitizedMessageView.java:134][depth] returns deeper containers unchanged. Wrap `Map.of("password", "correct horse battery staple")` in **16 successive `List.of(...)` calls**, then log `kv("payload", value)` through `"{}"`. The message retains `horse battery staple`: traversal never reaches the password entry, and subsequent text masking removes only `correct`. |
| R2-F5 | **reject** | The same depth cutoff breaks the published nested-value contract. With the `customerName → C***` sanitizer, wrap `"Fatima"` in **16 successive `List.of(...)` calls**, then log `kv("customerName", value)` through `"{}"`. The JSON descendant is sanitized, but `message` retains `Fatima`. [SanitizedMessageView.java:134][depth]; `PlatformValueMasker.java:137`. |
| R2-F6 | **accept** | Unconditional reserved-name pattern exemptions are removed. Credential ancestors retain precedence; only top-level W3C-shaped trace/span values remain exempt, as accepted. `PlatformValueMasker.java:112` and `:133`. |
| R2-F7 | **accept** | Header decoding and JSON-whitespace trimming recognize the reported leading-whitespace JWT. `PlatformValueMasker.java:287`. This remains the accepted outer-brace heuristic. |
| R2-F8 | **reject** | The guard excludes `AssertionError` and `StackOverflowError`. With no sanitizers, `Kv.of("entity", objectWhoseToStringThrowsAssertionError)` now throws before encoding; previously SLF4J caught that throwable and supplied its diagnostic placeholder. [SanitizedMessageView.java:65][guard] and `:121`. |
| R2-F8 — stderr concern | **reject** | `guardedToString()` discards the rendered string and returns the original argument, allowing another invocation outside the guard. A fresh event containing a `Kv` value whose first rendering succeeds and second throws `IllegalStateException("password=hunter2")` reaches SLF4J’s unmasked `Reporter.error`. [SanitizedMessageView.java:120][render]; concrete reproduction below. |
| R2-F9 | **accept** | Decimal digit count uses `long` arithmetic before integer expansion; large `BigInteger` values are rejected before decimal conversion. `PlatformValueMasker.java:223`. The reported `1E+100000000` no longer reaches `toBigInteger()`. |
| R2-F10 | **accept** | Ordering now follows metrics and composite-registry configuration. `CapabilityMetricsAutoConfiguration.java:26`. Boot 4.1’s cached metadata confirms Prometheus and simple-registry configurations precede composite selection. |
| AC5 / R3 | **reject** | The remaining structured-message credential leak under R2-F4 and the new cookie/diagnostic leaks below prevent acceptance. |
| AC6 | **reject** | R2-F5’s concrete nested-list input remains inconsistent between fields and message. Null-containing object arrays also regress, as detailed below. |
| R5.1 / AC7 | **accept** | The ordering fix is accompanied by an explicit `platform_capability_active{` scrape assertion in a context without a supplied registry bean. `PlatformEndpointWebTest.java:65`. |
| R7.2 docs, including generated output | **accept** | Exposure notes are present in chapters 7:1022, 9:832, 11:937 and 13:922. [docs/llms-full.txt:794][mdc-doc] now describes W3C MDC fields; [line 1063][exposure-doc] gives `health,info,prometheus` as the exposure default. |

## Regressions

1. **Previously masked cookie forms now leak.** At [PlatformValueMasker.java:71][cookie], `COOKIE_FIELD` requires a colon and quoted value; `COOKIE_HEADER` cannot cross the closing quote of a quoted key. Consequently, these message strings remain visible:

   ```text
   "cookie"="sid=abc123"
   {"cookie":12345678}
   ```

   Both matched the previous cookie expression. Neither matches either replacement expression, and the other masks do not remove these values. This was also checked with extracted expressions in Python.

2. **Extra rendering creates a new diagnostic leak.** Using the fresh-event helpers from `EncoderRedactionTest`, with no sanitizers:

   ```java
   Object flaky = new Object() {
       int calls;

       public String toString() {
           if (++calls == 1) return "ok";
           throw new IllegalStateException("password=hunter2");
       }
   };
   encode(event("{}", new Object[] {Kv.of("entity", flaky)}));
   ```

   [SanitizedMessageView.java:121][render] consumes the successful call but returns the object. Subsequent SLF4J formatting invokes it again and reports the exception through `Reporter.error`. On `0ffc3e1`, the empty registry bypassed this preliminary rendering, so this fresh event rendered successfully once. The diagnostic path is confirmed in cached SLF4J **2.0.18** bytecode.

3. **Previously guarded errors can escape encoding.** At [SanitizedMessageView.java:65][guard], the new unconditional rendering catches only `RuntimeException` and `LinkageError`. A `Kv` value whose `toString()` throws `AssertionError("boom")` now escapes `PlatformJsonEncoder.encode()`. A recursively implemented `toString()` similarly exposes `StackOverflowError`; SLF4J previously caught these during formatting when no sanitizers were registered.

4. **Ordinary arrays containing null lose their message representation.** With no sanitizers:

   ```java
   log.info("{}", kv("items", new Object[] {"ok", null}));
   ```

   [SanitizedMessageView.java:148][array] calls `List.of(array)`, which rejects null elements. The catch substitutes `[unrenderable]`, replacing the previously readable `items=[ok, null]`; the JSON array remains serializable.

No additional common-value pattern false positives, asymptotic-time regressions, shared-state/thread-safety defects, or independent changes to other encoders were established.

## Verification boundary

Reviewed `0ffc3e1..e1d2842`, surrounding source, tests, generated documentation, and available cached dependency bytecode/Boot metadata. Supplementary Python checks confirmed the cookie matching regression and recognized the whitespace-prefixed JWT without matching the sampled common-value corpus.

**No Maven/JUnit, live scrape, Java timing, or captured-stderr reproduction was completed.** The checkout is read-only, and the attempted JShell probe failed with `Error loading java.security file`. Verdicts therefore reflect source/bytecode verification, not independently passing Java test runs.

[depth]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:134
[guard]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:65
[render]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:120
[array]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:148
[cookie]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:71
[mdc-doc]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v/docs/llms-full.txt:794
[exposure-doc]: C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v/docs/llms-full.txt:1063


## Fix diff e1d2842..f5ce14c (docs/production-readiness excluded)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 7a64a54..8eebde2 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -55,6 +55,8 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
 - The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
   previously exported metrics but never traces.
 - `LoggingProperties` gains `mdc` and `redaction` components (canonical constructor changed).
+- JSON mode switches Boot's startup banner off (`spring.main.banner-mode=off`, overridable) so every
+  stdout line is JSON (D108).
 
 ### Added
 - Logging: `dc.platform.logging.mdc.include` (strict MDC mode), `dc.platform.logging.redaction.enabled`,
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index b09ab23..4870bd6 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -673,6 +673,14 @@ One line of context per decision; details live in the commit bodies referenced.
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
 - **D107 — console log format stays Boot's (hardening phase 2, A1, owner accepted 2026-10-26).** The
   `console` format (the `local` profile) is human-only: it is not masked and a CR/LF in a message can
   split a line. Overriding Boot's console pattern would fork its styling; JSON, used in every deployed
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index dc63688..3d5e59c 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -20,13 +20,15 @@ is watching.
     around it allowed); whatever follows `Bearer` (so prose such as "Bearer token missing" reads
     "Bearer **** missing"); `Basic` credentials that decode to `user:password`;
   - the value after a credential key (`password`, `passwd`, `pwd`, `secret`, `client_secret`, `token`,
-    `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`) in `key=value`,
+    `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`, and compound keys
+    ending in one of them: `session_token`, `db-password`, `smtpSecret`) in `key=value`,
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
@@ -46,7 +48,8 @@ is watching.
   them; a card number your code logs as a top-level field named `traceId`/`spanId` that is also a
   16-character hex string is not masked.
 - **No forged lines** — JSON string escaping keeps CR/LF, quotes and embedded JSON inside one field, so
-  user input cannot add a line or a field.
+  user input cannot add a line or a field. In JSON mode Boot's startup banner is off
+  (`spring.main.banner-mode=off`, overridable), so every stdout line is one JSON object.
 - **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
 - **`LogSanitizer` beans** — your own scrubbing, applied in `@Order` before the platform masks run, to
   MDC values and to keyed arguments — `Kv.of`, Logstash `kv(...)`/`v(...)`/`value(...)` and
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java
index 6e300c0..313b386 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessor.java
@@ -53,6 +53,9 @@ public class PlatformLoggingEnvironmentPostProcessor implements EnvironmentPostP
         }
         Map<String, Object> defaults = new LinkedHashMap<>();
         defaults.put("logging.config", CONFIG_LOCATION);
+        // The ASCII banner goes to stdout outside logging: in JSON mode it would put non-JSON lines in
+        // the stream a log shipper parses line by line (D108).
+        defaults.put("spring.main.banner-mode", "off");
         // Resolved here (not in logback XML) because springProperty supports a single source:
         // explicit dc.platform.logging.service-name > spring.application.name > "application".
         defaults.put("dc.platform.logging.service-name",
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
index df150fb..667828f 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
@@ -11,6 +11,7 @@ import java.util.List;
 import java.util.Locale;
 import java.util.Set;
 import java.util.function.Function;
+import java.util.function.Predicate;
 import java.util.regex.Matcher;
 import java.util.regex.Pattern;
 import net.logstash.logback.mask.ValueMasker;
@@ -45,31 +46,39 @@ public final class PlatformValueMasker implements ValueMasker {
     // Luhn-valid digit run). Anything else, or the same names nested in a structured value, is masked.
     private static final Pattern TRACE_OR_SPAN_ID = Pattern.compile("[0-9a-f]{32}|[0-9a-f]{16}");
 
-    // Normalized (lower case, no '-' or '_') field names whose whole value is a credential.
+    // Normalized (lower case, no '-' or '_') field names whose whole value is a credential: these, and
+    // compound names ending in one of the suffixes (sessionToken, db_password, x-auth-token, smtpSecret).
     private static final Set<String> CREDENTIAL_KEYS = Set.of("password", "passwd", "pwd", "secret",
             "clientsecret", "token", "accesstoken", "refreshtoken", "idtoken", "authorization",
             "proxyauthorization", "cookie", "setcookie", "apikey", "xapikey");
+    private static final List<String> CREDENTIAL_SUFFIXES = List.of("password", "passwd", "secret", "token",
+            "apikey", "authorization", "cookie");
 
     // Compact JWS/JWE candidates: runs of base64url segments joined by dots. A window of three segments
     // is a token when the first decodes to a JSON object (RFC 7515 allows whitespace around it); the
     // smallest header ({"alg":"x"}) is 15 characters, {} as payload encodes to e30, alg=none has no
     // signature.
     private static final Pattern DOTTED_RUN = Pattern.compile("[A-Za-z0-9_.-]{15,}");
-    private static final Pattern BEARER = Pattern.compile("(?i)(?<![\\w-])(Bearer)(\\s+)([A-Za-z0-9._~+/=-]+)");
-    private static final Pattern BASIC = Pattern.compile("(?i)(?<![\\w-])(Basic)(\\s+)([A-Za-z0-9+/]+={0,2})");
+    // Scheme then token. The token is matched in a lookahead, so a word that is not a credential ("basic
+    // Basic dXNlcjpw…") is not consumed and the scan resumes right after the scheme.
+    private static final Pattern BEARER = Pattern.compile("(?i)(?<![\\w-])(Bearer)(\\s+)(?=([A-Za-z0-9._~+/=-]+))");
+    private static final Pattern BASIC = Pattern.compile("(?i)(?<![\\w-])(Basic)(\\s+)(?=([A-Za-z0-9+/]+={0,2}))");
     // Quoted values with escapes, unrolled and possessive: a loop over an alternation would recurse once
     // per character in java.util.regex and overflow the stack on long values. An unterminated quote runs
     // to the end of the text.
     private static final String QUOTED = "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+(?:\"|\\z)"
             + "|'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+(?:'|\\z)";
-    // key=value, key: value, "key":"value", key='value'; quoted values masked whole, quotes kept.
-    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![\\w-])(password|passwd|pwd|secret"
-            + "|client_secret|access_token|refresh_token|id_token|token|api[-_]?key|authorization)"
+    // key=value, key: value, "key":"value", key='value'; quoted values masked whole, quotes kept. The key
+    // may be compound (session_token=, db-password:, smtpSecret=); the prefix is lazy and starts a token,
+    // so scanning stays linear.
+    private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![\\w-])([A-Za-z0-9_-]*?(?:password|passwd|pwd"
+            + "|secret|token|api[-_]?key|authorization))"
             + "(\"?\\s*[:=]\\s*)(" + QUOTED + "|(?:(?:Bearer|Basic)\\s+)?[^\\s\"'&,;}]+)");
-    // "cookie":"…" (a quoted field): the quoted value goes. Cookie: … (header text, several pairs, quoted
-    // pair values included): everything up to the end of the line goes.
-    private static final Pattern COOKIE_FIELD = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\"\\s*:\\s*)("
-            + QUOTED + ")");
+    // A quoted key ("cookie":"…", "cookie"="…", "cookie":12345678): the value goes — a quoted one whole,
+    // a bare one up to the next , } or line end. An unquoted key (Cookie: … header text, several pairs,
+    // quoted pair values included): everything up to the end of the line goes.
+    private static final Pattern COOKIE_FIELD = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\"\\s*[:=]\\s*)("
+            + QUOTED + "|[^\\s\"',}][^,}\\r\\n]*+)");
     private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\\s*[:=]\\s*)"
             + "([^\\r\\n]+)");
     // Emirates ID: 784-YYYY-NNNNNNN-C, dashes optional.
@@ -143,8 +152,8 @@ public final class PlatformValueMasker implements ValueMasker {
     String maskPatterns(String text) {
         String result = maskJwts(text);
         // RFC 6750 tokens may be letters only: whatever follows "Bearer" goes, prose included.
-        result = BEARER.matcher(result).replaceAll("$1$2" + MASK);
-        result = replace(result, BASIC, m -> basicCredential(m.group(3)) ? m.group(1) + m.group(2) + MASK : m.group());
+        result = schemeMask(result, BEARER, token -> !"bearer".equalsIgnoreCase(token));
+        result = schemeMask(result, BASIC, token -> !"basic".equalsIgnoreCase(token) && basicCredential(token));
         result = replace(result, KEY_VALUE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
         result = replace(result, COOKIE_FIELD, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
         result = replace(result, COOKIE_HEADER, m -> m.group(1) + m.group(2) + MASK);
@@ -194,7 +203,11 @@ public final class PlatformValueMasker implements ValueMasker {
      * @return {@code true} when the field's whole value must be masked
      */
     static boolean isCredentialKey(String name) {
-        return name != null && CREDENTIAL_KEYS.contains(normalize(name));
+        if (name == null) {
+            return false;
+        }
+        String normalized = normalize(name);
+        return CREDENTIAL_KEYS.contains(normalized) || CREDENTIAL_SUFFIXES.stream().anyMatch(normalized::endsWith);
     }
 
     private static boolean underCredentialKey(JsonStreamContext context) {
@@ -340,6 +353,29 @@ public final class PlatformValueMasker implements ValueMasker {
         return m.group();
     }
 
+    // Masks the token after each scheme match the predicate accepts; a rejected match consumes only the
+    // scheme and its whitespace.
+    private static String schemeMask(String text, Pattern scheme, Predicate<String> isCredential) {
+        Matcher matcher = scheme.matcher(text);
+        StringBuilder out = null;
+        int copied = 0;
+        int from = 0;
+        while (from <= text.length() && matcher.find(from)) {
+            String token = matcher.group(3);
+            if (isCredential.test(token)) {
+                if (out == null) {
+                    out = new StringBuilder(text.length());
+                }
+                out.append(text, copied, matcher.end()).append(MASK);
+                copied = matcher.end() + token.length();
+                from = copied;
+            } else {
+                from = matcher.end();
+            }
+        }
+        return out == null ? text : out.append(text, copied, text.length()).toString();
+    }
+
     private static String replace(String text, Pattern pattern, Function<Matcher, String> replacement) {
         Matcher matcher = pattern.matcher(text);
         if (!matcher.find()) {
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
index cb7fc82..8e64794 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -8,6 +8,7 @@ import java.lang.reflect.Method;
 import java.lang.reflect.Proxy;
 import java.text.MessageFormat;
 import java.util.ArrayList;
+import java.util.Arrays;
 import java.util.Collection;
 import java.util.LinkedHashMap;
 import java.util.List;
@@ -26,7 +27,8 @@ import org.slf4j.helpers.MessageFormatter;
  * any combination, keyed as in the JSON output (an array element by its enclosing field). Other objects
  * keep their {@code toString}; the pattern masks still run over the whole message.
  *
- * <p>Each argument is rendered under its own guard: one whose rendering throws becomes
+ * <p>Values nested deeper than {@value #MAX_DEPTH} levels read {@code ****} in the message. Each structured
+ * argument is rendered exactly once, under its own guard: one whose rendering throws becomes
  * {@value #UNRENDERABLE}, so neither the event nor a sibling's mask is lost and the exception text never
  * reaches a diagnostic channel. Returns an event-local view: the logged event and its argument array
  * are never mutated; when nothing changes, the original event is returned. Thread-safe.
@@ -62,7 +64,11 @@ public final class SanitizedMessageView {
             Object rendered;
             try {
                 rendered = displayed(arguments[i]);
-            } catch (RuntimeException | LinkageError e) {
+            } catch (OutOfMemoryError e) {
+                throw e;
+            } catch (Throwable e) {
+                // Whatever SLF4J's own formatting would have survived (assertion errors, a recursive
+                // toString's StackOverflowError) is survived here too, without reporting the text.
                 rendered = UNRENDERABLE;
             }
             if (rendered != arguments[i]) {
@@ -87,7 +93,7 @@ public final class SanitizedMessageView {
                 });
     }
 
-    // The argument itself when its rendering is unchanged, otherwise the text to show in its place.
+    // The text to show in the argument's place (an unstructured argument is left to SLF4J).
     private static Object displayed(Object argument) {
         if (argument instanceof Kv kv) {
             if (PlatformValueMasker.isCredentialKey(kv.key())) {
@@ -116,10 +122,10 @@ public final class SanitizedMessageView {
         return argument;
     }
 
-    // A structured argument whose own toString throws is shown as UNRENDERABLE, not handed to SLF4J.
+    // A structured argument is rendered exactly once, here, under the caller's guard: handing it back to
+    // SLF4J would render it a second time, outside the guard.
     private static Object guardedToString(Object argument) {
-        argument.toString();
-        return argument;
+        return String.valueOf(argument);
     }
 
     /**
@@ -131,8 +137,12 @@ public final class SanitizedMessageView {
         if (key != null && PlatformValueMasker.isCredentialKey(key)) {
             return PlatformValueMasker.MASK;
         }
-        if (value == null || depth >= MAX_DEPTH) {
-            return value;
+        if (value == null) {
+            return null;
+        }
+        if (depth >= MAX_DEPTH) {
+            // Deeper than the traversal goes: shown masked rather than unchecked.
+            return PlatformValueMasker.MASK;
         }
         if (value instanceof Map<?, ?> map) {
             Map<Object, Object> copy = new LinkedHashMap<>();
@@ -145,7 +155,7 @@ public final class SanitizedMessageView {
             return changed ? copy : value;
         }
         if (value instanceof Collection<?> || value instanceof Object[]) {
-            Iterable<?> elements = value instanceof Object[] array ? List.of(array) : (Collection<?>) value;
+            Iterable<?> elements = value instanceof Object[] array ? Arrays.asList(array) : (Collection<?>) value;
             List<Object> copy = new ArrayList<>();
             boolean changed = false;
             for (Object element : elements) {
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
index f665fe9..0d3f87f 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
@@ -74,7 +74,75 @@ class EncoderRedactionTest {
                 row("nested level field", "probe", new Object[] {kv("payload", Map.of("level", "card 4111111111111111"))},
                         "4111111111111111"),
                 // R2-F7: a JWT whose header has leading JSON whitespace.
-                row("whitespace-led JWT header", "token {}", new Object[] {SPACED_JWT}, HS256_SPACE_HEADER));
+                row("whitespace-led JWT header", "token {}", new Object[] {SPACED_JWT}, HS256_SPACE_HEADER),
+                // Verification pass: quoted-key cookie forms, and a credential below the traversal depth.
+                row("quoted cookie key with =", "{}", new Object[] {"\"cookie\"=\"sid=abc123\""}, "abc123"),
+                row("quoted cookie key, bare value", "{}", new Object[] {"{\"cookie\":12345678}"}, "12345678"),
+                // Gate F trial: a non-credential "basic"/"bearer" word before the real scheme, and
+                // compound credential names in text, MDC-like fields and arguments.
+                row("basic before Basic", "header {}", new Object[] {"basic Basic YWxpY2U6czNjcjN0LTJiMDgwMA=="},
+                        "YWxpY2U6czNjcjN0LTJiMDgwMA=="),
+                row("bearer before Bearer", "header {}", new Object[] {"bearer Bearer abcdefgh12345678"}, "abcdefgh12345678"),
+                row("compound key in text", "{}", new Object[] {"session_token=st-123456 db-password: pw-987 smtpSecret=sx-1"},
+                        "st-123456", "pw-987", "sx-1"),
+                row("compound credential field", "stored", new Object[] {kv("sessionToken", "st-abcdef"), Kv.of("x-auth-token", "xa-1")},
+                        "st-abcdef", "xa-1"),
+                row("credential 17 levels deep", "{}",
+                        new Object[] {kv("payload", wrap(Map.of("password", "correct horse battery staple"), 16))},
+                        "horse", "staple"));
+    }
+
+    private static Object wrap(Object value, int times) {
+        Object wrapped = value;
+        for (int i = 0; i < times; i++) {
+            wrapped = List.of(wrapped);
+        }
+        return wrapped;
+    }
+
+    @Test
+    void aSanitizedValueBelowTheTraversalDepthIsNotShownInTheMessage() {
+        publish((key, value) -> "customerName".equals(key) ? "C***" : value);
+        String line = encode(event("{}", new Object[] {kv("customerName", wrap("Fatima", 16))}));
+        assertThat(line).doesNotContain("Fatima");
+    }
+
+    @Test
+    void anyThrowableFromToStringIsContainedAndNothingIsRenderedTwice() throws Exception {
+        Object asserting = new Object() {
+            @Override
+            public String toString() {
+                throw new AssertionError("boom");
+            }
+        };
+        Object flaky = new Object() {
+            private int calls;
+
+            @Override
+            public String toString() {
+                if (++calls == 1) {
+                    return "ok";
+                }
+                throw new IllegalStateException("password=hunter4");
+            }
+        };
+        java.io.PrintStream originalErr = System.err;
+        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
+        String line;
+        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
+        try {
+            line = encode(event("{} {}", new Object[] {Kv.of("a", asserting), Kv.of("entity", flaky)}));
+        } finally {
+            System.setErr(originalErr);
+        }
+        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("[unrenderable] entity=ok");
+        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("hunter4");
+    }
+
+    @Test
+    void objectArraysWithNullsStayReadable() throws Exception {
+        String line = encode(event("{}", new Object[] {kv("items", new Object[] {"ok", null})}));
+        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("items=[ok, null]");
     }
 
     @ParameterizedTest(name = "{0}")
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
index ef33a2c..f906ea5 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/JsonLogOutputTest.java
@@ -65,6 +65,9 @@ class JsonLogOutputTest {
                 .orElseThrow(() -> new AssertionError("no probe line captured"));
 
         assertThat(jsonLine).startsWith("{");
+        // R1.1: every stdout line is JSON — the banner is off in JSON mode (D108).
+        assertThat(output.getOut().lines().filter(line -> !line.isBlank()))
+                .allSatisfy(line -> assertThat(line).startsWith("{"));
         assertThat(jsonLine)
                 .contains("\"@timestamp\":")
                 .contains("\"level\":\"INFO\"")
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java
index 0106ea3..c230b7b 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformLoggingEnvironmentPostProcessorTest.java
@@ -52,6 +52,15 @@ class PlatformLoggingEnvironmentPostProcessorTest {
         assertThat(environment.getProperty("logging.config")).isNull();
     }
 
+    @Test
+    void userBannerModeWins() {
+        MockEnvironment environment = new MockEnvironment().withProperty("spring.main.banner-mode", "console");
+
+        postProcessor.postProcessEnvironment(environment, null);
+
+        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("console");
+    }
+
     @Test
     void localProfileFallsBackToConsole() {
         MockEnvironment environment = new MockEnvironment();
@@ -104,6 +113,7 @@ class PlatformLoggingEnvironmentPostProcessorTest {
 
         assertThat(environment.getProperty("logging.config")).isEqualTo(PlatformLoggingEnvironmentPostProcessor.CONFIG_LOCATION);
         assertThat(environment.getProperty("dc.platform.logging.service-name")).isEqualTo("orders");
+        assertThat(environment.getProperty("spring.main.banner-mode")).isEqualTo("off");
         assertThat(environment.getProperty(PlatformLoggingEnvironmentPostProcessor.MDC_INCLUDE_RESOLVED)).isEmpty();
         assertThat(environment.getProperty(PlatformLoggingEnvironmentPostProcessor.REDACTION_PATTERNS_RESOLVED)).isEmpty();
     }
```
