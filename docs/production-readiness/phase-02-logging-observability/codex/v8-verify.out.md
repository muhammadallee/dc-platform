| Item | Verdict | Class | Reason |
|---|---|---|---|
| AC5 — escaped tab after `Bearer` | **accept** | **normal API use** | For `raw("payload", "\"Bearer\\thunter2\"")`, [SanitizedMessageView.java:195][raw-message] now renders the decoded string directly. The tab reaches the Bearer mask as whitespace; `hunter2` is masked in both views. |
| Previous regression 1 — duplicates bypass credential masking | **accept** | **normal API use** | `raw("payload", "{\"pass\\u0077ord\":\"hunter2\",\"phase\":1,\"phase\":2}")` stays on the structural path. [RawJson.java:57][raw-object] retains both members and decodes `password`; [SanitizedMessageView.java:200][raw-clean] masks its value. Both `phase` members survive. |
| Previous regression 2 — primitive-array PAN in message | **accept** | **unusual composition** | For `kv("payload", List.of(kv("phase", "ready"), new double[]{4111111111111111.0d}))`, [SanitizedMessageView.java:241][array] routes the array through the numeric check at [line 256][numbers]. The message becomes `payload=[phase=ready, [****]]`. |
| Previous regression 3 — numeric masking skips sanitizers | **accept** | **normal API use** | With `(key, value) -> "card".equals(key) ? "ending-1111" : value`, `kv("card", 4111111111111111L)` now produces `card=ending-1111` in the message and the matching JSON value. [SanitizedMessageView.java:347][sanitizer] invokes sanitizers before the numeric check. |

## Regressions

**Restarting the same encoder accumulates masking wrappers — unusual composition.**

[PlatformJsonEncoder.java:106][decorator] captures the currently installed decorator, including the platform wrapper installed by an earlier `start()`. After `stop(); start();`, the new wrapper therefore encloses the old one.

Concrete reproduction: register this `LogSanitizer`, restart the same configured encoder, and encode `"{}"` with `kv("recordId", "7")`:

```java
(key, value) ->
    "recordId".equals(key) && value != null ? "ref-" + value : value
```

Source-derived results:

- **Before this diff:** JSON `"recordId":"ref-7"`; message `recordId=ref-7`.
- **After this diff:** JSON `"recordId":"ref-ref-7"`; message `recordId=ref-7`.

Logstash retains the decorator across [stop/start](https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/composite/AbstractCompositeJsonFormatter.java), and its [masking generator forwards transformed values to its delegate](https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/mask/MaskingJsonGenerator.java). Each retained wrapper consequently applies the sanitizer again. This also affects non-idempotent pseudonymization functions.

Keep the consumer decorator separately from the installed platform wrapper so restarting rebuilds one masking layer.

## Verification boundary

Reviewed `2ca797d..739a08d` at checkout `739a08d2fd09cdddc751168bcc7eb19b1b5c903b`, respecting the exclusions, D102 baseline, and accepted residuals.

These are **source-established conclusions**, not executed integration-test results. No Maven/JUnit tests ran in the read-only checkout; the JShell probe failed with `Error loading java.security file`. The scaling-test change was reviewed but not benchmarked. No files were modified.

[raw-message]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v7/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:195
[raw-object]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v7/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/RawJson.java:57
[raw-clean]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v7/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:200
[array]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v7/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:241
[numbers]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v7/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:256
[sanitizer]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v7/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:347
[decorator]: /C:/Users/Ali/AppData/Local/Temp/hd/codex-p2v7/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java:106
