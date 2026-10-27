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
