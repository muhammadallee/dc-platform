# Verification pass 4 — DC Platform hardening Phase 2 (Logging + Observability)

Your working directory is a checkout of commit `b61cf2e`. Your previous pass on `7a7c962` (below)
accepted the scheme-overlap change, compound names and regressions 1–3, and rejected AC5 with two
regressions (nested-marker status leak; chained markers losing references). The diff below:

- renders a `Kv` or Logstash marker nested inside a structured value through the message view itself
  (same guard, depth and cycle path), never its own `toString`;
- renders chained markers as `LogstashMarker.toString` does (own text, then each reference, `", "`-joined
  — appended `", "` before each reference when a separator is pending; non-empty text appended);
- serializes a `Kv` nested in a structured value as `{"<key>": value}` (`KvJsonSerializer`, registered
  on the encoder's ObjectMapper after any configured `JsonFactoryDecorator`), found while fixing: as a
  record its credential name escaped the field masker;
- pins that `StructuredArguments.defer(...)` leaks nothing (no `toString` in 8.1; JSON masked).

Answer as a checklist, not an open hunt:

1. For AC5 and your previous regressions 1–2: **accept** or **reject**, with file:line and a concrete
   failing input for any reject.
2. List every `StructuredArgument` and `org.slf4j.Marker` implementation in logstash-logback-encoder
   8.1, and for each state **handled** or **not handled** by `SanitizedMessageView` (message) and by
   the encoder (JSON), with file:line. "Handled" means: credential names masked, a throwing
   `toString` contained without reaching stderr or the Logback status manager.
3. Label every finding **normal API use** (a documented `StructuredArguments`/`Markers`/`Kv` call a
   service would write) or **unusual composition** (e.g. markers nested in collections inside other
   markers, custom subclasses, reflection failure).
4. Report any regression this diff introduces (R3.3 common values, linearity, stack safety, leaks,
   message changes for unchanged arguments), same evidence standard.

Output a table `| Item | Verdict | Class | Reason |`, then the implementation inventory table, then
`## Regressions` (or "none established"), then `## Verification boundary`.

## Your previous verification pass

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

## Fix diff 7a7c962..b61cf2e (docs/production-readiness and the tooling/hardening consumer harness excluded)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 258ac9b..cc63a53 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -51,7 +51,10 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   numbers, maps, collections and arrays, in the JSON field and in the formatted `message` alike.
   Credential-keyed arguments read `password=****` in `message`; values nested deeper than 64 levels,
   or cyclic, read `****` there. A value whose `toString()` throws reads `[unrenderable]`, and its
-  exception is no longer reported to stderr or the Logback status manager.
+  exception is no longer reported to stderr or the Logback status manager. A `Kv` nested in a
+  structured value is written `{"<key>": value}` (was `{"key": …, "value": …}`; also with redaction
+  off), so credential names
+  mask it.
 - `platform.capability.active` is now registered when Boot creates the meter registry (the
   auto-configuration was evaluated before Boot's registry existed and backed off).
 - The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
diff --git a/docs/llms-full.txt b/docs/llms-full.txt
index 3d7551a..8412cd0 100644
--- a/docs/llms-full.txt
+++ b/docs/llms-full.txt
@@ -839,7 +839,10 @@ is watching.
   arrays, nested in any combination; each value is sanitized under its own key (map entries under
   theirs, array elements under the enclosing field's). Other objects (beans) are sanitized field by
   field in the JSON output only; in `message` they keep their `toString()`, over which the platform
-  masks still run (`fields(bean)` likewise). In `message`, a value nested more than 64 levels deep, or
+  masks still run (`fields(bean)` likewise). A `Kv` or marker nested inside a value is treated like a
+  top-level one (a nested `Kv` is written `{"<key>": value}` in JSON, so its credential name masks it);
+  chained markers (`append(...).and(...)`) render each one, `, `-joined. In `message`, a value nested
+  more than 64 levels deep, or
   a container that contains itself, reads `****`. An argument whose rendering throws reads
   `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
   status).
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index 1ffb838..ff833d9 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -59,7 +59,10 @@ is watching.
   arrays, nested in any combination; each value is sanitized under its own key (map entries under
   theirs, array elements under the enclosing field's). Other objects (beans) are sanitized field by
   field in the JSON output only; in `message` they keep their `toString()`, over which the platform
-  masks still run (`fields(bean)` likewise). In `message`, a value nested more than 64 levels deep, or
+  masks still run (`fields(bean)` likewise). A `Kv` or marker nested inside a value is treated like a
+  top-level one (a nested `Kv` is written `{"<key>": value}` in JSON, so its credential name masks it);
+  chained markers (`append(...).and(...)`) render each one, `, `-joined. In `message`, a value nested
+  more than 64 levels deep, or
   a container that contains itself, reads `****`. An argument whose rendering throws reads
   `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
   status).
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
index edc542a..3cb2690 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
@@ -1,13 +1,19 @@
 package ae.gov.dubaicustoms.platform.logging.autoconfigure;
 
+import ae.gov.dubaicustoms.platform.logging.Kv;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.CredentialFieldMasker;
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.KvJsonSerializer;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformValueMasker;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.SanitizedMessageView;
 import ch.qos.logback.classic.spi.ILoggingEvent;
+import com.fasterxml.jackson.core.JsonFactory;
+import com.fasterxml.jackson.databind.ObjectMapper;
+import com.fasterxml.jackson.databind.module.SimpleModule;
 import java.io.IOException;
 import java.io.OutputStream;
 import java.util.Arrays;
 import java.util.List;
+import net.logstash.logback.decorate.JsonFactoryDecorator;
 import net.logstash.logback.encoder.LogstashEncoder;
 import net.logstash.logback.mask.MaskingJsonGeneratorDecorator;
 
@@ -74,6 +80,18 @@ public class PlatformJsonEncoder extends LogstashEncoder {
             PLATFORM_MDC_KEYS.forEach(this::addIncludeMdcKeyName);
             keys.stream().filter(k -> !PLATFORM_MDC_KEYS.contains(k)).forEach(this::addIncludeMdcKeyName);
         }
+        // A Kv nested in a structured value is written {"<key>": value}, after any decorator already set.
+        JsonFactoryDecorator configured = getJsonFactoryDecorator();
+        setJsonFactoryDecorator(new JsonFactoryDecorator() {
+            @Override
+            public JsonFactory decorate(JsonFactory factory) {
+                JsonFactory decorated = configured == null ? factory : configured.decorate(factory);
+                if (decorated.getCodec() instanceof ObjectMapper mapper) {
+                    mapper.registerModule(new SimpleModule("platform-kv").addSerializer(Kv.class, new KvJsonSerializer()));
+                }
+                return decorated;
+            }
+        });
         if (redactionEnabled) {
             MaskingJsonGeneratorDecorator masking = new MaskingJsonGeneratorDecorator();
             masking.addFieldMasker(new CredentialFieldMasker());
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java
new file mode 100644
index 0000000..5555141
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/KvJsonSerializer.java
@@ -0,0 +1,27 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import ae.gov.dubaicustoms.platform.logging.Kv;
+import com.fasterxml.jackson.core.JsonGenerator;
+import com.fasterxml.jackson.databind.SerializerProvider;
+import com.fasterxml.jackson.databind.ser.std.StdSerializer;
+import java.io.IOException;
+
+/**
+ * Writes a {@link Kv} nested in a structured value as {@code {"<key>": <value>}} rather than as a
+ * record ({@code {"key": …, "value": …}}): the key becomes a JSON field name, so credential-name
+ * masking applies to it as to any other field. Stateless and thread-safe.
+ */
+public final class KvJsonSerializer extends StdSerializer<Kv> {
+
+    public KvJsonSerializer() {
+        super(Kv.class);
+    }
+
+    @Override
+    public void serialize(Kv kv, JsonGenerator generator, SerializerProvider provider) throws IOException {
+        generator.writeStartObject();
+        generator.writeFieldName(kv.key());
+        provider.defaultSerializeValue(kv.value(), generator);
+        generator.writeEndObject();
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
index b1520a3..d8fd26f 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -16,9 +16,11 @@ import java.util.LinkedHashMap;
 import java.util.List;
 import java.util.Map;
 import java.util.Set;
+import net.logstash.logback.marker.LogstashMarker;
 import net.logstash.logback.marker.MapEntriesAppendingMarker;
 import net.logstash.logback.marker.ObjectFieldsAppendingMarker;
 import net.logstash.logback.marker.SingleFieldAppendingMarker;
+import org.slf4j.Marker;
 import org.slf4j.helpers.MessageFormatter;
 
 /**
@@ -47,6 +49,7 @@ public final class SanitizedMessageView {
     private static final Field FORMAT_PATTERN = field(SingleFieldAppendingMarker.class, "messageFormatPattern");
     private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");
     private static final Field OBJECT_FIELDS = field(ObjectFieldsAppendingMarker.class, "object");
+    private static final Method SELF_TEXT = accessor(LogstashMarker.class, "toStringSelf");
 
     private SanitizedMessageView() {
     }
@@ -101,35 +104,73 @@ public final class SanitizedMessageView {
 
     // The text to show in the argument's place (an unstructured argument is left to SLF4J).
     private static Object displayed(Object argument) {
+        return displayed(argument, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
+    }
+
+    // Also used for a Kv or marker nested inside a structured value, with the enclosing depth and path.
+    private static Object displayed(Object argument, int depth, Set<Object> path) {
         if (argument instanceof Kv kv) {
             if (PlatformValueMasker.isCredentialKey(kv.key())) {
                 return kv.key() + "=" + PlatformValueMasker.MASK;
             }
-            Object clean = sanitizedTree(kv.key(), kv.value(), 0);
+            Object clean = sanitizedTree(kv.key(), kv.value(), depth + 1, path);
             return clean == kv.value() ? guardedToString(argument) : kv.key() + "=" + valueText(clean);
         }
-        if (argument instanceof SingleFieldAppendingMarker marker) {
-            String name = marker.getFieldName();
-            if (PlatformValueMasker.isCredentialKey(name)) {
-                return render(marker, name, PlatformValueMasker.MASK);
+        if (argument instanceof LogstashMarker marker) {
+            if (depth >= MAX_DEPTH || !path.add(marker)) {
+                return PlatformValueMasker.MASK;
+            }
+            try {
+                return markerText(marker, depth, path);
+            } finally {
+                path.remove(marker);
+            }
+        }
+        return argument;
+    }
+
+    // As LogstashMarker.toString — its own text, then each referenced marker's, ", "-joined — rendered
+    // here even when nothing changes: Logstash's own rendering goes through StructuredArguments.toString.
+    private static String markerText(LogstashMarker marker, int depth, Set<Object> path) {
+        String self = selfText(marker, depth, path);
+        if (!marker.hasReferences()) {
+            return self;
+        }
+        StringBuilder text = new StringBuilder(self);
+        boolean separator = !self.isEmpty();
+        for (Marker reference : marker) {
+            if (separator) {
+                text.append(", ");
             }
-            if (FIELD_VALUE == null) {
-                return guardedToString(argument);
+            String referenceText = String.valueOf(displayed(reference, depth + 1, path));
+            if (!referenceText.isEmpty()) {
+                text.append(referenceText);
+                separator = true;
+            }
+        }
+        return text.toString();
+    }
+
+    // A reflective accessor missing (another encoder version) shows the value masked, never unrendered.
+    private static String selfText(LogstashMarker marker, int depth, Set<Object> path) {
+        if (marker instanceof SingleFieldAppendingMarker field) {
+            String name = field.getFieldName();
+            if (PlatformValueMasker.isCredentialKey(name) || FIELD_VALUE == null) {
+                return render(field, name, PlatformValueMasker.MASK);
             }
-            // Rendered here even when unchanged: the marker's own toString goes through
-            // StructuredArguments.toString.
-            return render(marker, name, valueText(sanitizedTree(name, read(FIELD_VALUE, marker), 0)));
+            return render(field, name, valueText(sanitizedTree(name, read(FIELD_VALUE, field), depth + 1, path)));
         }
-        if (argument instanceof MapEntriesAppendingMarker entries && MAP_ENTRIES != null) {
-            Object map = read(MAP_ENTRIES, entries);
-            Object clean = sanitizedTree(null, map, 0);
-            return clean == map ? guardedToString(argument) : String.valueOf(clean);
+        if (marker instanceof MapEntriesAppendingMarker entries) {
+            return MAP_ENTRIES == null ? PlatformValueMasker.MASK
+                    : String.valueOf(sanitizedTree(null, read(MAP_ENTRIES, entries), depth + 1, path));
         }
-        if (argument instanceof ObjectFieldsAppendingMarker fields && OBJECT_FIELDS != null) {
+        if (marker instanceof ObjectFieldsAppendingMarker fields) {
             // fields(bean): the JSON fields are sanitized one by one; the message keeps the bean's text.
-            return valueText(read(OBJECT_FIELDS, fields));
+            return OBJECT_FIELDS == null ? PlatformValueMasker.MASK : valueText(read(OBJECT_FIELDS, fields));
         }
-        return argument;
+        // Other markers (the empty marker, consumer subclasses) render their own text; in encoder 8.1 only
+        // the two types above go through StructuredArguments.toString.
+        return SELF_TEXT == null ? "" : String.valueOf(read(SELF_TEXT, marker));
     }
 
     // A structured argument is rendered exactly once, here, under the caller's guard: handing it back to
@@ -173,6 +214,11 @@ public final class SanitizedMessageView {
         if (value == null) {
             return null;
         }
+        if (value instanceof Kv || value instanceof LogstashMarker) {
+            // A structured argument nested in a value: rendered here like a top-level one, never through
+            // its own toString.
+            return displayed(value, depth, path);
+        }
         boolean container = value instanceof Map<?, ?> || value instanceof Collection<?> || value instanceof Object[];
         if (depth >= MAX_DEPTH || container && path.contains(value)) {
             // Deeper than the traversal goes, or a cycle: shown masked rather than unchecked.
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
index 6996df0..b38de0f 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
@@ -131,6 +131,48 @@ class EncoderRedactionTest {
                 .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
     }
 
+    @Test
+    void aMarkerNestedInAValueIsRenderedUnderTheSameGuard() throws Exception {
+        // Verification pass 3: a marker inside a structured value went through its own toString.
+        int before = context.getStatusManager().getCount();
+        String line = encode(event("{} {}", new Object[] {
+                kv("payload", wrap(kv("entity", new HostileBean()), 16)),
+                kv("wrapper", List.of(kv("password", "hunter2"), Kv.of("token", "tk-1")))}));
+        // (The text masks then take a bare value up to whitespace , ; or } — the closing ] included.)
+        assertThat(JSON.readTree(line).get("message").asText())
+                .startsWith("[unrenderable] wrapper=[password=****, token=****")
+                .doesNotContain("hunter2").doesNotContain("tk-1");
+        // A nested Kv is written {"token": …}, so the credential-name field masker applies.
+        assertThat(line).doesNotContain("hunter2").doesNotContain("tk-1");
+        assertThat(JSON.readTree(line).get("wrapper").get(1).get("token").asText()).isEqualTo("****");
+        assertThat(context.getStatusManager().getCopyOfStatusList().subList(before, context.getStatusManager().getCount()))
+                .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
+    }
+
+    @Test
+    void chainedMarkersKeepTheirReferencesInTheMessage() throws Exception {
+        // Verification pass 3: LogstashMarker.toString appends referenced markers, ", "-joined.
+        net.logstash.logback.marker.LogstashMarker chained = net.logstash.logback.marker.Markers.append("orderId", "ORD-42")
+                .and(net.logstash.logback.marker.Markers.append("phase", "ready"))
+                .and(net.logstash.logback.marker.Markers.append("password", "hunter2"));
+        String line = encode(event("{}", new Object[] {chained}));
+        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("orderId=ORD-42, phase=ready, password=****");
+    }
+
+    @Test
+    void aDeferredArgumentLeaksNothing() throws Exception {
+        // Pre-pass-4 sweep: defer(...) is the one StructuredArgument that is not a marker. Encoder 8.1
+        // gives it no toString (the message shows its identity), and the JSON masks the supplied field.
+        int before = context.getStatusManager().getCount();
+        String line = encode(event("{} {}", new Object[] {
+                net.logstash.logback.argument.StructuredArguments.defer(() -> v("password", "hunter2")),
+                net.logstash.logback.argument.StructuredArguments.defer(() -> kv("entity", new HostileBean()))}));
+        assertThat(line).doesNotContain("hunter2").doesNotContain("hunter5");
+        assertThat(JSON.readTree(line).get("password").asText()).isEqualTo("****");
+        assertThat(context.getStatusManager().getCopyOfStatusList().subList(before, context.getStatusManager().getCount()))
+                .noneSatisfy(status -> assertThat(String.valueOf(status.getThrowable())).contains("hunter5"));
+    }
+
     /** Serializable as JSON ({@code {"id":"b-1"}}); its toString throws with a secret in the message. */
     public static final class HostileBean {
 
```
