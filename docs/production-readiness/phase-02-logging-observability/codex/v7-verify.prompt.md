# Verification pass 6 — DC Platform hardening Phase 2 (Logging + Observability)

Your working directory is a checkout of commit `2ca797d`. Your previous pass on `edcd515` (below)
accepted the `fields(...)` gap and regressions 1–3, and rejected AC5 (escaped raw string defeating
message masks; decimal-form PANs) with five regressions. The diff below:

- parses raw JSON with `USE_BIG_DECIMAL_FOR_FLOATS`, exact decimal nodes, `FAIL_ON_TRAILING_TOKENS`
  and `STRICT_DUPLICATE_DETECTION` (one shared mapper for JSON and message); text that is not exactly
  one JSON value is kept whole under the pattern masks;
- always shows parsed raw JSON re-serialized in the message (escapes decoded before the masks);
- gives integral finite `Double`/`Float` values the numeric card check, in JSON and message;
- renders the view's sanitized copies element by element (arrays by content at any nesting).

Checklist only — scope is THIS diff:

1. AC5 and your previous regressions 1–5: **accept** or **reject**, file:line, concrete failing input.
2. Any regression this diff introduces, labelled **normal API use** or **unusual composition**.

Output a table `| Item | Verdict | Class | Reason |`, then `## Regressions` (or "none established"),
then `## Verification boundary`.

## Your previous verification pass

| Item | Verdict | Class | Reason |
|---|---|---|---|
| AC5 | **reject** | **normal API use** | The original embedded-password examples are fixed, but `encode(event("{}", new Object[]{raw("payload", "\"Bearer\\u0020hunter2\"")}))` still exposes `hunter2` in `message`. [SanitizedMessageView.java:196][raw-message] returns the original escaped text; the [Bearer pattern at line 64][bearer] requires actual whitespace. JSON masks this string correctly. Decimal-form PANs also remain unmasked in JSON; see below. |
| `fields(...)` composition gap | **accept** | **unusual composition** | [SanitizedMessageView.java:179][fields] now traverses the supplied collection. The two previous examples produce `[****]` and `[unrenderable]`; nested rendering reaches the outer guard without invoking Logstash’s diagnostic helper. |
| Previous regression 1 — depth boundary | **accept** | **unusual composition** | [SanitizedMessageView.java:120][depth-kv] and [line 169][depth-field] restore initial value depth zero. `kv("payload", wrap(1793001600123L, 63))` retains the epoch value. |
| Previous regression 2 — primitive array identity | **accept** | **unusual composition** | [SanitizedMessageView.java:284][array] preserves the changed outer array, so the supplied example renders `payload=[phase=ready, [1, 2]]`. A different nested-array regression is established below. |
| Previous regression 3 — `fields(Kv)` emits nothing | **accept** | **unusual composition** | [KvJsonSerializer.java:31][unwrap] supplies an unwrapping serializer. `fields(Kv.of("orderId", "ORD-42"))` contributes `"orderId":"ORD-42"`, including with redaction disabled. This accepts the explicitly documented new shape. |

## Inventory changes

| Implementation | Message | JSON |
|---|---|---|
| `RawJsonAppendingMarker` | **Not completely handled.** Escaped credential field names now work; escaped string contents can still defeat message pattern masking, as above. | **Handled for the supplied credential examples**, including objects inside arrays and credential-named outer fields. **Not completely handled:** decimal-form numeric PANs become `Double`, which the numeric PAN masker does not inspect. |
| `DeferredStructuredArgument` | **Handled by non-evaluation**, unchanged. | Deferred `raw(...)` now receives the same processing and limitations as direct raw JSON. |
| `DeferredLogstashMarker` | **Handled by non-evaluation**, unchanged. | Same raw-JSON improvement and limitations, including supplied references. |
| `ObjectFieldsAppendingMarker` | The two rejected compositions become **handled**. | `fields(Kv)` no longer silently disappears. |

`ObjectAppendingMarker` and `MapEntriesAppendingMarker` retain their ordinary masking verdicts, with the new array-rendering exception below. Other verdicts remain unchanged. For the abstract bases, `writeRawValue(...)` alone is no longer evidence of an unrestricted bypass; arbitrary overrides using other emission methods, including `writeRaw(...)`, remain uncertified.

## Regressions

1. **Raw numeric precision and type loss — normal API use.**

   ```java
   raw("amount", "0.12345678901234567890123456789")
   raw("amount", "1e400")
   ```

   Previously the JSON field retained each numeric literal. [RawJsonMaskingGenerator.java:36][raw-read] now parses through a default mapper: the first becomes approximately `0.12345678901234568`; the second becomes the JSON string `"Infinity"` under default generator settings.

   This also affects numbers inside objects and arrays. Message values undergo the same conversion when another field forces reserialization at [SanitizedMessageView.java:200][raw-serialize]. These are value/type changes beyond whitespace normalization. Jackson defaults floating-point tree values to `Double`. [Jackson deserializer][jackson-node], [deserialization defaults][jackson-defaults].

2. **Previously masked numeric PAN becomes visible in the message — normal API use.**

   ```java
   raw("payload", "{\"password\":\"hunter2\",\"card\":4111111111111111.0}")
   ```

   Before this diff, message pattern masking caught both the password and the contiguous PAN digits. Now password masking forces [message reserialization][raw-serialize], converting the card to `4.111111111111111E15`. The text patterns no longer recognize that representation.

   JSON also emits that numeric PAN unmasked: [RawJsonMaskingGenerator.java:36][raw-read] creates a `Double`, while [PlatformValueMasker.java:237][numeric] recognizes integral types and `BigDecimal`, but not `Double`. The JSON leak is residual; the message leak is newly introduced. Logstash passes double values to the value masker through its [numeric write path][masking].

3. **Duplicate members disappear — normal API use.**

   ```java
   raw("payload", "{\"phase\":\"queued\",\"phase\":\"ready\"}")
   ```

   Previously both members were emitted. Now JSON contains only `{"phase":"ready"}` because [RawJsonMaskingGenerator.java:36][raw-read] materializes an object tree with last-value-wins semantics. The message retains both members unless sanitization forces reserialization, producing inconsistent views. Jackson’s default explicitly overwrites earlier duplicate members. [Deserialization defaults][jackson-defaults].

4. **Changed arrays inside collections render by identity — unusual composition.**

   ```java
   kv("payload",
       Collections.singletonList(new Object[]{kv("phase", "ready")}))
   ```

   Before: `payload=[[phase=ready]]`.

   Now: `payload=[[Ljava.lang.Object;@<identity>]`.

   [SanitizedMessageView.java:284][array] now retains the inner array, but the enclosing list reaches `String.valueOf` at [line 226][value-text]. List rendering does not apply `Arrays.deepToString` to its elements. The same problem occurs with changed arrays inside maps.

5. **Malformed raw input with a valid prefix is silently truncated — normal API use, malformed input.**

   ```java
   raw("payload", "{\"region\":\"dxb\"} trailing")
   ```

   [RawJsonMaskingGenerator.java:36][raw-read] accepts the first object and emits only `{"region":"dxb"}`. It never reaches the documented non-JSON fallback. Trailing-token validation is disabled by default. [Deserialization defaults][jackson-defaults].

   Previously the entire text was emitted. That previous output was already invalid JSON; this finding is a new silent-discard behavior, not a newly introduced JSON-validity defect.

## Verification boundary

Verified checkout `edcd5153bd1c306111cfb5ce0502c716e0346410` against `b61cf2e`, relevant source/tests, cached encoder 8.1 bytecode and Jackson 2.21.4 behavior.

The interception applies to other callers of `writeRawValue(...)`, including Jackson `@JsonRawValue`/`RawValue` serialization; the numeric and duplicate-member changes therefore extend beyond `raw(...)`. String slices and character arrays reach the override; the inherited `SerializableString` overload dispatches to the string override. Ordinary JSON scalars and arrays are supported.

Malformed input that actually throws during parsing still receives only textual pattern masks and remains raw. Redaction disabled bypasses both the new generator and message view, so the raw-processing and array-rendering regressions above do not apply; `Kv` serializer registration remains unconditional.

**No Maven/JUnit execution or captured-stderr reproduction completed.** The filesystem is read-only; JShell failed with `Error loading java.security file`. `javap` emitted the inspected bytecode alongside archive-access errors. Verdicts and before/after outputs above are source/bytecode-established, not executed integration-test results.

[raw-message]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:196
[bearer]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:64
[fields]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:179
[depth-kv]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:120
[depth-field]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:169
[array]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:284
[unwrap]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java:31
[raw-read]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java:36
[raw-serialize]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:200
[numeric]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:237
[value-text]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v5/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:226
[jackson-node]: https://raw.githubusercontent.com/FasterXML/jackson-databind/jackson-databind-2.21.4/src/main/java/com/fasterxml/jackson/databind/deser/std/JsonNodeDeserializer.java
[jackson-defaults]: https://raw.githubusercontent.com/FasterXML/jackson-databind/jackson-databind-2.21.4/src/main/java/com/fasterxml/jackson/databind/DeserializationFeature.java
[masking]: https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/mask/MaskingJsonGenerator.java

## Fix diff edcd515..2ca797d (docs/production-readiness and tooling/hardening excluded; llms-full excluded — regenerated from docs/modules)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 8da302c..5abf9ec 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -54,8 +54,9 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   exception is no longer reported to stderr or the Logback status manager. A `Kv` nested in a
   structured value is written `{"<key>": value}` (was `{"key": …, "value": …}`; also with redaction
   off), so credential names mask it; `fields(kv)` writes its one field. `raw(...)` JSON is now masked:
-  it is parsed and written back through the masks, so its JSON field is re-serialized (whitespace
-  and key formatting normalized) when redaction is on.
+  it is parsed (numbers exact) and written back through the masks, so its JSON field is re-serialized
+  (whitespace and escapes normalized) when redaction is on; raw text that is not exactly one JSON
+  value is kept whole under the pattern masks. Integral `double`/`float` card numbers are masked.
 - `platform.capability.active` is now registered when Boot creates the meter registry (the
   auto-configuration was evaluated before Boot's registry existed and backed off).
 - The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index f70b0c6..3d9c329 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -63,7 +63,9 @@ is watching.
   top-level one (a nested `Kv` is written `{"<key>": value}` in JSON, so its credential name masks it);
   chained markers (`append(...).and(...)`) render each one, `, `-joined. `raw(...)` JSON is parsed and
   masked like any other structured value, in the JSON field and in `message` (escaped field names
-  included); raw text that is not JSON gets the pattern masks only. In `message`, a value nested
+  included, numbers kept exact); raw text that is not exactly one JSON value (invalid, duplicate
+  member names, trailing text) is kept whole with the pattern masks only. Card numbers logged as a
+  `double` (`4111111111111111.0`) are masked too. In `message`, a value nested
   more than 64 levels deep, or
   a container that contains itself, reads `****`. An argument whose rendering throws reads
   `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
index b740799..8ebcb7d 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
@@ -133,8 +133,7 @@ public final class PlatformValueMasker implements ValueMasker {
                 }
             }
             // kv("card", 4111111111111111L): a card number written as a JSON number.
-            String digits = integralDigits(number);
-            return digits != null && DIGITS.matcher(digits).matches() && luhn(digits) ? MASK : null;
+            return cardNumber(number) ? MASK : null;
         }
         if (!(value instanceof CharSequence text)) {
             return null;
@@ -234,6 +233,12 @@ public final class PlatformValueMasker implements ValueMasker {
         return context.inObject() && context.getParent() != null && context.getParent().inRoot();
     }
 
+    /** Whether a number is a Luhn-valid card number (integral, 13–19 digits, issuer digit 2–6). */
+    static boolean cardNumber(Number number) {
+        String digits = integralDigits(number);
+        return digits != null && DIGITS.matcher(digits).matches() && luhn(digits);
+    }
+
     private static String integralDigits(Number number) {
         if (number instanceof Long || number instanceof Integer) {
             return number.toString();
@@ -250,6 +255,11 @@ public final class PlatformValueMasker implements ValueMasker {
             return stripped.scale() <= 0 && integerDigits >= 13 && integerDigits <= 19
                     ? stripped.toBigInteger().toString() : null;
         }
+        if (number instanceof Double || number instanceof Float) {
+            // 4111111111111111.0 logged as a double: integral finite values are checked like any number.
+            double value = number.doubleValue();
+            return Double.isFinite(value) && value == Math.rint(value) ? integralDigits(BigDecimal.valueOf(value)) : null;
+        }
         return null;
     }
 
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
index ed02fd2..7bf65e0 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
@@ -2,9 +2,13 @@ package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
 
 import com.fasterxml.jackson.core.JsonGenerator;
 import com.fasterxml.jackson.core.JsonProcessingException;
+import com.fasterxml.jackson.core.StreamReadFeature;
 import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
+import com.fasterxml.jackson.databind.DeserializationFeature;
 import com.fasterxml.jackson.databind.JsonNode;
 import com.fasterxml.jackson.databind.ObjectMapper;
+import com.fasterxml.jackson.databind.json.JsonMapper;
+import com.fasterxml.jackson.databind.node.JsonNodeFactory;
 import java.io.IOException;
 
 /**
@@ -16,7 +20,13 @@ import java.io.IOException;
  */
 public final class RawJsonMaskingGenerator extends JsonGeneratorDelegate {
 
-    private static final ObjectMapper RAW = new ObjectMapper();
+    // Exact numbers (no Double rounding, no Infinity), and only one clean value: duplicate members or
+    // trailing text fail the parse, so the text is kept whole under the pattern masks, never truncated.
+    static final ObjectMapper RAW = JsonMapper.builder()
+            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
+            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
+            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
+            .build();
 
     private final PlatformValueMasker masker;
 
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
index 906c84b..db570b0 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -18,6 +18,7 @@ import java.util.LinkedHashMap;
 import java.util.List;
 import java.util.Map;
 import java.util.Set;
+import java.util.StringJoiner;
 import net.logstash.logback.marker.LogstashMarker;
 import net.logstash.logback.marker.MapEntriesAppendingMarker;
 import net.logstash.logback.marker.ObjectFieldsAppendingMarker;
@@ -53,7 +54,7 @@ public final class SanitizedMessageView {
     private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");
     private static final Field OBJECT_FIELDS = field(ObjectFieldsAppendingMarker.class, "object");
     private static final Method SELF_TEXT = accessor(LogstashMarker.class, "toStringSelf");
-    private static final ObjectMapper RAW = new ObjectMapper();
+    private static final ObjectMapper RAW = RawJsonMaskingGenerator.RAW;
 
     private SanitizedMessageView() {
     }
@@ -183,8 +184,9 @@ public final class SanitizedMessageView {
         return SELF_TEXT == null ? "" : String.valueOf(read(SELF_TEXT, marker));
     }
 
-    // raw(...): parsed, so escaped field names are seen; masked contents are shown as JSON again. Text
-    // that is not JSON is shown as is (the pattern masks still run over the message).
+    // raw(...): parsed, so escaped field names are seen, and shown as JSON again with escapes decoded, so
+    // the pattern masks over the message see a JSON-escaped space after "Bearer" as a space. Text that is not one clean
+    // JSON value is shown as is (the pattern masks still run over the message).
     private static String rawText(String name, String raw, int depth, Set<Object> path) {
         Object parsed;
         try {
@@ -193,9 +195,6 @@ public final class SanitizedMessageView {
             return raw;
         }
         Object clean = sanitizedTree(name, parsed, depth, path);
-        if (clean == parsed) {
-            return raw;
-        }
         try {
             return RAW.writeValueAsString(clean);
         } catch (JsonProcessingException e) {
@@ -214,7 +213,13 @@ public final class SanitizedMessageView {
     private static String valueText(Object value) {
         return switch (value) {
             case null -> "null";
-            case Object[] array -> Arrays.deepToString(array);
+            case Object[] array -> {
+                StringJoiner text = new StringJoiner(", ", "[", "]");
+                for (Object element : array) {
+                    text.add(valueText(element));
+                }
+                yield text.toString();
+            }
             case byte[] array -> Arrays.toString(array);
             case short[] array -> Arrays.toString(array);
             case int[] array -> Arrays.toString(array);
@@ -227,6 +232,28 @@ public final class SanitizedMessageView {
         };
     }
 
+    // The sanitized copies show their elements as valueText does (arrays by content, at any nesting);
+    // collections the caller passed in, unchanged, keep their own toString.
+    private static final class ListCopy extends ArrayList<Object> {
+
+        @Override
+        public String toString() {
+            StringJoiner text = new StringJoiner(", ", "[", "]");
+            forEach(element -> text.add(valueText(element)));
+            return text.toString();
+        }
+    }
+
+    private static final class MapCopy extends LinkedHashMap<Object, Object> {
+
+        @Override
+        public String toString() {
+            StringJoiner text = new StringJoiner(", ", "{", "}");
+            forEach((key, value) -> text.add(key + "=" + valueText(value)));
+            return text.toString();
+        }
+    }
+
     /**
      * Applies credential masking and the {@code LogSanitizer} beans to a structured value: the same
      * instance when nothing changes, otherwise a sanitized copy (maps, collections and object arrays
@@ -256,7 +283,7 @@ public final class SanitizedMessageView {
         }
         if (value instanceof Map<?, ?> map) {
             path.add(value);
-            Map<Object, Object> copy = new LinkedHashMap<>();
+            Map<Object, Object> copy = new MapCopy();
             boolean changed = false;
             for (Map.Entry<?, ?> entry : map.entrySet()) {
                 Object clean = sanitizedTree(String.valueOf(entry.getKey()), entry.getValue(), depth + 1, path);
@@ -269,7 +296,7 @@ public final class SanitizedMessageView {
         if (container) {
             path.add(value);
             Iterable<?> elements = value instanceof Object[] array ? Arrays.asList(array) : (Collection<?>) value;
-            List<Object> copy = new ArrayList<>();
+            List<Object> copy = new ListCopy();
             boolean changed = false;
             for (Object element : elements) {
                 Object clean = sanitizedTree(key, element, depth + 1, path);
@@ -283,6 +310,10 @@ public final class SanitizedMessageView {
             // An array stays an array, so it is shown by content (deepToString) as before.
             return value instanceof Object[] ? copy.toArray() : copy;
         }
+        if (value instanceof Number number && PlatformValueMasker.cardNumber(number)) {
+            // As the JSON number masker: a double's text (4.111111111111111E15) escapes the text masks.
+            return PlatformValueMasker.MASK;
+        }
         if (key != null && (value instanceof CharSequence || value instanceof Number)
                 && !LogSanitizerRegistry.current().isEmpty()) {
             String raw = value.toString();
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
index 2d3b0b4..baa7c95 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
@@ -215,6 +215,44 @@ class EncoderRedactionTest {
                 .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
     }
 
+    @Test
+    void rawJsonKeepsNumbersAndMasksEscapedAndDecimalSecrets() throws Exception {
+        // Verification pass 5: raw parsing must not change numbers, and escapes must not hide secrets.
+        String line = encode(event("{} {} {}", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.raw("escaped", "\"Bearer\\u0020hunter2\""),
+                net.logstash.logback.argument.StructuredArguments.raw("payload",
+                        "{\"password\":\"hunter3\",\"card\":4111111111111111.0,\"amount\":0.12345678901234567890123456789,\"big\":1e400}"),
+                kv("double", 4111111111111111.0d)}));
+        assertThat(line).doesNotContain("hunter2").doesNotContain("hunter3")
+                .doesNotContain("4111111111111111").doesNotContain("4.111111111111111E15");
+        // Exact in the encoded text (the test's own reader would round to a double).
+        assertThat(line).contains("\"amount\":0.12345678901234567890123456789").contains("\"big\":1E+400");
+        assertThat(JSON.readTree(line).get("payload").get("big").isNumber()).isTrue();
+    }
+
+    @Test
+    void rawJsonThatIsNotOneCleanValueIsKeptWholeUnderThePatternMasks() throws Exception {
+        // Verification pass 5: duplicates and trailing text were silently dropped by the parser.
+        String line = encode(event("probe", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.raw("dup", "{\"phase\":\"queued\",\"phase\":\"ready\"}"),
+                net.logstash.logback.argument.StructuredArguments.raw("tail", "{\"region\":\"dxb\"}")}));
+        JsonNode node = JSON.readTree(line);
+        assertThat(line).contains("\"queued\"").contains("\"ready\"");
+        assertThat(node.get("tail").get("region").asText()).isEqualTo("dxb");
+        // Not valid JSON before or after; kept whole rather than truncated, under the pattern masks.
+        String trailing = encode(event("probe", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.raw("tail", "{\"region\":\"dxb\"} password=hunter4")}));
+        assertThat(trailing).contains("dxb").doesNotContain("hunter4");
+    }
+
+    @Test
+    void changedArraysInsideCollectionsRenderByContent() throws Exception {
+        // Verification pass 5 regression 4.
+        String line = encode(event("{}", new Object[] {
+                kv("payload", java.util.Collections.singletonList(new Object[] {kv("phase", "ready")}))}));
+        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("payload=[[phase=ready]]");
+    }
+
     /** Serializable as JSON ({@code {"id":"b-1"}}); its toString throws with a secret in the message. */
     public static final class HostileBean {
 
```
