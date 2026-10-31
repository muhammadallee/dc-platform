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
