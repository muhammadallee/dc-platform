# Verification pass 5 — DC Platform hardening Phase 2 (Logging + Observability)

Your working directory is a checkout of commit `edcd515`. Your previous pass on `b61cf2e` (below)
rejected AC5 (`raw(...)` JSON bypassing masking — normal API use) and the `fields(...)` composition
gap, and listed three regressions (depth boundary shifted by one; changed arrays rendering primitive
arrays by identity; `fields(Kv)` writing no fields). The diff below addresses them:

- `RawJsonMaskingGenerator`, installed outside the masking generator, parses `writeRawValue` text and
  writes the tree back through itself (`JsonGeneratorDelegate` with `delegateCopyMethods=false`, so the
  codec writes through the masks); non-JSON raw text is written raw after the pattern masks. The
  message view parses `raw(...)` the same way (escaped field names included).
- `KvJsonSerializer` gains an unwrapping form; `fields(...)` values are traversed like any value; the
  depth boundary is back to structured values starting at depth 0; a changed `Object[]` stays an array.

Checklist only:

1. For AC5, the `fields(...)` gap and your previous regressions 1–3: **accept** or **reject**, with
   file:line and a concrete failing input for any reject.
2. Update your inventory: does `RawJsonAppendingMarker` (and a deferred `raw(...)`) now count as
   **handled** for message and JSON? Any other row whose verdict changes?
3. Report any regression this diff introduces, labelled **normal API use** or **unusual
   composition**, to the same evidence standard (consider: raw JSON numbers/precision, duplicate keys,
   raw values that are JSON scalars or arrays, invalid raw JSON, `writeRawValue` calls from paths
   other than `raw(...)`, redaction disabled).

Output a table `| Item | Verdict | Class | Reason |`, then `## Inventory changes`, then
`## Regressions` (or "none established"), then `## Verification boundary`.

## Your previous verification pass

| Item | Verdict | Class | Reason |
|---|---|---|---|
| AC5 | **reject** | **normal API use** | `encode(event("{}", new Object[]{raw("payload", "{\"password\":\"hunter2\"}")}))` still writes `"payload":{"password":"hunter2"}`. `RawJsonAppendingMarker.java:65` calls `writeRawValue`; `MaskingJsonGenerator.java:382` forwards it without inspecting its contents. The platform installs that generator at [PlatformJsonEncoder.java:96][encoder]. This gap predates this diff. [Encoder 8.1 raw-value implementation][raw], [masking implementation][masking]. |
| Previous regression 1 — nested-marker status leak | **accept** | **unusual composition** | The supplied `kv("payload", wrap(kv("entity", bad), 16))` now reaches [SanitizedMessageView.java:217][nested], then the safe field renderer at [line 161][field]. The throwing value reaches the outer catch at [line 78][guard], producing `[unrenderable]` without invoking Logstash’s diagnostic catch. |
| Previous regression 2 — chained references disappear | **accept** | **normal API use** | [SanitizedMessageView.java:134][chain] now preserves own text, reference order and separator behavior, matching encoder 8.1’s `LogstashMarker.java:107`. The supplied chain renders `orderId=ORD-42, phase=ready, password=****`. [Upstream rendering][logstash]. |
| Nested `Kv` credential masking | **accept** | **unusual composition** | `kv("wrapper", List.of(Kv.of("token", "tk-1")))` now exposes `token` as a JSON field through [KvJsonSerializer.java:23][serializer]. Registration follows the configured factory decorator at [PlatformJsonEncoder.java:88][registration]. |
| Supplied `StructuredArguments.defer(...)` cases | **accept** | **normal API use** | `DeferredStructuredArgument` has no `toString` override in 8.1; message formatting therefore does not evaluate its supplied argument. Its `writeTo`, `DeferredStructuredArgument.java:70`, delegates JSON writing through the masking generator. This acceptance does not extend to a deferred `raw(...)` payload. |
| Remaining `fields(...)` composition gap | **reject** | **unusual composition** | `fields(List.of(kv("entity", bad)))`, with the supplied `HostileBean`, still leaks its throwable to Logstash’s diagnostic channel. [SanitizedMessageView.java:169][fields] calls `valueText` directly; the list invokes the nested marker’s own `toString`, reaching `StructuredArguments.java:309`. Likewise, `fields(List.of(v("password", "hunter2")))` exposes `[hunter2]` in the message. Both gaps predate this diff. [Upstream diagnostic helper][arguments]. |

The inventory below includes **all eight concrete implementations and both abstract implementation bases** found by inspecting every class header in the cached 8.1 JAR. Verdicts distinguish ordinary stock behavior from the explicit composition failures above. Referenced or deferred values inherit the verdict of their implementation; arbitrary consumer subclasses and custom serializers are not certified.

| Implementation | Interfaces | Message | JSON | Class / evidence |
|---|---|---|---|---|
| `ObjectAppendingMarker` | `StructuredArgument`, `Marker` | **handled** for `kv`, `v`, `value`, `array`, `append`, `appendArray`: credential fields are intercepted and ordinary throwing values reach the outer guard. | **handled** for ordinary structured values and the supplied hostile bean; JSON serialization does not call that bean’s `toString`. | **normal API use**. [View:156][field]; `ObjectAppendingMarker.java:93` writes through `generator.writeObject`; [CredentialFieldMasker.java:18][credential]. |
| `RawJsonAppendingMarker` | Both | **not handled** completely. Outer credential names and ordinary textual credential spellings are masked, but JSON escape sequences are not decoded. For example, `raw("payload", "{\"pass\\u0077ord\":\"hunter2\"}")` leaves `hunter2` in the message. | **not handled**: embedded fields bypass masking, as in the AC5 reproduction. A credential-named *outer* field is masked. | **normal API use**. [View:161][field], [PlatformValueMasker.java:75][patterns]; `RawJsonAppendingMarker.java:65`, `MaskingJsonGenerator.java:382`. [Raw source][raw], [masking source][masking]. |
| `MapEntriesAppendingMarker` | Both | **handled** for ordinary string-keyed maps, including nested structured values and throwing value rendering. | **handled** for ordinary string-keyed maps and bean values. This is not containment of arbitrary throwing map-key conversion. | **normal API use**. [View:163][entries]; `MapEntriesAppendingMarker.java:89` writes field names and `:90` writes values; [field masker][credential]. |
| `ObjectFieldsAppendingMarker` | Both | **handled** for ordinary `fields(bean)` rendering; **not handled** for the nested-marker compositions above. Bean text retains the documented text-mask-only behavior. | **handled** for ordinary bean properties. `fields(Kv)` loses its fields after this diff—see regression 3. | **normal API use** for beans; **unusual composition** for those exceptions. [View:167][fields]; `ObjectFieldsAppendingMarker.java:103,110,176`. |
| `EmptyLogstashMarker` | Both | **handled**: empty own text; references use the guarded renderer. | **handled**: own `writeTo` emits nothing. Event-marker references are processed separately. | **normal API use**. [View:134][chain], [View:173][fallback]; `EmptyLogstashMarker.java:51,55`. |
| `DeferredStructuredArgument` | `StructuredArgument` only | **handled** by non-evaluation: inherited identity text, no supplied-value `toString`. | **handled** for supplied ordinary arguments; **not handled** when supplying the raw-JSON case above. | **normal API use**. [View:129][passthrough]; `DeferredStructuredArgument.java:70,83`. |
| `DeferredLogstashMarker` | `Marker` only | **handled**: inherited own text is `DEFERRED`; it does not render the supplier’s value. | **handled** for supplied ordinary markers; **not handled** for supplied raw JSON. Its JSON traversal includes supplied references. | **normal API use**. [View:173][fallback]; `DeferredLogstashMarker.java:59,65,94,99`; `LogstashMarker.java:137`. |
| `LogstashBasicMarker` — package-private | `Marker` only | **handled** for its stock name/reference-name rendering, although the view leaves it unchanged. It has no arbitrary value `toString` call. | **handled** as ordinary marker-name text through the tags provider. | **unusual composition** to instantiate this internal class directly. [View:129][passthrough]; `LogstashBasicMarker.toString`; `TagsJsonProvider.java:76`. [Basic-marker source][basic]. |
| `SingleFieldAppendingMarker` — abstract | Both | **handled** for stock subclasses through field extraction and guarded rendering. Arbitrary subclass behavior is **not handled** universally. | **not handled** universally: its abstract `writeFieldValue` permits raw output; see its concrete subclasses above. | **unusual composition** for consumer subclasses. [View:156][field]; `SingleFieldAppendingMarker.java:84,108`. [Base source][single]. |
| `LogstashMarker` — abstract | `Marker` | **handled** for stock subclasses through guarded own-text/reference traversal. Custom diagnostic-producing overrides are **not handled** universally. | **not handled** universally: `writeTo` is an unrestricted extension point. | **unusual composition** for consumer subclasses. [View:119][marker], [View:173][fallback]; `LogstashMarker.writeTo`. [Base source][logstash]. |

`Kv` is neither interface implementation, so it is outside the 8.1 inventory. Its platform message branch is at [line 112][kv]; its new nested JSON serializer is at [line 21][serializer].

## Regressions

1. **Depth masking starts one value level earlier — unusual composition.**

   ```java
   kv("payload", wrap(1793001600123L, 63))
   ```

   In `7a7c962`, the structured value starts at depth zero, so this epoch reaches depth 63 and remains visible. In `b61cf2e`, [line 161][field] starts it at depth one, so the epoch reaches [line 223][limit] at depth 64 and becomes `****`. The same shift affects `Kv` and `entries`.

   The depth-16 acceptance remains valid. This is a boundary change to the previously accepted depth exception, not an ordinary shallow-value false positive.

2. **Unchanged array contents can become identity text — unusual composition.**

   ```java
   kv("payload", new Object[]{
       kv("phase", "ready"),
       new int[]{1, 2}
   })
   ```

   Previously, the unchanged outer array reached `Arrays.deepToString`, producing:

   ```text
   payload=[phase=ready, [1, 2]]
   ```

   Now [line 217][nested] converts the inner marker to a string. That marks the outer array changed, causing [line 250][arraycopy] to return a `List`. Its rendering at [line 196][value] produces:

   ```text
   payload=[phase=ready, [I@<identity>]
   ```

   The primitive-array contents disappear despite no sanitization changing them.

3. **`fields(Kv)` silently loses JSON fields — unusual composition.**

   ```java
   fields(Kv.of("orderId", "ORD-42"))
   ```

   Previously Jackson could unwrap the record’s `key` and `value` properties. The globally registered [KvJsonSerializer.java:14][serializer] supplies no unwrapping serializer. Encoder 8.1’s `ObjectFieldsAppendingMarker.java:176` explicitly skips serializers whose `isUnwrappingSerializer()` is false, so this argument now contributes **no JSON fields**.

   This also occurs with redaction disabled because registration is unconditional at [PlatformJsonEncoder.java:84][registration]. It is additional behavior beyond the documented nested-`Kv` shape change.

No additional shallow R3.3 false positive, newly introduced credential leak, asymptotic text-scan regression or escaping stack-overflow regression was established. The raw-JSON and nested-`fields` leaks above are pre-existing.

## Verification boundary

Verified checkout `b61cf2ef8d182de06e6ccaddaa139b25df9f541b` against `7a7c962`, relevant source/tests and AC5 requirements. Enumerated implementations from the cached 8.1 JAR’s class hierarchy; inspected method bytecode and its source line tables. Upstream line numbers above use those bytecode line tables.

**No Maven/JUnit execution, Java timing run or captured-stderr reproduction completed.** The filesystem is read-only; JShell again failed with `Error loading java.security file`. `javap` produced the cited bytecode but also reported archive-access errors. These are source/bytecode-established verdicts, not independently passing integration-test results.

[encoder]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java:96
[registration]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java:84
[serializer]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java:21
[credential]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/CredentialFieldMasker.java:18
[patterns]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:75
[nested]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:217
[field]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:156
[guard]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:78
[chain]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:134
[fields]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:167
[entries]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:163
[fallback]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:173
[passthrough]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:129
[marker]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:119
[kv]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:112
[limit]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:223
[arraycopy]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:250
[value]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v4/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:196
[raw]: https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/marker/RawJsonAppendingMarker.java
[masking]: https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/mask/MaskingJsonGenerator.java
[logstash]: https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/marker/LogstashMarker.java
[arguments]: https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/argument/StructuredArguments.java
[basic]: https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/marker/LogstashBasicMarker.java
[single]: https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/marker/SingleFieldAppendingMarker.java

## Fix diff b61cf2e..edcd515 (docs/production-readiness and tooling/hardening excluded)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index cc63a53..8da302c 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -53,8 +53,9 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   or cyclic, read `****` there. A value whose `toString()` throws reads `[unrenderable]`, and its
   exception is no longer reported to stderr or the Logback status manager. A `Kv` nested in a
   structured value is written `{"<key>": value}` (was `{"key": …, "value": …}`; also with redaction
-  off), so credential names
-  mask it.
+  off), so credential names mask it; `fields(kv)` writes its one field. `raw(...)` JSON is now masked:
+  it is parsed and written back through the masks, so its JSON field is re-serialized (whitespace
+  and key formatting normalized) when redaction is on.
 - `platform.capability.active` is now registered when Boot creates the meter registry (the
   auto-configuration was evaluated before Boot's registry existed and backed off).
 - The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
diff --git a/docs/llms-full.txt b/docs/llms-full.txt
index 8412cd0..81cac25 100644
--- a/docs/llms-full.txt
+++ b/docs/llms-full.txt
@@ -841,7 +841,9 @@ is watching.
   field in the JSON output only; in `message` they keep their `toString()`, over which the platform
   masks still run (`fields(bean)` likewise). A `Kv` or marker nested inside a value is treated like a
   top-level one (a nested `Kv` is written `{"<key>": value}` in JSON, so its credential name masks it);
-  chained markers (`append(...).and(...)`) render each one, `, `-joined. In `message`, a value nested
+  chained markers (`append(...).and(...)`) render each one, `, `-joined. `raw(...)` JSON is parsed and
+  masked like any other structured value, in the JSON field and in `message` (escaped field names
+  included); raw text that is not JSON gets the pattern masks only. In `message`, a value nested
   more than 64 levels deep, or
   a container that contains itself, reads `****`. An argument whose rendering throws reads
   `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index ff833d9..f70b0c6 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -61,7 +61,9 @@ is watching.
   field in the JSON output only; in `message` they keep their `toString()`, over which the platform
   masks still run (`fields(bean)` likewise). A `Kv` or marker nested inside a value is treated like a
   top-level one (a nested `Kv` is written `{"<key>": value}` in JSON, so its credential name masks it);
-  chained markers (`append(...).and(...)`) render each one, `, `-joined. In `message`, a value nested
+  chained markers (`append(...).and(...)`) render each one, `, `-joined. `raw(...)` JSON is parsed and
+  masked like any other structured value, in the JSON field and in `message` (escaped field names
+  included); raw text that is not JSON gets the pattern masks only. In `message`, a value nested
   more than 64 levels deep, or
   a container that contains itself, reads `****`. An argument whose rendering throws reads
   `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
index 3cb2690..53295e7 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
@@ -4,9 +4,11 @@ import ae.gov.dubaicustoms.platform.logging.Kv;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.CredentialFieldMasker;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.KvJsonSerializer;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformValueMasker;
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.RawJsonMaskingGenerator;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.SanitizedMessageView;
 import ch.qos.logback.classic.spi.ILoggingEvent;
 import com.fasterxml.jackson.core.JsonFactory;
+import com.fasterxml.jackson.core.JsonGenerator;
 import com.fasterxml.jackson.databind.ObjectMapper;
 import com.fasterxml.jackson.databind.module.SimpleModule;
 import java.io.IOException;
@@ -14,6 +16,7 @@ import java.io.OutputStream;
 import java.util.Arrays;
 import java.util.List;
 import net.logstash.logback.decorate.JsonFactoryDecorator;
+import net.logstash.logback.decorate.JsonGeneratorDecorator;
 import net.logstash.logback.encoder.LogstashEncoder;
 import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
 
@@ -95,9 +98,16 @@ public class PlatformJsonEncoder extends LogstashEncoder {
         if (redactionEnabled) {
             MaskingJsonGeneratorDecorator masking = new MaskingJsonGeneratorDecorator();
             masking.addFieldMasker(new CredentialFieldMasker());
-            masking.addValueMasker(new PlatformValueMasker(PlatformValueMasker.compile(redactionPatterns)));
+            PlatformValueMasker masker = new PlatformValueMasker(PlatformValueMasker.compile(redactionPatterns));
+            masking.addValueMasker(masker);
             masking.start();
-            setJsonGeneratorDecorator(masking);
+            // Outside the masking generator: raw(...) JSON is parsed and written back through it.
+            setJsonGeneratorDecorator(new JsonGeneratorDecorator() {
+                @Override
+                public JsonGenerator decorate(JsonGenerator generator) {
+                    return new RawJsonMaskingGenerator(masking.decorate(generator), masker);
+                }
+            });
         }
         super.start();
     }
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java
index 5555141..89d49b3 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java
@@ -2,14 +2,17 @@ package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
 
 import ae.gov.dubaicustoms.platform.logging.Kv;
 import com.fasterxml.jackson.core.JsonGenerator;
+import com.fasterxml.jackson.databind.JsonSerializer;
 import com.fasterxml.jackson.databind.SerializerProvider;
 import com.fasterxml.jackson.databind.ser.std.StdSerializer;
+import com.fasterxml.jackson.databind.util.NameTransformer;
 import java.io.IOException;
 
 /**
  * Writes a {@link Kv} nested in a structured value as {@code {"<key>": <value>}} rather than as a
  * record ({@code {"key": …, "value": …}}): the key becomes a JSON field name, so credential-name
- * masking applies to it as to any other field. Stateless and thread-safe.
+ * masking applies to it as to any other field. Unwrapped ({@code fields(kv)}), it contributes the one
+ * field {@code "<key>": <value>}. Stateless and thread-safe.
  */
 public final class KvJsonSerializer extends StdSerializer<Kv> {
 
@@ -20,8 +23,27 @@ public final class KvJsonSerializer extends StdSerializer<Kv> {
     @Override
     public void serialize(Kv kv, JsonGenerator generator, SerializerProvider provider) throws IOException {
         generator.writeStartObject();
+        writeField(kv, generator, provider);
+        generator.writeEndObject();
+    }
+
+    @Override
+    public JsonSerializer<Kv> unwrappingSerializer(NameTransformer unwrapper) {
+        return new StdSerializer<>(Kv.class) {
+            @Override
+            public void serialize(Kv kv, JsonGenerator generator, SerializerProvider provider) throws IOException {
+                writeField(kv, generator, provider);
+            }
+
+            @Override
+            public boolean isUnwrappingSerializer() {
+                return true;
+            }
+        };
+    }
+
+    private static void writeField(Kv kv, JsonGenerator generator, SerializerProvider provider) throws IOException {
         generator.writeFieldName(kv.key());
         provider.defaultSerializeValue(kv.value(), generator);
-        generator.writeEndObject();
     }
 }
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
new file mode 100644
index 0000000..ed02fd2
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJsonMaskingGenerator.java
@@ -0,0 +1,54 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import com.fasterxml.jackson.core.JsonGenerator;
+import com.fasterxml.jackson.core.JsonProcessingException;
+import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
+import com.fasterxml.jackson.databind.JsonNode;
+import com.fasterxml.jackson.databind.ObjectMapper;
+import java.io.IOException;
+
+/**
+ * Wraps the masking generator so a raw JSON value ({@code raw(...)}) is masked like any other:
+ * Logstash's masking generator forwards {@code writeRawValue} unread. The raw text is parsed and
+ * written back as a tree through this generator, so field-name and value masks apply to its contents
+ * (escaped field names included); text that is not JSON is written raw after the pattern masks. One
+ * instance per event's generator; not thread-safe, like the generator it wraps.
+ */
+public final class RawJsonMaskingGenerator extends JsonGeneratorDelegate {
+
+    private static final ObjectMapper RAW = new ObjectMapper();
+
+    private final PlatformValueMasker masker;
+
+    /**
+     * @param delegate the masking generator; never {@code null}
+     * @param masker the pattern masks for raw text that is not JSON; never {@code null}
+     */
+    public RawJsonMaskingGenerator(JsonGenerator delegate, PlatformValueMasker masker) {
+        super(delegate, false);
+        this.masker = masker;
+    }
+
+    @Override
+    public void writeRawValue(String text) throws IOException {
+        JsonNode tree;
+        try {
+            tree = RAW.readTree(text);
+        } catch (JsonProcessingException e) {
+            super.writeRawValue(masker.maskPatterns(text));
+            return;
+        }
+        // Not delegated: the codec writes the tree through this generator, so the masks see every field.
+        writeTree(tree);
+    }
+
+    @Override
+    public void writeRawValue(String text, int offset, int len) throws IOException {
+        writeRawValue(text.substring(offset, offset + len));
+    }
+
+    @Override
+    public void writeRawValue(char[] text, int offset, int len) throws IOException {
+        writeRawValue(new String(text, offset, len));
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
index d8fd26f..906c84b 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -2,6 +2,8 @@ package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
 
 import ae.gov.dubaicustoms.platform.logging.Kv;
 import ch.qos.logback.classic.spi.ILoggingEvent;
+import com.fasterxml.jackson.core.JsonProcessingException;
+import com.fasterxml.jackson.databind.ObjectMapper;
 import java.lang.reflect.Field;
 import java.lang.reflect.InvocationTargetException;
 import java.lang.reflect.Method;
@@ -19,6 +21,7 @@ import java.util.Set;
 import net.logstash.logback.marker.LogstashMarker;
 import net.logstash.logback.marker.MapEntriesAppendingMarker;
 import net.logstash.logback.marker.ObjectFieldsAppendingMarker;
+import net.logstash.logback.marker.RawJsonAppendingMarker;
 import net.logstash.logback.marker.SingleFieldAppendingMarker;
 import org.slf4j.Marker;
 import org.slf4j.helpers.MessageFormatter;
@@ -50,6 +53,7 @@ public final class SanitizedMessageView {
     private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");
     private static final Field OBJECT_FIELDS = field(ObjectFieldsAppendingMarker.class, "object");
     private static final Method SELF_TEXT = accessor(LogstashMarker.class, "toStringSelf");
+    private static final ObjectMapper RAW = new ObjectMapper();
 
     private SanitizedMessageView() {
     }
@@ -113,7 +117,7 @@ public final class SanitizedMessageView {
             if (PlatformValueMasker.isCredentialKey(kv.key())) {
                 return kv.key() + "=" + PlatformValueMasker.MASK;
             }
-            Object clean = sanitizedTree(kv.key(), kv.value(), depth + 1, path);
+            Object clean = sanitizedTree(kv.key(), kv.value(), depth, path);
             return clean == kv.value() ? guardedToString(argument) : kv.key() + "=" + valueText(clean);
         }
         if (argument instanceof LogstashMarker marker) {
@@ -158,21 +162,47 @@ public final class SanitizedMessageView {
             if (PlatformValueMasker.isCredentialKey(name) || FIELD_VALUE == null) {
                 return render(field, name, PlatformValueMasker.MASK);
             }
-            return render(field, name, valueText(sanitizedTree(name, read(FIELD_VALUE, field), depth + 1, path)));
+            Object value = read(FIELD_VALUE, field);
+            if (marker instanceof RawJsonAppendingMarker && value instanceof String raw) {
+                return render(field, name, rawText(name, raw, depth, path));
+            }
+            return render(field, name, valueText(sanitizedTree(name, value, depth, path)));
         }
         if (marker instanceof MapEntriesAppendingMarker entries) {
             return MAP_ENTRIES == null ? PlatformValueMasker.MASK
-                    : String.valueOf(sanitizedTree(null, read(MAP_ENTRIES, entries), depth + 1, path));
+                    : String.valueOf(sanitizedTree(null, read(MAP_ENTRIES, entries), depth, path));
         }
         if (marker instanceof ObjectFieldsAppendingMarker fields) {
-            // fields(bean): the JSON fields are sanitized one by one; the message keeps the bean's text.
-            return OBJECT_FIELDS == null ? PlatformValueMasker.MASK : valueText(read(OBJECT_FIELDS, fields));
+            // fields(bean): the JSON fields are sanitized one by one; the message keeps the bean's text
+            // (a collection or map of structured arguments is traversed like any value).
+            return OBJECT_FIELDS == null ? PlatformValueMasker.MASK
+                    : valueText(sanitizedTree(null, read(OBJECT_FIELDS, fields), depth, path));
         }
         // Other markers (the empty marker, consumer subclasses) render their own text; in encoder 8.1 only
         // the two types above go through StructuredArguments.toString.
         return SELF_TEXT == null ? "" : String.valueOf(read(SELF_TEXT, marker));
     }
 
+    // raw(...): parsed, so escaped field names are seen; masked contents are shown as JSON again. Text
+    // that is not JSON is shown as is (the pattern masks still run over the message).
+    private static String rawText(String name, String raw, int depth, Set<Object> path) {
+        Object parsed;
+        try {
+            parsed = RAW.readValue(raw, Object.class);
+        } catch (JsonProcessingException e) {
+            return raw;
+        }
+        Object clean = sanitizedTree(name, parsed, depth, path);
+        if (clean == parsed) {
+            return raw;
+        }
+        try {
+            return RAW.writeValueAsString(clean);
+        } catch (JsonProcessingException e) {
+            return PlatformValueMasker.MASK;
+        }
+    }
+
     // A structured argument is rendered exactly once, here, under the caller's guard: handing it back to
     // SLF4J would render it a second time, outside the guard.
     private static Object guardedToString(Object argument) {
@@ -217,7 +247,7 @@ public final class SanitizedMessageView {
         if (value instanceof Kv || value instanceof LogstashMarker) {
             // A structured argument nested in a value: rendered here like a top-level one, never through
             // its own toString.
-            return displayed(value, depth, path);
+            return displayed(value, depth + 1, path);
         }
         boolean container = value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof Object[];
         if (depth >= MAX_DEPTH || container && path.contains(value)) {
@@ -247,7 +277,11 @@ public final class SanitizedMessageView {
                 copy.add(clean);
             }
             path.remove(value);
-            return changed ? copy : value;
+            if (!changed) {
+                return value;
+            }
+            // An array stays an array, so it is shown by content (deepToString) as before.
+            return value instanceof Object[] ? copy.toArray() : copy;
         }
         if (key != null && (value instanceof CharSequence || value instanceof Number)
                 && !LogSanitizerRegistry.current().isEmpty()) {
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
index b38de0f..2d3b0b4 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
@@ -173,6 +173,48 @@ class EncoderRedactionTest {
                 .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
     }
 
+    @Test
+    void rawJsonIsMaskedLikeAnyOtherStructuredValue() throws Exception {
+        // Verification pass 4: raw(...) went through writeRawValue, which the masking generator forwards.
+        String line = encode(event("{} {}", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.raw("payload", "{\"password\":\"hunter2\",\"region\":\"dxb\"}"),
+                net.logstash.logback.argument.StructuredArguments.raw("escaped", "{\"pass\\u0077ord\":\"hunter3\"}")}));
+        assertThat(line).doesNotContain("hunter2").doesNotContain("hunter3");
+        JsonNode node = JSON.readTree(line);
+        assertThat(node.get("payload").get("password").asText()).isEqualTo("****");
+        assertThat(node.get("payload").get("region").asText()).isEqualTo("dxb");
+    }
+
+    @Test
+    void fieldsOfAKvKeepsItsField() throws Exception {
+        // Verification pass 4 regression 3: fields(...) needs an unwrapping serializer.
+        String line = encode(event("stored", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.fields(Kv.of("orderId", "ORD-42"))}));
+        assertThat(JSON.readTree(line).get("orderId").asText()).isEqualTo("ORD-42");
+    }
+
+    @Test
+    void unchangedArrayContentsAndTheDepthBoundaryAreKept() throws Exception {
+        // Verification pass 4 regressions 1 and 2.
+        String line = encode(event("{} {}", new Object[] {
+                kv("payload", new Object[] {kv("phase", "ready"), new int[] {1, 2}}),
+                kv("deep", wrap(1793001600123L, 63))}));
+        assertThat(JSON.readTree(line).get("message").asText())
+                .startsWith("payload=[phase=ready, [1, 2]] deep=").contains("1793001600123");
+    }
+
+    @Test
+    void markersInsideFieldsAreRenderedUnderTheGuard() throws Exception {
+        // Verification pass 4: fields(List.of(marker)) rendered the nested marker through its own toString.
+        int before = context.getStatusManager().getCount();
+        String line = encode(event("{} {}", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.fields(List.of(v("password", "hunter2"))),
+                net.logstash.logback.argument.StructuredArguments.fields(List.of(kv("entity", new HostileBean())))}));
+        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("[****] [unrenderable]");
+        assertThat(context.getStatusManager().getCopyOfStatusList().subList(before, context.getStatusManager().getCount()))
+                .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
+    }
+
     /** Serializable as JSON ({@code {"id":"b-1"}}); its toString throws with a secret in the message. */
     public static final class HostileBean {
 
```
