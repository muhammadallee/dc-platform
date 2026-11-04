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
