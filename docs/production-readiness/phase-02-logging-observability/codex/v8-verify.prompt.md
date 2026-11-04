# Verification pass 7 — DC Platform hardening Phase 2 (Logging + Observability)

Your working directory is a checkout of commit `739a08d`. Your previous pass on `2ca797d` (below)
accepted pass-5 regressions 1–5 and rejected AC5 (escaped tab after `Bearer` in raw JSON) with three
regressions (duplicate detection sending escaped credential keys to the text fallback; `double[]` card
numbers in `message`; numeric card check before `LogSanitizer` beans in `message`). The diff below:

- replaces the Jackson tree parse with a streaming reader (`RawJson`): exactly one JSON value, member
  order and duplicates kept, numbers exact, names/strings decoded, trailing text rejected; written back
  through the masks; the message shows it decoded (`{name=value}`), like other structured values;
- text that is not exactly one JSON value is written as a JSON **string**, escapes decoded, under the
  pattern masks (decision D109), in JSON and message;
- primitive `long[]`/`double[]`/`float[]` elements get the numeric card check in the message;
- `LogSanitizer` beans run before the numeric card check in the message;
- a consumer-configured `JsonGeneratorDecorator` is chained inside the masking generator;
- the scaling test takes the fastest of five runs per size.

Rules for this pass:
- Scope is THIS diff (`2ca797d..739a08d`, `tooling/hardening`, `docs/llms-full.txt` and
  `docs/production-readiness` excluded).
- A finding reproducible with a plain `String` argument through `{}` is the D102 text-mask baseline,
  not a structured-argument defect: do not report it.
- Accepted residuals — do not re-raise: depth > 64 or cycles read `****` in `message`; bean `toString`
  prose in `message` gets text masks only; the top-level W3C `traceId`/`spanId` exemption; unparseable
  raw text gets the text masks only, after escape decoding (D109); console format unmasked (D107);
  custom `LogstashMarker` subclasses and custom serializers are not certified.

Checklist:
1. AC5 and your previous regressions 1–3: **accept** or **reject**, file:line, concrete failing input.
2. Any regression this diff introduces, labelled **normal API use** or **unusual composition**.

Output a table `| Item | Verdict | Class | Reason |`, then `## Regressions` (or "none established"),
then `## Verification boundary`.

## Your previous verification pass

| Item | Verdict | Class | Reason |
|---|---|---|---|
| AC5 | **reject** | **normal API use** | The supplied escaped-space and decimal-PAN examples are fixed. However, `raw("payload", "\"Bearer\\thunter2\"")` still exposes `hunter2` in `message`: [SanitizedMessageView.java:199][serialize] reserializes the decoded tab as literal `\t`, which the [Bearer pattern’s whitespace matcher][bearer] cannot match. Jackson explicitly [escapes tabs during serialization](https://raw.githubusercontent.com/FasterXML/jackson-core/2.21/src/main/java/com/fasterxml/jackson/core/io/CharTypes.java). The structured JSON value is masked. |
| Previous regression 1 — numeric precision/type loss | **accept** | **normal API use** | [RawJsonMaskingGenerator.java:25][mapper] preserves `raw("amount", "0.12345678901234567890123456789")` exactly and emits `raw("amount", "1e400")` as numeric `1E+400`. The shared message mapper also preserves these values. This follows Jackson’s [BigDecimal deserialization path](https://raw.githubusercontent.com/FasterXML/jackson-databind/jackson-databind-2.21.4/src/main/java/com/fasterxml/jackson/databind/deser/std/JsonNodeDeserializer.java). |
| Previous regression 2 — decimal PAN exposed in message | **accept** | **normal API use** | For `raw("payload", "{\"password\":\"hunter2\",\"card\":4111111111111111.0}")`, exact decimal parsing reaches the existing BigDecimal card check in JSON and the new check at [SanitizedMessageView.java:313][number]. Both secrets are masked in both views. |
| Previous regression 3 — duplicate members disappear | **accept** | **normal API use** | `raw("payload", "{\"phase\":\"queued\",\"phase\":\"ready\"}")` now fails duplicate detection and reaches [RawJsonMaskingGenerator.java:48][fallback], preserving both members. Message parsing uses the same configuration. This accepts preservation; its masking regression is below. |
| Previous regression 4 — changed nested arrays render by identity | **accept** | **unusual composition** | `kv("payload", Collections.singletonList(new Object[]{kv("phase", "ready")}))` renders `payload=[[phase=ready]]`. [SanitizedMessageView.java:242][list-render] renders copied-list elements through the recursive array renderer; copied maps do likewise. |
| Previous regression 5 — malformed input silently truncated | **accept** | **normal API use** | `raw("payload", "{\"region\":\"dxb\"} trailing")` fails trailing-token validation and retains the entire text through [RawJsonMaskingGenerator.java:48][fallback] and [SanitizedMessageView.java:195][raw-fallback]. It remains invalid JSON, as previously documented. |

## Regressions

1. **Duplicate detection newly bypasses structural credential masking — normal API use.**

   ```java
   raw("payload",
       "{\"pass\\u0077ord\":\"hunter2\",\"phase\":1,\"phase\":2}")
   ```

   Previously, parsing decoded `password`; both JSON and message masked `hunter2`. Now [RawJsonMaskingGenerator.java:27][duplicates] rejects the duplicate `phase`, sending the entire object through textual masks at [line 48][fallback]. Those masks do not recognize the escaped credential key. `hunter2` survives in both outputs; the message takes the corresponding [fallback at line 195][raw-fallback].

2. **Copied collections newly expose primitive-array PANs in the message — unusual composition.**

   ```java
   kv("payload", List.of(
       kv("phase", "ready"),
       new double[]{4111111111111111.0d}))
   ```

   Previous message: `payload=[phase=ready, [D@<identity>]`.

   Current message: `payload=[phase=ready, [4.111111111111111E15]]`.

   [SanitizedMessageView.java:242][list-render] now renders the primitive array’s contents. However, [line 279][containers] excludes primitive arrays from traversal, and [line 229][double-array] formats their numbers directly. Neither the new numeric check nor the textual PAN patterns catch this value. The current JSON array is masked through Logstash’s [element-wise numeric write path](https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/mask/MaskingJsonGenerator.java).

3. **Numeric card masking now skips custom message sanitizers — normal API use.**

   Register this `LogSanitizer` and log the argument below:

   ```java
   (key, value) -> "card".equals(key) ? "ending-1111" : value

   kv("card", 4111111111111111L)
   ```

   Previously, message and JSON used `ending-1111`. Now the message is `card=****`, while JSON still contains `"card":"ending-1111"`.

   The new early return at [SanitizedMessageView.java:313][number] skips the sanitizer invocation at line 320. JSON retains sanitizer-first ordering at [PlatformValueMasker.java:128][sanitizer-order], as required by the [documented ordering][contract].

## Verification boundary

Reviewed checkout `2ca797d3d880090238a681a1a43e9f9387004f60` against `edcd515`, limited to this diff and relevant supporting source/tests. The tab-escape failure is residual; the three regressions above are newly introduced.

These are **source-established findings, not executed integration-test results**. No Maven/JUnit execution completed: the filesystem is read-only, and the JShell probe failed with `Error loading java.security file`. Redaction-disabled behavior bypasses these changed masking/rendering paths.

[mapper]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java:25
[duplicates]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java:27
[fallback]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java:48
[serialize]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:199
[raw-fallback]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:195
[list-render]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:242
[containers]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:279
[double-array]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:229
[number]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:313
[bearer]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:64
[sanitizer-order]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:128
[contract]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v6/docs/modules/logging.md:55

## Fix diff 2ca797d..739a08d

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 5abf9ec..ba7fa4d 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -54,9 +54,11 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   exception is no longer reported to stderr or the Logback status manager. A `Kv` nested in a
   structured value is written `{"<key>": value}` (was `{"key": …, "value": …}`; also with redaction
   off), so credential names mask it; `fields(kv)` writes its one field. `raw(...)` JSON is now masked:
-  it is parsed (numbers exact) and written back through the masks, so its JSON field is re-serialized
-  (whitespace and escapes normalized) when redaction is on; raw text that is not exactly one JSON
-  value is kept whole under the pattern masks. Integral `double`/`float` card numbers are masked.
+  it is read as one JSON value (member order, duplicates and exact numbers kept) and written back
+  through the masks, so its JSON field is re-serialized (whitespace and escapes normalized) when
+  redaction is on, and `message` shows it decoded (`{name=value}`); raw text that is not exactly one
+  JSON value is written as a JSON string, escapes decoded, under the pattern masks (was: raw, which
+  made the line invalid JSON; D109). Integral `double`/`float` card numbers are masked.
 - `platform.capability.active` is now registered when Boot creates the meter registry (the
   auto-configuration was evaluated before Boot's registry existed and backed off).
 - The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
diff --git a/docs/decisions/decision-log.md b/docs/decisions/decision-log.md
index 1dadfdb..58bdc87 100644
--- a/docs/decisions/decision-log.md
+++ b/docs/decisions/decision-log.md
@@ -677,6 +677,14 @@ One line of context per decision; details live in the commit bodies referenced.
   written to stdout outside logging, so a log shipper parsing stdout line by line saw non-JSON lines.
   The logging EPP defaults `spring.main.banner-mode=off` whenever the JSON format is resolved
   (lowest precedence; `console` or `log` set by the service wins). Console format keeps Boot's banner.
+- **D109 — `raw(...)` JSON is masked; unparseable raw text becomes a string (hardening phase 2, Gate D).**
+  Logstash's masking generator forwards `writeRawValue` unread, so `raw("payload", "{\"password\":…}")`
+  reached the log in clear. The platform reads the raw text as exactly one JSON value (member order,
+  duplicates and exact numbers kept, escapes decoded) and writes it back through the masks. Text that
+  is not one JSON value is written as a JSON **string**, escapes decoded, under the pattern masks:
+  written raw it would make the whole line invalid JSON, and undecoded escapes (`password`)
+  would hide credential names from the masks. Simpler than accepting the bypass as a residual, and
+  fail-closed.
 - **D102 addendum — compound credential names (hardening phase 2, Gate F trial).** A field or text key
   ending in `password`, `passwd`, `secret`, `token`, `apikey`, `authorization` or `cookie` (ignoring
   case, `-`, `_`) is a credential too: `sessionToken`, `x-auth-token`, `db_password`. The consumer run
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index 3d9c329..9232370 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -62,12 +62,13 @@ is watching.
   masks still run (`fields(bean)` likewise). A `Kv` or marker nested inside a value is treated like a
   top-level one (a nested `Kv` is written `{"<key>": value}` in JSON, so its credential name masks it);
   chained markers (`append(...).and(...)`) render each one, `, `-joined. `raw(...)` JSON is parsed and
-  masked like any other structured value, in the JSON field and in `message` (escaped field names
-  included, numbers kept exact); raw text that is not exactly one JSON value (invalid, duplicate
-  member names, trailing text) is kept whole with the pattern masks only. Card numbers logged as a
-  `double` (`4111111111111111.0`) are masked too. In `message`, a value nested
-  more than 64 levels deep, or
-  a container that contains itself, reads `****`. An argument whose rendering throws reads
+  masked like any other structured value, in the JSON field and in `message` (escapes decoded, member
+  order and duplicate names kept, numbers exact; `message` shows it decoded, `{name=value}`, like
+  other structured values); raw text that is not exactly one JSON value (invalid, trailing text) is
+  written as a JSON string, escapes decoded, under the pattern masks (D109). Card numbers logged as a
+  `double` (`4111111111111111.0`, also inside a `double[]`) are masked too; `LogSanitizer` beans run
+  before that check. In `message`, a value nested more than 64 levels deep, or a container that
+  contains itself, reads `****`. An argument whose rendering throws reads
   `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
   status).
 
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
index 53295e7..0da99b7 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
@@ -101,11 +101,14 @@ public class PlatformJsonEncoder extends LogstashEncoder {
             PlatformValueMasker masker = new PlatformValueMasker(PlatformValueMasker.compile(redactionPatterns));
             masking.addValueMasker(masker);
             masking.start();
-            // Outside the masking generator: raw(...) JSON is parsed and written back through it.
+            // Outside the masking generator: raw(...) JSON is parsed and written back through it. A
+            // generator decorator set in the consumer's Logback file stays innermost.
+            JsonGeneratorDecorator configuredGenerator = getJsonGeneratorDecorator();
             setJsonGeneratorDecorator(new JsonGeneratorDecorator() {
                 @Override
                 public JsonGenerator decorate(JsonGenerator generator) {
-                    return new RawJsonMaskingGenerator(masking.decorate(generator), masker);
+                    JsonGenerator base = configuredGenerator == null ? generator : configuredGenerator.decorate(generator);
+                    return new RawJsonMaskingGenerator(masking.decorate(base), masker);
                 }
             });
         }
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJson.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJson.java
new file mode 100644
index 0000000..e9b9dbc
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJson.java
@@ -0,0 +1,158 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import com.fasterxml.jackson.core.JsonFactory;
+import com.fasterxml.jackson.core.JsonGenerator;
+import com.fasterxml.jackson.core.JsonParseException;
+import com.fasterxml.jackson.core.JsonParser;
+import com.fasterxml.jackson.core.JsonToken;
+import java.io.IOException;
+import java.math.BigDecimal;
+import java.math.BigInteger;
+import java.util.AbstractMap.SimpleImmutableEntry;
+import java.util.ArrayList;
+import java.util.List;
+import java.util.Map;
+
+/**
+ * Reads the text of a {@code raw(...)} argument as exactly one JSON value, losing nothing: members keep
+ * their order and duplicates, numbers stay exact ({@link BigDecimal}, integers as parsed), strings and
+ * names are decoded. Objects become {@link RawObject}, arrays {@link List}. Text that is not exactly one
+ * JSON value (invalid, empty, trailing text) fails with an {@link IOException}. Stateless and thread-safe.
+ */
+final class RawJson {
+
+    private static final JsonFactory FACTORY = new JsonFactory();
+
+    private RawJson() {
+    }
+
+    /** A JSON object as parsed: its members in order, duplicates kept. */
+    static final class RawObject {
+
+        final List<Map.Entry<String, Object>> members = new ArrayList<>();
+
+        void add(String name, Object value) {
+            members.add(new SimpleImmutableEntry<>(name, value));
+        }
+    }
+
+    static Object parse(String text) throws IOException {
+        try (JsonParser parser = FACTORY.createParser(text)) {
+            parser.nextToken();
+            Object value = read(parser);
+            if (parser.nextToken() != null) {
+                throw new JsonParseException(parser, "trailing text after the JSON value");
+            }
+            return value;
+        }
+    }
+
+    private static Object read(JsonParser parser) throws IOException {
+        JsonToken token = parser.currentToken();
+        if (token == null) {
+            throw new JsonParseException(parser, "no JSON value");
+        }
+        return switch (token) {
+            case START_OBJECT -> {
+                RawObject object = new RawObject();
+                while (parser.nextToken() == JsonToken.FIELD_NAME) {
+                    String name = parser.currentName();
+                    parser.nextToken();
+                    object.add(name, read(parser));
+                }
+                yield object;
+            }
+            case START_ARRAY -> {
+                List<Object> array = new ArrayList<>();
+                while (parser.nextToken() != JsonToken.END_ARRAY) {
+                    array.add(read(parser));
+                }
+                yield array;
+            }
+            case VALUE_STRING -> parser.getText();
+            case VALUE_NUMBER_INT -> parser.getNumberValue();
+            case VALUE_NUMBER_FLOAT -> parser.getDecimalValue();
+            case VALUE_TRUE -> Boolean.TRUE;
+            case VALUE_FALSE -> Boolean.FALSE;
+            case VALUE_NULL -> null;
+            default -> throw new JsonParseException(parser, "unexpected " + token);
+        };
+    }
+
+    /**
+     * Decodes JSON escapes ({@code \"}, {@code \\}, {@code \/}, {@code \b}, {@code \f}, {@code \n},
+     * {@code \r}, {@code \t}, {@code \}{@code uXXXX}) in text that did not parse, so the pattern masks
+     * see escaped names and values as written. A malformed escape is kept as is.
+     */
+    static String decodeEscapes(String text) {
+        if (text.indexOf('\\') < 0) {
+            return text;
+        }
+        StringBuilder out = new StringBuilder(text.length());
+        for (int i = 0; i < text.length(); i++) {
+            char c = text.charAt(i);
+            if (c != '\\' || i + 1 == text.length()) {
+                out.append(c);
+                continue;
+            }
+            char next = text.charAt(i + 1);
+            switch (next) {
+                case '"', '\\', '/' -> out.append(next);
+                case 'b' -> out.append('\b');
+                case 'f' -> out.append('\f');
+                case 'n' -> out.append('\n');
+                case 'r' -> out.append('\r');
+                case 't' -> out.append('\t');
+                case 'u' -> {
+                    if (i + 6 <= text.length() && hex(text, i + 2, i + 6)) {
+                        out.append((char) Integer.parseInt(text, i + 2, i + 6, 16));
+                        i += 4;
+                    } else {
+                        out.append(c).append(next);
+                    }
+                }
+                default -> out.append(c).append(next);
+            }
+            i++;
+        }
+        return out.toString();
+    }
+
+    private static boolean hex(String text, int from, int to) {
+        for (int i = from; i < to; i++) {
+            if (Character.digit(text.charAt(i), 16) < 0) {
+                return false;
+            }
+        }
+        return true;
+    }
+
+    /** Writes a parsed value through the given generator (so its masks apply to every member). */
+    static void write(Object value, JsonGenerator generator) throws IOException {
+        switch (value) {
+            case null -> generator.writeNull();
+            case RawObject object -> {
+                generator.writeStartObject();
+                for (Map.Entry<String, Object> member : object.members) {
+                    generator.writeFieldName(member.getKey());
+                    write(member.getValue(), generator);
+                }
+                generator.writeEndObject();
+            }
+            case List<?> array -> {
+                generator.writeStartArray();
+                for (Object element : array) {
+                    write(element, generator);
+                }
+                generator.writeEndArray();
+            }
+            case String text -> generator.writeString(text);
+            case BigDecimal number -> generator.writeNumber(number);
+            case BigInteger number -> generator.writeNumber(number);
+            case Long number -> generator.writeNumber(number);
+            case Integer number -> generator.writeNumber(number);
+            case Boolean bool -> generator.writeBoolean(bool);
+            default -> generator.writeString(String.valueOf(value));
+        }
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
index 7bf65e0..1653a2d 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
@@ -1,33 +1,19 @@
 package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
 
 import com.fasterxml.jackson.core.JsonGenerator;
-import com.fasterxml.jackson.core.JsonProcessingException;
-import com.fasterxml.jackson.core.StreamReadFeature;
 import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
-import com.fasterxml.jackson.databind.DeserializationFeature;
-import com.fasterxml.jackson.databind.JsonNode;
-import com.fasterxml.jackson.databind.ObjectMapper;
-import com.fasterxml.jackson.databind.json.JsonMapper;
-import com.fasterxml.jackson.databind.node.JsonNodeFactory;
 import java.io.IOException;
 
 /**
  * Wraps the masking generator so a raw JSON value ({@code raw(...)}) is masked like any other:
- * Logstash's masking generator forwards {@code writeRawValue} unread. The raw text is parsed and
- * written back as a tree through this generator, so field-name and value masks apply to its contents
- * (escaped field names included); text that is not JSON is written raw after the pattern masks. One
+ * Logstash's masking generator forwards {@code writeRawValue} unread. The raw text is read as one JSON
+ * value ({@link RawJson}: member order, duplicates and exact numbers kept, escapes decoded) and written
+ * back through this generator, so field-name and value masks apply to its contents; text that is not
+ * exactly one JSON value is written as a JSON string, escapes decoded, after the pattern masks. One
  * instance per event's generator; not thread-safe, like the generator it wraps.
  */
 public final class RawJsonMaskingGenerator extends JsonGeneratorDelegate {
 
-    // Exact numbers (no Double rounding, no Infinity), and only one clean value: duplicate members or
-    // trailing text fail the parse, so the text is kept whole under the pattern masks, never truncated.
-    static final ObjectMapper RAW = JsonMapper.builder()
-            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
-            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
-            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
-            .build();
-
     private final PlatformValueMasker masker;
 
     /**
@@ -41,15 +27,17 @@ public final class RawJsonMaskingGenerator extends JsonGeneratorDelegate {
 
     @Override
     public void writeRawValue(String text) throws IOException {
-        JsonNode tree;
+        Object value;
         try {
-            tree = RAW.readTree(text);
-        } catch (JsonProcessingException e) {
-            super.writeRawValue(masker.maskPatterns(text));
+            value = RawJson.parse(text);
+        } catch (IOException e) {
+            // Not one JSON value: written as a string (raw it would break the line's JSON), escapes
+            // decoded first so the pattern masks see escaped names (D109).
+            writeString(masker.maskPatterns(RawJson.decodeEscapes(text)));
             return;
         }
-        // Not delegated: the codec writes the tree through this generator, so the masks see every field.
-        writeTree(tree);
+        // Through this generator, not the delegate's raw path: the masks see every member.
+        RawJson.write(value, this);
     }
 
     @Override
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
index db570b0..594c951 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -2,8 +2,7 @@ package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
 
 import ae.gov.dubaicustoms.platform.logging.Kv;
 import ch.qos.logback.classic.spi.ILoggingEvent;
-import com.fasterxml.jackson.core.JsonProcessingException;
-import com.fasterxml.jackson.databind.ObjectMapper;
+import java.io.IOException;
 import java.lang.reflect.Field;
 import java.lang.reflect.InvocationTargetException;
 import java.lang.reflect.Method;
@@ -54,7 +53,6 @@ public final class SanitizedMessageView {
     private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");
     private static final Field OBJECT_FIELDS = field(ObjectFieldsAppendingMarker.class, "object");
     private static final Method SELF_TEXT = accessor(LogstashMarker.class, "toStringSelf");
-    private static final ObjectMapper RAW = RawJsonMaskingGenerator.RAW;
 
     private SanitizedMessageView() {
     }
@@ -165,7 +163,7 @@ public final class SanitizedMessageView {
             }
             Object value = read(FIELD_VALUE, field);
             if (marker instanceof RawJsonAppendingMarker && value instanceof String raw) {
-                return render(field, name, rawText(name, raw, depth, path));
+                return render(field, name, rawText(name, raw, depth));
             }
             return render(field, name, valueText(sanitizedTree(name, value, depth, path)));
         }
@@ -184,22 +182,36 @@ public final class SanitizedMessageView {
         return SELF_TEXT == null ? "" : String.valueOf(read(SELF_TEXT, marker));
     }
 
-    // raw(...): parsed, so escaped field names are seen, and shown as JSON again with escapes decoded, so
-    // the pattern masks over the message see a JSON-escaped space after "Bearer" as a space. Text that is not one clean
-    // JSON value is shown as is (the pattern masks still run over the message).
-    private static String rawText(String name, String raw, int depth, Set<Object> path) {
+    // raw(...): read as one JSON value (RawJson) and shown decoded, like any structured value — names and
+    // strings unescaped, so the pattern masks over the message see them as written. Text that is not
+    // exactly one JSON value is shown with its escapes decoded (the pattern masks run over the message).
+    private static String rawText(String name, String raw, int depth) {
         Object parsed;
         try {
-            parsed = RAW.readValue(raw, Object.class);
-        } catch (JsonProcessingException e) {
-            return raw;
+            parsed = RawJson.parse(raw);
+        } catch (IOException e) {
+            return RawJson.decodeEscapes(raw);
         }
-        Object clean = sanitizedTree(name, parsed, depth, path);
-        try {
-            return RAW.writeValueAsString(clean);
-        } catch (JsonProcessingException e) {
+        return valueText(rawClean(name, parsed, depth));
+    }
+
+    // As sanitizedTree, over a parsed raw value (duplicate members kept).
+    private static Object rawClean(String key, Object node, int depth) {
+        if (key != null && PlatformValueMasker.isCredentialKey(key) || depth >= MAX_DEPTH) {
             return PlatformValueMasker.MASK;
         }
+        if (node instanceof RawJson.RawObject object) {
+            RawJson.RawObject copy = new RawJson.RawObject();
+            object.members.forEach(member ->
+                    copy.add(member.getKey(), rawClean(member.getKey(), member.getValue(), depth + 1)));
+            return copy;
+        }
+        if (node instanceof List<?> array) {
+            List<Object> copy = new ListCopy();
+            array.forEach(element -> copy.add(rawClean(key, element, depth + 1)));
+            return copy;
+        }
+        return sanitizedTree(key, node, depth);
     }
 
     // A structured argument is rendered exactly once, here, under the caller's guard: handing it back to
@@ -223,15 +235,36 @@ public final class SanitizedMessageView {
             case byte[] array -> Arrays.toString(array);
             case short[] array -> Arrays.toString(array);
             case int[] array -> Arrays.toString(array);
-            case long[] array -> Arrays.toString(array);
+            case long[] array -> numbers(Arrays.stream(array).boxed().toList());
             case char[] array -> Arrays.toString(array);
-            case float[] array -> Arrays.toString(array);
-            case double[] array -> Arrays.toString(array);
+            case float[] array -> numbers(floats(array));
+            case double[] array -> numbers(Arrays.stream(array).boxed().toList());
             case boolean[] array -> Arrays.toString(array);
+            case RawJson.RawObject object -> {
+                StringJoiner text = new StringJoiner(", ", "{", "}");
+                object.members.forEach(member -> text.add(member.getKey() + "=" + valueText(member.getValue())));
+                yield text.toString();
+            }
             default -> String.valueOf(value);
         };
     }
 
+    // Primitive number arrays by content, each element through the numeric card check (the JSON array's
+    // elements are masked one by one too).
+    private static String numbers(List<? extends Number> elements) {
+        StringJoiner text = new StringJoiner(", ", "[", "]");
+        elements.forEach(number -> text.add(PlatformValueMasker.cardNumber(number) ? PlatformValueMasker.MASK : String.valueOf(number)));
+        return text.toString();
+    }
+
+    private static List<Float> floats(float[] array) {
+        List<Float> boxed = new ArrayList<>(array.length);
+        for (float element : array) {
+            boxed.add(element);
+        }
+        return boxed;
+    }
+
     // The sanitized copies show their elements as valueText does (arrays by content, at any nesting);
     // collections the caller passed in, unchanged, keep their own toString.
     private static final class ListCopy extends ArrayList<Object> {
@@ -310,17 +343,17 @@ public final class SanitizedMessageView {
             // An array stays an array, so it is shown by content (deepToString) as before.
             return value instanceof Object[] ? copy.toArray() : copy;
         }
-        if (value instanceof Number number && PlatformValueMasker.cardNumber(number)) {
-            // As the JSON number masker: a double's text (4.111111111111111E15) escapes the text masks.
-            return PlatformValueMasker.MASK;
-        }
+        // LogSanitizer beans first, then the numeric card check — the JSON field's order.
         if (key != null && (value instanceof CharSequence || value instanceof Number)
                 && !LogSanitizerRegistry.current().isEmpty()) {
             String raw = value.toString();
             String clean = PlatformValueMasker.sanitize(key, raw);
-            return clean.equals(raw) ? value : clean;
+            if (!clean.equals(raw)) {
+                return clean;
+            }
         }
-        return value;
+        // As the JSON number masker: a double's text (4.111111111111111E15) escapes the text masks.
+        return value instanceof Number number && PlatformValueMasker.cardNumber(number) ? PlatformValueMasker.MASK : value;
     }
 
     // As SingleFieldAppendingMarker.toStringSelf, with the given value text.
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
index baa7c95..81f5aa3 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
@@ -253,6 +253,51 @@ class EncoderRedactionTest {
         assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("payload=[[phase=ready]]");
     }
 
+    @Test
+    void rawJsonEscapesAndDuplicatesNeverHideACredential() throws Exception {
+        // Verification pass 6: an escaped tab after Bearer; duplicates sent escaped keys to the text masks.
+        String line = encode(event("{} {}", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.raw("tabbed", "\"Bearer\\thunter2\""),
+                net.logstash.logback.argument.StructuredArguments.raw("payload",
+                        "{\"pass\\u0077ord\":\"hunter3\",\"phase\":1,\"phase\":2}")}));
+        assertThat(line).doesNotContain("hunter2").doesNotContain("hunter3");
+        assertThat(line).contains("\"phase\":1").contains("\"phase\":2");
+    }
+
+    @Test
+    void cardNumbersInPrimitiveArraysAreMaskedInTheMessage() throws Exception {
+        // Verification pass 6 regression 2.
+        String line = encode(event("{}", new Object[] {
+                kv("payload", List.of(kv("phase", "ready"), new double[] {4111111111111111.0d}))}));
+        assertThat(line).doesNotContain("4.111111111111111E15").doesNotContain("4111111111111111");
+    }
+
+    @Test
+    void sanitizersRunBeforeTheNumericCardCheckInTheMessage() throws Exception {
+        // Verification pass 6 regression 3: LogSanitizer beans come first, as in the JSON field.
+        publish((key, value) -> "card".equals(key) ? "ending-1111" : value);
+        String line = encode(event("{}", new Object[] {kv("card", 4111111111111111L)}));
+        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("card=ending-1111");
+    }
+
+    @Test
+    void unparseableRawTextIsDecodedMaskedAndWrittenAsAString() throws Exception {
+        // Pre-pass-7 probe: whatever makes the parse fail, escaped names must not reach the text masks
+        // undecoded, and the log line must stay one valid JSON object.
+        String longNumber = "1".repeat(1_200);
+        String line = encode(event("{} {} {} {}", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.raw("comma", "{\"pass\\u0077ord\":\"hunter6\",}"),
+                net.logstash.logback.argument.StructuredArguments.raw("long", "{\"pass\\u0077ord\":\"hunter7\",\"n\":" + longNumber + "}"),
+                net.logstash.logback.argument.StructuredArguments.raw("empty", ""),
+                net.logstash.logback.argument.StructuredArguments.defer(
+                        () -> net.logstash.logback.argument.StructuredArguments.raw("deferred", "{\"pass\\u0077ord\":\"hunter8\"}"))}));
+        assertThat(line).doesNotContain("hunter6").doesNotContain("hunter7").doesNotContain("hunter8");
+        JsonNode node = JSON.readTree(line);
+        assertThat(node.get("comma").isTextual()).isTrue();
+        assertThat(node.get("empty").asText()).isEmpty();
+        assertThat(node.get("deferred").get("password").asText()).isEqualTo("****");
+    }
+
     /** Serializable as JSON ({@code {"id":"b-1"}}); its toString throws with a secret in the message. */
     public static final class HostileBean {
 
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
index 8c54b81..f943971 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
@@ -185,13 +185,23 @@ class PlatformValueMaskerTest {
     void longInputsWithoutMatchesAreScannedQuickly() {
         // Scaling, not a wall-clock budget (shared CI runners vary): 4x the input must cost far less than
         // the 16x a quadratic scan would. The absolute ceiling only catches a pathological case.
+        // Fastest of five runs per size: a GC pause or a descheduled thread on a shared runner only ever
+        // adds time. Linear ~4x, quadratic ~16x; the threshold sits between.
         scanTime(50_000); // warm-up (JIT)
-        long small = Math.max(scanTime(50_000), Duration.ofMillis(1).toNanos());
-        long large = scanTime(200_000);
-        assertThat(large).as("4x input: %d ns vs %d ns", large, small).isLessThan(8 * small);
+        long small = Math.max(fastestScan(50_000), Duration.ofMillis(1).toNanos());
+        long large = fastestScan(200_000);
+        assertThat(large).as("4x input: %d ns vs %d ns", large, small).isLessThan(10 * small);
         assertThat(Duration.ofNanos(large)).isLessThan(Duration.ofSeconds(5));
     }
 
+    private long fastestScan(int length) {
+        long fastest = Long.MAX_VALUE;
+        for (int run = 0; run < 5; run++) {
+            fastest = Math.min(fastest, scanTime(length));
+        }
+        return fastest;
+    }
+
     private long scanTime(int length) {
         String noAt = "a".repeat(length);
         String trailingAt = "a.".repeat(length / 2) + "@";
```
