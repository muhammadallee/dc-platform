## Round-1 dispositions

- **F1: PARTIAL** — quoted secrets are covered, but `Cookie: sid="abc"; session=deadbeef` still leaks both values. `PlatformValueMasker.java:66`; tests at `PlatformValueMaskerTest.java:67` omit quoted cookie pairs.
- **F2: PARTIAL** — short Basic/Bearer credentials and short JWT payloads are fixed and tested. JWT headers containing **leading** JSON whitespace still bypass the prefix restriction. `PlatformValueMasker.java:57`; `PlatformValueMaskerTest.java:82`.
- **F3: PARTIAL** — numeric credentials, integral `BigDecimal`, and string/number descendants are handled. However, the encoder never invokes this `ValueMasker` for booleans or binary values. The direct boolean test bypasses that integration issue. `PlatformJsonEncoder.java:78`; `PlatformValueMaskerTest.java:156`.
- **F4: CONFIRMED** — boundaries, trailing hyphens, following numbers, and the documented grouped layouts are addressed. `PlatformValueMasker.java:73`, `:212`; regression cases at `PlatformValueMaskerTest.java:105`.
- **F5: PARTIAL** — scalar `Kv`/`kv` sanitizer results are reflected in `message`. Other structured argument shapes, nested values, and built-in credential masking remain inconsistent between fields and messages. `SanitizedMessageView.java:35`, `:64`; tests at `RedactedJsonLogOutputTest.java:123`.
- **F6: CONFIRMED** — the e-mail expression has bounded components and a token-start boundary; the reported quadratic scan is addressed. `PlatformValueMasker.java:80`; performance test at `PlatformValueMaskerTest.java:181`. Its timing was not independently executed.
- **F7: CONFIRMED** — the starter now supplies the exporter, aligned through Boot’s OpenTelemetry BOM, with explicit on/off tests. `observability/platform-starter-observability/pom.xml:52`; `OtlpExportSwitchTest.java:32`.

Re-evaluation concerns:

- **traceId/spanId exemption: CONFIRMED** — arbitrary text no longer bypasses masking under those names, and credential ancestors take precedence. `PlatformValueMasker.java:106`, `:119`. Other reserved names still have a similar vulnerability, reported below.
- **Sanitizers running twice: CONFIRMED** — the message and structured field independently process the raw scalar value; neither consumes the other’s sanitized result. `SanitizedMessageView.java:67`; `RedactedJsonLogOutputTest.java:141`.
- **Whitespace in JWT headers: PARTIAL** — whitespace immediately after `{` is covered; whitespace before `{` is not. `PlatformValueMasker.java:57`.

**Verification boundary:** reviewed commit `0ffc3e1` and the full phase diff, with surrounding source and locally cached dependency bytecode. JVM probes failed because the sandbox denied access to `java.security`; Maven tests and Java performance reproductions were not run. Findings below distinguish source reasoning from execution evidence.

### R2-F1 — Long quoted values can overflow the logging thread’s stack

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:60`
- **Failure scenario:** logging `password="` followed by 200,000 ordinary characters and a closing quote can throw `StackOverflowError` during synchronous encoding.
- **Proof or reasoning:** both quoted-value expressions use unbounded repetition over an alternation, `(?:[^"\\]|\\.)*`. Java 25 compiles this form into recursive matching; stack depth grows with the value’s length. This is a stack-exhaustion defect, without needing exponential backtracking. The existing performance test contains no quoted credential values. This conclusion follows from the [OpenJDK 25 regex implementation](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/java/util/regex/Pattern.java); the exact failure threshold remains unverified here.
- **Proposed fix:** use an iterative quoted-value scanner, or a demonstrably stack-safe expression. Test long matching, escaped, and unterminated quoted values through the encoder.

### R2-F2 — Quoted cookie pairs bypass whole-header masking

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:66`
- **Failure scenario:** `Cookie: sid="abc123"; session=deadbeef` becomes `Cookie: ****"abc123"; session=deadbeef`. `Set-Cookie` has the same problem.
- **Proof or reasoning:** the bare-header alternative excludes `"`, so it consumes only `sid=`. The quoted alternative applies only when the entire value starts with a quote. This exact substitution was reproduced using the extracted expression in Python; the relevant matching semantics are also evident directly from the expression.
- **Proposed fix:** distinguish raw header text from a quoted JSON field. Consume an entire raw cookie header through the line ending, including quoted cookie pairs. Add both forms to encoder tests.

### R2-F3 — Credential masking does not cover binary and boolean serialization

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java:78`
- **Failure scenario:** `log.info("stored", kv("secret", secretBytes))` emits the recoverable Base64 secret; `kv("token", true)` emits `true`. Binary/boolean descendants beneath credential ancestors also survive.
- **Proof or reasoning:** only a `ValueMasker` is installed. In the pinned logstash encoder 8.1, `writeBinary` and `writeBoolean` delegate without invoking value maskers. Consequently, `underCredentialKey()` cannot protect these paths. Calling `mask(field("token"), true)` directly does not test the actual encoder.
- **Proposed fix:** install a credential-name `FieldMasker` that replaces the entire field before serialization, including containers. Add real encoder tests for booleans, byte arrays, and nested mixed-type credential values.

### R2-F4 — Structured credentials still leak through the formatted message

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:35`
- **Failure scenario:** without custom sanitizers, `log.info("{}", kv("password", "correct horse battery staple"))` produces a masked structured field but a message containing `password=**** horse battery staple`. `StructuredArguments.v("password", "hunter2")` renders the entire password without its key.
- **Proof or reasoning:** the message view returns immediately when the sanitizer registry is empty. Even when populated, it applies only custom sanitizers, not credential-key masking. After formatting, the text masker has lost the structured value’s boundaries—and value-only rendering has lost its key entirely.
- **Proposed fix:** apply built-in credential-key redaction to structured arguments before formatting, independently of custom sanitizer registration. Test whitespace, punctuation, collections, and value-only arguments.

### R2-F5 — Custom sanitizers remain inconsistent across structured argument shapes

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:64`
- **Failure scenario:** with the supplied `customerName` sanitizer, `log.info("{}", entries(Map.of("customerName", "Fatima")))` masks the JSON field but leaves `Fatima` in `message`. Nesting that map inside `kv("person", …)` has the same result.
- **Proof or reasoning:** `entries(...)` is not a `SingleFieldAppendingMarker`; nested values are never traversed by the view. Arrays have another mismatch: `String.valueOf(array)` produces an identity string, whereas logstash renders array contents, so `.replace(raw, clean)` cannot replace them. Separately, numeric structured values return from `PlatformValueMasker.java:109` before custom sanitizers run, although their message representation can be sanitized.
- **Proposed fix:** define one sanitization traversal for supported structured values and use it consistently for field output and message rendering. Cover maps, nested values, arrays, and numeric values; avoid textual replacement of an already-rendered marker.

### R2-F6 — Reserved field names still provide a redaction bypass

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:119`
- **Failure scenario:** `log.info("probe", kv("payload", Map.of("logger", "password=hunter2")))` emits the password unchanged inside `payload.logger`. Consumer MDC named `logger`, `level`, or `@timestamp` likewise bypasses masking.
- **Proof or reasoning:** the exemption checks only the current field name, not its origin or position. These names are not exclusively controlled by the encoder. `PlatformValueMaskerTest.java:142` explicitly expects this unsafe behavior for `level`.
- **Proposed fix:** remove the unconditional name-based exemption, or distinguish trusted encoder emission from consumer values. Test reserved names both at the root and inside structured objects.

### R2-F7 — Valid JWT headers with leading whitespace remain visible

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:57`
- **Failure scenario:** a JWT whose decoded protected header is ` {"alg":"HS256"}` starts with `IHsiYWxnIjoiSFMyNTYifQ`, not `ey` or `ew`. Logged as plain text or under a noncredential field, it remains visible.
- **Proof or reasoning:** the mandatory `e[wy]` prefix excludes this encoding. Leading whitespace is permitted by [RFC 7515 §3](https://www.rfc-editor.org/rfc/rfc7515.html#section-3). Constructing that header and testing the extracted expression confirmed the mismatch.
- **Proposed fix:** identify bounded compact-token candidates and validate a decoded JSON header, including leading whitespace, instead of relying exclusively on a Base64 prefix. Retain negative tests for ordinary dotted prose.

### R2-F8 — The message view introduces an unguarded `toString()` failure path

- **Severity:** Medium
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java:66`
- **Failure scenario:** register any sanitizer, then log a `Kv` whose value’s `toString()` throws—for example, an object referencing unavailable lazy-loaded state. Encoding fails and the intended event is lost.
- **Proof or reasoning:** `String.valueOf(...)` runs outside both the sanitizer exception guard and SLF4J’s guarded argument formatting. The loop also evaluates arguments that have no corresponding `{}` placeholder. This changes previously tolerated formatting failures into whole-event failures.
- **Proposed fix:** guard value rendering and preserve a safe diagnostic placeholder. Test throwing values, unused arguments, escaped placeholders, and an accompanying throwable.

### R2-F9 — PAN checking expands arbitrarily large decimal exponents

- **Severity:** High
- **Location:** `logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:191`
- **Failure scenario:** logging `kv("amount", new BigDecimal("1E+100000000"))` performs enormous integer expansion and decimal-string allocation on the logging thread, potentially exhausting heap or stalling requests.
- **Proof or reasoning:** the compact input passes the integral-scale check. `toBigInteger().toString()` expands it before the later 13–19-digit PAN check rejects it. Ordinary scientific-notation serialization would not require this expansion. The expansion follows directly from the code; its runtime cost was not measured here.
- **Proposed fix:** reject values outside the possible PAN digit range before conversion, using precision/scale arithmetic with overflow-safe intermediates. Add large positive and negative exponent tests.

### R2-F10 — Capability gauges can disappear because configuration runs too early

- **Severity:** Medium
- **Location:** `observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/CapabilityMetricsAutoConfiguration.java:24`
- **Failure scenario:** a normal starter application relying on Boot-created registries starts without `CapabilityGaugeRegistrar`; `/actuator/prometheus` lacks `platform_capability_active`.
- **Proof or reasoning:** this configuration has no ordering relationship to registry auto-configuration. Boot’s default ordering places the `ae.gov...` configuration before the registry configurations, so its method-level `@ConditionalOnBean(MeterRegistry.class)` fails. `SmartInitializingSingleton` cannot help a bean definition that was never registered. The test at `CapabilityMetricsAutoConfigurationTest.java:20` pre-registers a registry and hides the issue. This is a pre-existing, in-scope defect; application execution remains unverified here.
- **Proposed fix:** order the configuration after registry creation configurations, including composite registry selection. Add a starter-context test with no manually supplied registry and assert the gauge in the scrape.

## Acceptance criteria not met

- **AC5 / R3:** redaction is incomplete for quoted cookie pairs, supported structured credential arguments, binary credential values, reserved field names, and whitespace-prefixed JWT headers. Existing UNIT tests miss these encoder paths.
- **AC6:** sanitizer coverage is incomplete for maps, nested values, arrays, and numeric structured values. The accepted scalar SLICE test does not establish the full published contract.
- **R5.1 / AC7 coverage:** platform capability meters can be absent; the scrape test checks tags but never asserts that the platform meter exists.
- **R7.2 documentation/surface inventory:** the metrics-exposure note was added only to chapter 8. Examples remain without the required note in chapters 7 (`:1023`), 9 (`:832`), 11 (`:938`), and 13 (`:923`). `docs/llms-full.txt:905` still advertises the old exposure default, and `:705` retains the stale MDC wording.

No additional defect was established in the reviewed common-value false-positive cases, request MDC lifecycle, health/exposure changes, override guards, or OTLP dependency alignment. Build results were not independently rerun. Explicitly pending Gate F checks and the owner-accepted console behavior are not reported as missing.
