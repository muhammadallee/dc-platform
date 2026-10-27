# Verification pass — DC Platform hardening Phase 2 (Logging + Observability), after Round 2

Your working directory is a checkout of commit `e1d2842`. In round 2 you reviewed `0ffc3e1`, raised
R2-F1–R2-F10 and an AC list, and re-evaluated the implementer's proposed fixes (all accepted except the
`docs/llms-full.txt` row, plus one additional concern about SLF4J's `Reporter.error` on a throwing
`toString`). The fixes are now implemented. This is a verification pass, not a new round: for EACH item
below state **accept** (implemented and resolves it) or **reject** (not resolved — say why, with
file:line and a concrete failing input). Also report any **regression the fix diff introduces** (new
false positives on R3.3's common values, new leaks, linear-time or stack-safety violations, broken
behaviour of other encoders/appenders, thread-safety), with the same evidence standard. Do not re-raise
items outside the diff unless the diff makes them worse.

Items: R2-F1, R2-F2, R2-F3, R2-F4, R2-F5, R2-F6, R2-F7, R2-F8 (+ the stderr concern), R2-F9, R2-F10,
AC5/R3, AC6, R5.1/AC7, R7.2 docs (including `docs/llms-full.txt`).

Output: a table `| Item | Verdict | Reason |`, then `## Regressions` (or "none established"), then
`## Verification boundary` (what you could and could not execute).

## Your round-2 findings
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


## Your re-evaluation

These verdicts assess the proposed fixes and stated contracts, not implementation or test completion. I did not independently verify the reported runs.

| Item | Verdict | Reason |
|---|---|---|
| Round-1 F1 | accept | R2-F2’s whole-line raw-cookie handling covers quoted cookie pairs and subsequent pairs. |
| Round-1 F2 | accept | R2-F7 removes the prefix restriction that excluded whitespace before `{`. |
| Round-1 F3 | accept | Field masking covers serialization paths that never invoke value maskers. |
| Round-1 F5 | accept | R2-F4/F5 address supported argument shapes before formatting, with an explicit limitation for arbitrary bean `toString()` output. |
| Re-evaluation: JWT whitespace | accept | Decoding the header and trimming JSON whitespace covers the reported leading-whitespace case. |
| R2-F1 | accept | The proposed possessive construction avoids length-dependent recursive repetition. Apply it to both quote styles in both patterns. [OpenJDK implementation](https://raw.githubusercontent.com/openjdk/jdk/jdk-25-ga/src/java.base/share/classes/java/util/regex/Pattern.java). |
| R2-F2 | accept | Consuming raw cookie headers through the line ending includes embedded quotes and every cookie pair; quoted-field handling preserves its separate boundary. |
| R2-F3 | accept | A credential-name `FieldMasker` suppresses the entire field, including boolean, binary and container contents. [Encoder 8.1 implementation](https://raw.githubusercontent.com/logfellow/logstash-logback-encoder/logstash-logback-encoder-8.1/src/main/java/net/logstash/logback/mask/MaskingJsonGenerator.java). |
| R2-F4 | accept | Unconditional, key-aware masking before formatting protects multiword credentials and value-only markers without rendering the secret. |
| R2-F5 | accept | The shared traversal resolves entries, nested containers, arrays and numbers. Accept the stated bean limitation: field-level JSON sanitization does not promise equivalent sanitization of arbitrary bean prose. |
| R2-F6 | accept | Removing the reserved-name exemptions closes the reported bypass. The narrowly scoped top-level W3C-ID exception has an explicit residual ambiguity; credential ancestors must retain precedence. |
| R2-F7 | accept | The decoded-header check resolves the reported whitespace bypass. Checking outer braces is a recognition heuristic, however, rather than full JSON validation. |
| R2-F8 | accept | Per-argument guards address event loss and preserve independently masked siblings. Placeholder selection must follow SLF4J escaping and throwable semantics. See the additional concern below. |
| R2-F9 | accept | A `long` digit-count check before expansion rejects enormous exponents; the BigInteger bit-length guard safely excludes values too large to be supported PANs. |
| R2-F10 | accept | Ordering after registry configurations, including composite selection, resolves premature condition evaluation. The proposed Boot-created-registry scrape assertion exercises the missing path. |
| AC5 / R3 | accept | The proposed fixes close the enumerated redaction gaps, under the explicitly stated trace/span-ID residual risk. |
| AC6 | accept | The shared traversal and numeric handling satisfy the stated supported-value contract; arbitrary bean message rendering remains explicitly limited. |
| R5.1 / AC7 coverage | accept | Correct configuration ordering plus an assertion that the platform gauge actually appears closes the identified coverage gap. |
| R7.2 documentation/surface inventory | maintain concern | The chapter notes resolve those examples. Recording generated content as stale does not correct the wrong [exposure default](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r2/docs/llms-full.txt:905) or [MDC description](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r2/docs/llms-full.txt:705). Regenerate it, or obtain an explicit scope exception. |

Additional concern: R2-F8’s fallback delegates a failing object back to SLF4J. Its `safeObjectAppend` reports the thrown exception through `Reporter.error`, outside the JSON masker; an exception containing `password=hunter2` can therefore expose it through diagnostics. Prefer a fixed safe placeholder directly, and test captured stderr as well as encoded bytes. This follows from [SLF4J’s formatter implementation](https://raw.githubusercontent.com/qos-ch/slf4j/v_2.0.17/slf4j-api/src/main/java/org/slf4j/helpers/MessageFormatter.java).


## Implementer response (proposed fixes)

# Phase 2 round 2: Claude's response to Codex (Gate D, step 2)

Reviewed commit `0ffc3e1`. Before replying, each finding was reproduced on that commit with a new test.
`EncoderRedactionTest` drives the real `PlatformJsonEncoder` on hand-built events and checks the encoded
bytes; this is the encoder path the round-1 F3 test skipped. One assertion was added to
`PlatformEndpointWebTest$Defaults`. The run log is `evidence-raw/d1-red.log` (git-ignored). On
`0ffc3e1`: 16 tests ran, with 13 failures and 3 errors. The web test had 1 failure.

## Round-1 dispositions

| Codex | Reply |
|---|---|
| F1 PARTIAL (quoted cookie pairs) | Agree. Reproduced; see R2-F2. |
| F2 PARTIAL (whitespace before `{` in the JWT header) | Agree. Reproduced; see R2-F7. |
| F3 PARTIAL (boolean and binary never reach the `ValueMasker`) | Agree. Reproduced through the encoder; the direct `mask()` test hid it. See R2-F3. |
| F4 CONFIRMED, F6 CONFIRMED, F7 CONFIRMED | Noted. |
| F5 PARTIAL | Agree. See R2-F4 and R2-F5. |
| Re-evaluation concerns | Noted. The JWT whitespace concern is part of R2-F7. |

## Findings

### R2-F1 — stack overflow on long quoted values: **Agree**
The `password="…"` encoder test reproduces it: 200 000 characters throw `StackOverflowError`. In a
standalone check on JDK 25, the alternation loop `"(?:[^"\\]|\\.)*"` overflows on both 200 000 plain
characters and 100 000 escapes. The unrolled possessive form `"[^"\\]*+(?:\\.[^"\\]*+)*+"` matches both
inputs without recursing. Fix: the unrolled possessive form, used in both `KEY_VALUE` and `COOKIE`. The
tests cover plain, escaped and unterminated values through the encoder.

### R2-F2 — quoted cookie pairs: **Agree**
Reproduced for `Cookie:` and `Set-Cookie:`. Fix: in header text the cookie value runs to the end of the
line, quotes included. A value that starts with a quote is the JSON or quoted-field form; it is masked whole
and the quotes are kept.

### R2-F3 — boolean and binary credentials: **Agree**
Reproduced: `kv("token", true)`, a `byte[]` under `secret`, and a `byte[]` under `auth.password`. Fix: a
`FieldMasker` replaces the whole value of any field whose name is a credential key, at any depth, before
the value is written. The written value can be a string, number, boolean, binary, object or array.

### R2-F4 — structured credentials in the formatted message: **Agree**
Reproduced: `kv("password", "correct horse battery staple")`, the same with `Kv.of`, and value-only
`v("password", "hunter2")`. Fix: the message view always runs, not only when `LogSanitizer` beans exist.
It renders credential-keyed `Kv` and markers as `key=****` (`****` for value-only markers) without calling
the value's `toString`.

### R2-F5 — sanitizer coverage across argument shapes: **Agree**, with a written contract
Reproduced with `entries(...)`, a nested map, an array and a number. Fix: one traversal serves both the
field output and the message:
- scalars, `Map` (including `entries(...)`), `Collection` and arrays are supported, nested in any
  combination;
- an array element gets the nearest enclosing field name as its key;
- numbers go through the sanitizers too.

Markers are rendered from their field name, their format pattern and the sanitized value, using logstash's
own `StructuredArguments.toString`. This replaces textual replacement. For other objects (beans), the JSON
field is sanitized field by field; the message gets their `toString` plus the pattern masks. This contract
is documented in `logging.md`.

### R2-F6 — reserved field names as a bypass: **Agree**
Reproduced: `payload.logger` and `payload.level`. Fix: the name exemption for `@timestamp`, `level` and
`logger` is removed. Those fields are pattern-masked like any other; the negative test covers real logger
names, timestamps with offsets and level names. `traceId`/`spanId` stay exempt only as top-level fields
holding a W3C hex ID. Remaining risk, documented: a consumer that writes a card number as a top-level
`spanId` field.

### R2-F7 — JWT header with leading whitespace: **Agree**
Reproduced: the header ` {"alg":"HS256"}` encodes to `IHsi…`. Fix: compact-token candidates (a run of
three dot-separated base64url segments, the first at least 10 characters) are masked when the first
segment decodes to a JSON object: after JSON whitespace is trimmed it starts with `{` and ends with `}`.
This replaces the base64 prefix check. A negative corpus stays readable:
- stack frames;
- the platform's own fully qualified class names;
- `svc.cluster.local` hosts;
- `backup.tar.gz`;
- version strings;
- `exceptions.internal.…` / `ewallet.…` package names.

### R2-F8 — `toString()` failure path in the message view: **Agree**
Reproduced: the event fails with `IllegalStateException` from `String.valueOf`. Fix:
- each argument is rendered under its own guard, and a failing argument keeps its original form, so SLF4J's
  own guarded formatting handles it;
- a credential-keyed argument needs no `toString`, so its mask survives a sibling's failure;
- arguments past the last placeholder are not rendered.

### R2-F9 — huge decimal exponents: **Agree**
Reproduced: `kv("amount", new BigDecimal("1E+100000000"))` took 195 s on one encode. Fix: the
integer-digit count is computed as `precision - scale` in `long` arithmetic, and the value is rejected
unless that count is 13–19. A `BigInteger` with more than 64 bits is rejected before `toString`. Neither
case is ever expanded.

### R2-F10 — capability gauge missing: **Agree** (pre-existing, in scope)
Reproduced: in the Boot-created registry case (`PlatformEndpointWebTest$Defaults`, no registry bean of the
platform's) the scrape has no `platform_capability_active`. Fix: order the configuration after Boot's
registry auto-configurations, using the exact class names from the Boot 4.1 metrics jar's
`AutoConfiguration.imports`. The web test assertion proves the fix by going from red to green.

## Acceptance criteria

- AC5, AC6 and R5.1/AC7: covered by the fixes above.
- R7.2 docs:
  - book chapters 7, 9, 11 and 13 get the same exposure note as chapter 8;
  - `docs/llms-full.txt` is generated; it is regenerated by its generator, or recorded in the issue register
    as a known-stale generated file with its regeneration step.


## Fix diff 0ffc3e1..e1d2842 (docs/production-readiness excluded)

```diff
diff --git a/CHANGELOG.md b/CHANGELOG.md
index ba13216..7a64a54 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -47,7 +47,11 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   Generated services opt in `platform` in their `application.yml` (D105).
 - **JSON logs are masked.** Credentials, credential-named fields, Emirates IDs, card numbers, e-mail
   addresses and phone numbers become `****` (D102). Turn off with `dc.platform.logging.redaction.enabled=false`.
-- `LogSanitizer` beans are now applied to MDC values and structured arguments, as documented.
+- `LogSanitizer` beans are now applied to MDC values and structured arguments, as documented: strings,
+  numbers, maps, collections and arrays, in the JSON field and in the formatted `message` alike.
+  Credential-keyed arguments read `password=****` in `message`.
+- `platform.capability.active` is now registered when Boot creates the meter registry (the
+  auto-configuration was evaluated before Boot's registry existed and backed off).
 - The observability starter now brings `opentelemetry-exporter-otlp`: `dc.platform.observability.otlp.enabled=true`
   previously exported metrics but never traces.
 - `LoggingProperties` gains `mdc` and `redaction` components (canonical constructor changed).
diff --git a/docs/book/chapters/03-logging-observability.md b/docs/book/chapters/03-logging-observability.md
index 8de803c..5129d81 100644
--- a/docs/book/chapters/03-logging-observability.md
+++ b/docs/book/chapters/03-logging-observability.md
@@ -6,6 +6,14 @@
 > validation errors omit `rejectedValue` by default. Where this chapter still describes the
 > `X-Correlation-Id` model, the module pages (`docs/modules/core.md`, `errors.md`, `logging.md`,
 > `restclient.md`) and decisions D89–D92 are authoritative. Full chapter rewrite: issue HI-0004.
+>
+> **Updated by capability hardening, phase 2 (2026-10).** Readiness is **pod-local**
+> (`readinessState` only); `db`, `rabbit` and `redis` moved to a separate `dependencies` health group.
+> The chassis exposes only `health`, `info` and `prometheus` over http — `metrics` and `platform` are
+> opt-ins (`management.endpoints.web.exposure.include`; generated services opt in `platform`). JSON logs
+> mask credentials and common PII, and `LogSanitizer` beans (`String sanitize(String key, String value)`)
+> are applied to MDC values and structured arguments. Where this chapter differs, `docs/modules/logging.md`,
+> `docs/modules/observability.md` and decisions D101–D107 are authoritative.
 
 > **Capabilities covered:** `logging`, `observability`
 >
@@ -217,22 +225,24 @@ Two questions an orchestrator asks, with two different consequences:
 | **Liveness** | Is this process broken beyond recovery? | The pod is **killed and restarted** |
 | **Readiness** | Can this instance serve traffic right now? | The pod is **removed from the load balancer**, not restarted |
 
-Getting these backwards causes outages. A database blip should fail *readiness* — the instance cannot
-serve, but restarting will not fix the database, and a restart loop across every replica turns a
-recoverable dependency blip into a full outage.
+Getting these backwards causes outages. A database blip must fail *neither* probe: restarting will not
+fix the database, and a database every replica shares would take every replica out of the load
+balancer at once — a recoverable dependency blip becomes a full outage. Requests that need the database
+fail on their own (with a problem response); the rest keep being served.
 
-The platform's defaults:
+The platform's defaults (D104):
 
 ```
-  liveness    <- livenessState
-  readiness   <- readinessState, db, rabbit, redis      (each only "when present")
+  liveness      <- livenessState
+  readiness     <- readinessState                          (pod-local)
+  dependencies  <- db, rabbit, redis      (each only "when present"; for dashboards and alerts, not probes)
 ```
 
 Two details make this work in practice. `management.endpoint.health.probes.enabled=true` makes the
 liveness and readiness contributors exist on **any** platform — Boot only auto-enables them when it
-detects Kubernetes. And `validate-group-membership=false` is what lets the readiness group name `db`,
-`rabbit`, and `redis` optionally: with validation on, a service that has no database would fail to
-start because its readiness group references a health indicator that does not exist.
+detects Kubernetes. And `validate-group-membership=false` is what lets the `dependencies` group name
+`db`, `rabbit`, and `redis` optionally: with validation on, a service that has no database would fail to
+start because its group references a health indicator that does not exist.
 
 ### 2.7 Baggage — how correlation crosses a service boundary without becoming a tag
 
@@ -609,14 +619,16 @@ curl -s localhost:8080/actuator/prometheus | grep '^http_server_requests'
 **Metrics missing tags.** `common-tags.enabled` is off, or a bean named `platformCommonTagsCustomizer`
 is replacing the platform's. Check `/actuator/beans`.
 
-**Readiness is always down.** A member of the readiness group is failing. The group is
-`readinessState,db,rabbit,redis` and each is optional *if absent* — but if `db` is **present** and
-unhealthy, readiness is correctly down. `curl -s localhost:8080/actuator/health/readiness | jq` names
-the failing component.
+**Readiness is always down.** Readiness is `readinessState` only, so the application itself reported
+`REFUSING_TRAFFIC` (during startup or shutdown, or set by your code); a database outage does not take
+it down. `curl -s localhost:8080/actuator/health/dependencies | jq` names a failing `db`, `rabbit` or
+`redis`.
 
 **The metrics backend is struggling.** Almost certainly cardinality. Find the offender:
 
 ```bash
+# metrics is not exposed by default: add it locally with
+#   management.endpoints.web.exposure.include=health,info,prometheus,platform,metrics
 curl -s localhost:8080/actuator/metrics | jq -r '.names[]' | while read m; do
   echo "$(curl -s "localhost:8080/actuator/metrics/$m" | jq '[.availableTags[].values|length] | add // 0') $m"
 done | sort -rn | head
@@ -660,7 +672,7 @@ long-retained, widely readable, and rarely reviewed.
 | PII or secrets in log fields | `LogSanitizer` beans, centrally. Review what you put in MDC extras |
 | Secrets in exception messages | The [errors](02-errors-validation.md) capability logs full exceptions at ERROR. Anything in the message is in the log |
 | Log injection | [`@SafeText`](02-errors-validation.md) on single-line fields; `CorrelationId`'s format check |
-| Actuator exposure | Default exposure is `health,info,platform,metrics,prometheus`. The security chain permits only `health` and `info` |
+| Actuator exposure | Default exposure is `health,info,prometheus` (`platform`, `metrics` are opt-ins). The security chain permits only `health` and `info` |
 | `/actuator/env` leaking configuration | It shows property values. Boot masks known-sensitive keys, but a key named `myCustomThing` is not known-sensitive |
 | Metric tags with user identifiers | A cardinality problem *and* a privacy problem — metrics are retained far longer than logs |
 
@@ -861,7 +873,7 @@ carries the three common tags.
 1. Add a `Timer` tagged with a per-request identifier — the correlation id is the purest form of the
    mistake.
 2. Drive 1,000 requests.
-3. `curl -s localhost:8080/actuator/metrics/<name> | jq '.availableTags'`. Count the values.
+3. `curl -s localhost:8080/actuator/metrics/<name> | jq '.availableTags'` (expose `metrics` first). Count the values.
 4. Measure `/actuator/prometheus` response size and time before and after. Extrapolate to 10 million
    requests.
 5. Run the cardinality-ranking command from §5.3. Confirm your meter is at the top.
diff --git a/docs/book/chapters/06-restclient-resilience.md b/docs/book/chapters/06-restclient-resilience.md
index f97d4a0..8470fbb 100644
--- a/docs/book/chapters/06-restclient-resilience.md
+++ b/docs/book/chapters/06-restclient-resilience.md
@@ -867,7 +867,7 @@ each made three recorded attempts (§6.3). Step 7 turns a failure into a degrade
 
 **Hints.**
 
-- `curl -s localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq`
+- `curl -s localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq` (expose `metrics` first: it is not exposed by default)
 - The fallback signature must be the guarded method's parameters plus a `Throwable`. A mismatch fails
   silently at runtime, not at compile time.
 
@@ -972,6 +972,8 @@ catch (RemoteCallException e) {
 **Diagnose it**
 
 ```bash
+# metrics is not exposed by default: add it locally with
+#   management.endpoints.web.exposure.include=health,info,prometheus,platform,metrics
 curl -s localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq
 curl -s localhost:8080/actuator/metrics/http.client.requests | jq '.availableTags'
 curl -s localhost:8080/actuator/configprops | jq '.. | .restclient? // empty'
diff --git a/docs/book/chapters/07-messaging-events.md b/docs/book/chapters/07-messaging-events.md
index e19e381..03dfff5 100644
--- a/docs/book/chapters/07-messaging-events.md
+++ b/docs/book/chapters/07-messaging-events.md
@@ -1019,6 +1019,8 @@ void on(InvoiceIssued event) { }          // exact runtime type — not assignab
 **Diagnose it**
 
 ```bash
+# metrics is not exposed by default: add it locally with
+#   management.endpoints.web.exposure.include=health,info,prometheus,platform,metrics
 curl -s localhost:8080/actuator/platform | jq            # messaging[ACTIVE] (provider)?
 curl -s localhost:8080/actuator/metrics/platform.messaging.handled | jq '.availableTags'
 curl -s localhost:8080/actuator/metrics/platform.messaging.published | jq
diff --git a/docs/book/chapters/09-cache-redis.md b/docs/book/chapters/09-cache-redis.md
index 3ca675f..bea761f 100644
--- a/docs/book/chapters/09-cache-redis.md
+++ b/docs/book/chapters/09-cache-redis.md
@@ -699,7 +699,7 @@ bypass, reproduced deliberately. Step 6 is the real exercise: a number, and a de
 
 **Hints.**
 
-- `curl -s localhost:8080/actuator/metrics/cache.gets | jq '.availableTags'`
+- `curl -s localhost:8080/actuator/metrics/cache.gets | jq '.availableTags'` (expose `metrics` first: it is not exposed by default)
 - If step 4 shows no meters, the `CacheManager` was replaced by a wrapper — §2.4.
 
 **How to verify.** A test asserting the method body executes once across two calls, and a second
@@ -829,6 +829,8 @@ redis.opsForValue().set("session:" + id, v, Duration.ofMinutes(30));   // wire k
 **Diagnose it**
 
 ```bash
+# metrics is not exposed by default: add it locally with
+#   management.endpoints.web.exposure.include=health,info,prometheus,platform,metrics
 curl -s localhost:8080/actuator/metrics/cache.gets | jq '.availableTags'   # hit ratio
 curl -s localhost:8080/actuator/metrics/cache.size | jq
 redis-cli --scan --pattern 'orders-service:*' | head        # actual wire keys
diff --git a/docs/book/chapters/11-storage-files.md b/docs/book/chapters/11-storage-files.md
index a8e5a77..35ce776 100644
--- a/docs/book/chapters/11-storage-files.md
+++ b/docs/book/chapters/11-storage-files.md
@@ -934,6 +934,8 @@ StreamingDownloads.write(store, bucket, key);
 **Diagnose it**
 
 ```bash
+# metrics is not exposed by default: add it locally with
+#   management.endpoints.web.exposure.include=health,info,prometheus,platform,metrics
 curl -s localhost:8080/actuator/platform | jq                       # storage[ACTIVE] (fs|s3)?
 curl -s localhost:8080/actuator/metrics/dc.platform.storage | jq '.availableTags'
 curl -s localhost:8080/actuator/env/dc.platform.storage.fs.root | jq
diff --git a/docs/book/chapters/13-ratelimit-flags.md b/docs/book/chapters/13-ratelimit-flags.md
index 52f7c2d..acdb29f 100644
--- a/docs/book/chapters/13-ratelimit-flags.md
+++ b/docs/book/chapters/13-ratelimit-flags.md
@@ -919,6 +919,8 @@ Optional<Receipt> newFlow(...)        // Optional/boolean/void — NOT a bare ob
 **Diagnose it**
 
 ```bash
+# metrics is not exposed by default: add it locally with
+#   management.endpoints.web.exposure.include=health,info,prometheus,platform,metrics
 curl -i localhost:8080/limited                                     # 429 + Retry-After?
 curl -s localhost:8080/actuator/metrics/dc.platform.ratelimit.decisions | jq '.availableTags'
 grep "DC-RATELIMIT-0500" app.log                                   # failing open RIGHT NOW
diff --git a/docs/book/crosscutting/security-model.md b/docs/book/crosscutting/security-model.md
index d84cb8c..47f4ced 100644
--- a/docs/book/crosscutting/security-model.md
+++ b/docs/book/crosscutting/security-model.md
@@ -268,9 +268,10 @@ rotation.
 
 ## 9. The operational surface
 
-The platform exposes `health,info,platform,metrics,prometheus` over HTTP, and its security chain
-permits **only** `health` and `info`. Everything else — including `/actuator/env`,
-`/actuator/configprops`, `/actuator/metrics`, and `/actuator/platform` — requires authentication.
+The platform exposes `health,info,prometheus` over HTTP (generated services also expose `platform`;
+`metrics` is an opt-in), and its security chain permits **only** `health` and `info`. Everything else —
+`/actuator/prometheus`, `/actuator/platform`, and any endpoint you expose — requires authentication; an
+endpoint that is not exposed answers 401 without a token and 404 with one.
 
 Two consequences to plan for:
 
diff --git a/docs/modules/logging.md b/docs/modules/logging.md
index 66d941f..dc63688 100644
--- a/docs/modules/logging.md
+++ b/docs/modules/logging.md
@@ -16,14 +16,18 @@ is watching.
   are the only MDC keys platform code writes.
 - **Redaction** — in every string the JSON encoder writes (message, MDC values, structured arguments,
   exception message, stack trace) the platform replaces with `****`:
-  - JWTs; whatever follows `Bearer` (so prose such as "Bearer token missing" reads
+  - JWTs (three dot-separated base64url segments whose first decodes to a JSON object, whitespace
+    around it allowed); whatever follows `Bearer` (so prose such as "Bearer token missing" reads
     "Bearer **** missing"); `Basic` credentials that decode to `user:password`;
   - the value after a credential key (`password`, `passwd`, `pwd`, `secret`, `client_secret`, `token`,
     `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`) in `key=value`,
-    `key: value`, `"key":"value"` and `key='value'` text — quoted values whole, quotes kept;
-  - everything after `Cookie:` / `Set-Cookie:` up to the end of the line (all pairs);
-  - the whole value — of any type, nested values included — of an MDC entry or structured argument
-    whose **name** is one of those keys or `cookie` / `set-cookie`;
+    `key: value`, `"key":"value"` and `key='value'` text — quoted values whole (escapes included, an
+    unterminated quote to the end), quotes kept;
+  - everything after `Cookie:` / `Set-Cookie:` up to the end of the line (all pairs, quoted ones too);
+  - the whole value — of any type (string, number, boolean, `byte[]`, object, array) — of an MDC entry or
+    structured-argument field, at any depth, whose **name** is one of those keys or `cookie` /
+    `set-cookie`; in the formatted `message` such an argument reads `password=****` (`****` for a
+    value-only `v(...)`);
   - Emirates ID numbers (`784-YYYY-NNNNNNN-C`, dashes optional) and Luhn-valid card numbers starting
     2–6: 13–19 contiguous digits, or the printed layouts `4111 1111 1111 1111` (optionally `+1..3`
     digits) and `3782 822463 10005`, with one consistent space or dash separator — in text and when
@@ -34,17 +38,24 @@ is watching.
 
   Timestamps, epoch millis, trace/span IDs (also hex IDs with digit runs inside), UUIDs, IP addresses,
   version strings, `Object@1a2b3c` identity strings and ordinary numeric IDs are left alone. The
-  `traceId`/`spanId` fields are never masked while they hold W3C hex IDs. Masking is linear in the input
-  length. Residual risks: a numeric ID that happens to
+  top-level `traceId`/`spanId` fields are never masked while they hold W3C hex IDs; no other field name
+  exempts a value (the encoder's own `level`/`logger` included). Masking is linear in the input length
+  and stack-safe on long values. Residual risks: a numeric ID that happens to
   be a Luhn-valid 13–19 digit number starting 2–6, or a 10-digit ID starting `050`–`058`, is masked;
   UAE landlines written without a prefix (`04 123 4567`) are **not** masked — add a pattern if you log
-  them.
+  them; a card number your code logs as a top-level field named `traceId`/`spanId` that is also a
+  16-character hex string is not masked.
 - **No forged lines** — JSON string escaping keeps CR/LF, quotes and embedded JSON inside one field, so
   user input cannot add a line or a field.
 - **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
-- **`LogSanitizer` beans** — your own scrubbing, applied in `@Order` to MDC values and keyed arguments
-  (`Kv.of`, Logstash `kv(...)`) — both the structured field and the argument's text in `message` — but not
-  to the platform's own fields, before the platform masks run.
+- **`LogSanitizer` beans** — your own scrubbing, applied in `@Order` before the platform masks run, to
+  MDC values and to keyed arguments — `Kv.of`, Logstash `kv(...)`/`v(...)`/`value(...)` and
+  `entries(...)` — in both the structured field and the argument's text in `message`, but not to the
+  platform's own top-level fields. Supported values: strings, numbers, maps, collections and object
+  arrays, nested in any combination; each value is sanitized under its own key (map entries under
+  theirs, array elements under the enclosing field's). Other objects (beans) are sanitized field by
+  field in the JSON output only; in `message` they keep their `toString()`, over which the platform
+  masks still run. An argument whose rendering throws reads `[unrenderable]` in `message`.
 
 ## Starter coordinates
 
diff --git a/docs/reference/properties.md b/docs/reference/properties.md
index f10bcdf..e63c09e 100644
--- a/docs/reference/properties.md
+++ b/docs/reference/properties.md
@@ -30,8 +30,8 @@ Every platform property lives under the `dc.platform.<capability>` prefix, has a
 | Key | Type | Default | Description | Deprecation |
 |-----|------|---------|-------------|-------------|
 | `dc.platform.core.banner-enabled` | Boolean | `true` | whether the startup capability banner is logged |  |
-| `dc.platform.core.correlation.generate-if-missing` | Boolean | `true` | generate a fresh id when the incoming request carries none |  |
-| `dc.platform.core.correlation.header-name` | String | `X-Correlation-Id` | HTTP header carrying the correlation id |  |
+| `dc.platform.core.correlation.generate-if-missing` | Boolean |  | Removed: a request without a valid traceparent always starts a new trace, whose trace ID is the correlation id. | error (The tracing bridge always starts a new trace when traceparent is missing or invalid.) |
+| `dc.platform.core.correlation.header-name` | String |  | Removed: the correlation id is the W3C trace ID and travels in the standard traceparent header, so there is no custom header to configure. | error (Correlation is the W3C trace ID (traceparent); custom correlation headers are no longer read or written.) |
 | `dc.platform.core.enabled` | Boolean | `true` | master kill switch for the whole capability |  |
 
 ## dc.platform.data
@@ -46,6 +46,7 @@ Every platform property lives under the `dc.platform.<capability>` prefix, has a
 | Key | Type | Default | Description | Deprecation |
 |-----|------|---------|-------------|-------------|
 | `dc.platform.errors.enabled` | Boolean | `true` | master kill switch for the whole capability |  |
+| `dc.platform.errors.include-rejected-values` | Boolean | `false` | echo each invalid value as rejectedValue (off by default: rejected values are raw user input and may be personal data; credential-shaped fields stay redacted even when on) |  |
 | `dc.platform.errors.include-stacktrace` | Boolean | `false` | add a stacktrace extension to problem bodies (debugging aid; never enable in production) |  |
 | `dc.platform.errors.map-validation` | Boolean | `true` | map Bean Validation failures to 400 problem responses with errors[] |  |
 | `dc.platform.errors.type-base-uri` | String | `urn:dc:problem:` | base URI prepended to the error code to form the problem type |  |
@@ -95,7 +96,10 @@ Every platform property lives under the `dc.platform.<capability>` prefix, has a
 |-----|------|---------|-------------|-------------|
 | `dc.platform.logging.enabled` | Boolean | `true` | master kill switch for the whole capability |  |
 | `dc.platform.logging.format` | ae.gov.dubaicustoms.platform.logging.autoconfigure.LoggingProperties$Format | `json` | log output format; json in every deployed environment, console for humans (auto-selected when the local profile is active) |  |
-| `dc.platform.logging.include-mdc` | Boolean | `true` | copy MDC entries (correlationId, …) into JSON log fields |  |
+| `dc.platform.logging.include-mdc` | Boolean | `true` | copy MDC entries (traceId, spanId, …) into JSON log fields |  |
+| `dc.platform.logging.mdc.include` | List<String> |  | MDC keys written besides traceId and spanId; empty (the default) writes every MDC key, a non-empty list switches to strict mode and drops unlisted keys |  |
+| `dc.platform.logging.redaction.enabled` | Boolean | `true` | mask values (on by default) |  |
+| `dc.platform.logging.redaction.patterns` | List<String> |  | additional regular expressions whose matches are masked |  |
 | `dc.platform.logging.service-name` | String |  | service name stamped on every JSON log line; defaults to name |  |
 
 ## dc.platform.messaging
@@ -157,11 +161,11 @@ Every platform property lives under the `dc.platform.<capability>` prefix, has a
 
 | Key | Type | Default | Description | Deprecation |
 |-----|------|---------|-------------|-------------|
-| `dc.platform.restclient.clients` | Map<String,ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties$ClientOverride> |  | per-client timeout overrides, keyed by the name passed to PlatformRestClientFactory#builder(String) |  |
+| `dc.platform.restclient.clients` | Map<String,ae.gov.dubaicustoms.platform.restclient.autoconfigure.RestClientProperties$ClientOverride> |  | per-client timeout overrides, keyed by the name passed to PlatformRestClientFactory#builder(String) <p>propagate-correlation is gone: every client is instrumented with the observation registry, so the W3C traceparent (the platform's only correlation id) is always propagated and no custom header is sent. The key remains in the metadata as deprecated. |  |
 | `dc.platform.restclient.defaults.connect-timeout` | Duration | `2s` | connection timeout |  |
 | `dc.platform.restclient.defaults.read-timeout` | Duration | `10s` | read timeout |  |
 | `dc.platform.restclient.enabled` | Boolean | `true` | master kill switch for the whole capability |  |
-| `dc.platform.restclient.propagate-correlation` | Boolean | `true` | propagate the current RequestContext correlation id as an outbound header |  |
+| `dc.platform.restclient.propagate-correlation` | Boolean |  | Removed: every platform client propagates the W3C traceparent (the platform's only correlation id) through its observation registry; no custom correlation header is sent. | error (Correlation is the W3C trace ID, propagated in traceparent by the instrumented client.) |
 
 ## dc.platform.scheduling
 
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
index f815c99..edc542a 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/PlatformJsonEncoder.java
@@ -1,5 +1,6 @@
 package ae.gov.dubaicustoms.platform.logging.autoconfigure;
 
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.CredentialFieldMasker;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.PlatformValueMasker;
 import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.SanitizedMessageView;
 import ch.qos.logback.classic.spi.ILoggingEvent;
@@ -75,6 +76,7 @@ public class PlatformJsonEncoder extends LogstashEncoder {
         }
         if (redactionEnabled) {
             MaskingJsonGeneratorDecorator masking = new MaskingJsonGeneratorDecorator();
+            masking.addFieldMasker(new CredentialFieldMasker());
             masking.addValueMasker(new PlatformValueMasker(PlatformValueMasker.compile(redactionPatterns)));
             masking.start();
             setJsonGeneratorDecorator(masking);
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/CredentialFieldMasker.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/CredentialFieldMasker.java
new file mode 100644
index 0000000..310fe0d
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/CredentialFieldMasker.java
@@ -0,0 +1,20 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
+
+import com.fasterxml.jackson.core.JsonStreamContext;
+import net.logstash.logback.mask.FieldMasker;
+
+/**
+ * Replaces the whole value of a credential-named field ({@code password}, {@code token},
+ * {@code apiKey}, …), at any depth, before it is written: strings, numbers, booleans, binary values,
+ * objects and arrays alike. The value masker alone cannot do this, because the JSON generator never
+ * passes booleans or binary values to value maskers.
+ *
+ * <p>Stateless; thread-safe.
+ */
+public final class CredentialFieldMasker implements FieldMasker {
+
+    @Override
+    public Object mask(JsonStreamContext context) {
+        return PlatformValueMasker.isCredentialKey(context.getCurrentName()) ? PlatformValueMasker.MASK : null;
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
index 5c584ed..df150fb 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java
@@ -22,11 +22,13 @@ import net.logstash.logback.mask.ValueMasker;
  * structured-argument fields (D102).
  *
  * <p>Order per value: whole-value mask when the field, or any field enclosing it, is a credential key
- * (any value type); {@code LogSanitizer} beans (non-platform fields only); pattern masks. Pattern masks
- * run last, so a sanitizer can neither skip them nor reintroduce a credential the patterns would catch.
- * {@code traceId}/{@code spanId} fields holding W3C hex IDs are never pattern-masked. Every pattern is
- * anchored to token starts and bounded, so masking stays linear in the input length. Masked text
- * becomes {@value #MASK}.
+ * (any value type; {@link CredentialFieldMasker} also replaces such fields before their value is
+ * written); {@code LogSanitizer} beans, keyed by the nearest enclosing field name (not the platform's
+ * own top-level fields); pattern masks. Pattern masks run last, so a sanitizer can neither skip them nor
+ * reintroduce a credential the patterns would catch. Only top-level {@code traceId}/{@code spanId}
+ * fields holding W3C hex IDs are exempt from pattern masks; every other field, whatever its name, is
+ * masked. Every pattern is anchored to token starts and bounded, and no pattern recurses per character,
+ * so masking stays linear in the input length and stack-safe. Masked text becomes {@value #MASK}.
  *
  * <p>Stateless apart from the immutable extra patterns; thread-safe.
  */
@@ -35,14 +37,12 @@ public final class PlatformValueMasker implements ValueMasker {
     /** Replacement for masked text; fixed length so the original length is not disclosed. */
     public static final String MASK = "****";
 
-    // Field names written by the encoder itself; LogSanitizers apply to everything else (MDC, args).
+    // Top-level fields the encoder writes itself; LogSanitizers apply to everything else (MDC, args).
     private static final Set<String> PLATFORM_FIELDS = Set.of("@timestamp", "level", "logger", "message",
             "stack_trace", "service", "traceId", "spanId", "tags");
 
-    // Encoder-written fields: never pattern-masked.
-    private static final Set<String> ENCODER_FIELDS = Set.of("@timestamp", "level", "logger");
-    // traceId/spanId are exempt only when they hold what the platform writes there: W3C hex IDs (a hex
-    // ID may contain a Luhn-valid digit run). Anything else under those names is masked as usual.
+    // The platform writes W3C hex IDs to the top-level traceId/spanId fields (a hex ID may contain a
+    // Luhn-valid digit run). Anything else, or the same names nested in a structured value, is masked.
     private static final Pattern TRACE_OR_SPAN_ID = Pattern.compile("[0-9a-f]{32}|[0-9a-f]{16}");
 
     // Normalized (lower case, no '-' or '_') field names whose whole value is a credential.
@@ -50,22 +50,28 @@ public final class PlatformValueMasker implements ValueMasker {
             "clientsecret", "token", "accesstoken", "refreshtoken", "idtoken", "authorization",
             "proxyauthorization", "cookie", "setcookie", "apikey", "xapikey");
 
-    // A JWT header is base64url JSON: '{' then '"' or whitespace encodes to "ey" / "ew" (RFC 7515 allows
-    // whitespace); the smallest header ({"alg":"x"}) is 15 characters. {} as payload encodes to e30;
-    // alg=none has no signature.
-    private static final Pattern JWT =
-            Pattern.compile("(?<![A-Za-z0-9_-])e[wy][A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{2,}\\.[A-Za-z0-9_-]*");
+    // Compact JWS/JWE candidates: runs of base64url segments joined by dots. A window of three segments
+    // is a token when the first decodes to a JSON object (RFC 7515 allows whitespace around it); the
+    // smallest header ({"alg":"x"}) is 15 characters, {} as payload encodes to e30, alg=none has no
+    // signature.
+    private static final Pattern DOTTED_RUN = Pattern.compile("[A-Za-z0-9_.-]{15,}");
     private static final Pattern BEARER = Pattern.compile("(?i)(?<![\\w-])(Bearer)(\\s+)([A-Za-z0-9._~+/=-]+)");
     private static final Pattern BASIC = Pattern.compile("(?i)(?<![\\w-])(Basic)(\\s+)([A-Za-z0-9+/]+={0,2})");
-    private static final String QUOTED_OR_BARE =
-            "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|(?:(?:Bearer|Basic)\\s+)?[^\\s\"'&,;}]+)";
+    // Quoted values with escapes, unrolled and possessive: a loop over an alternation would recurse once
+    // per character in java.util.regex and overflow the stack on long values. An unterminated quote runs
+    // to the end of the text.
+    private static final String QUOTED = "\"[^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+(?:\"|\\z)"
+            + "|'[^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+(?:'|\\z)";
     // key=value, key: value, "key":"value", key='value'; quoted values masked whole, quotes kept.
     private static final Pattern KEY_VALUE = Pattern.compile("(?i)(?<![\\w-])(password|passwd|pwd|secret"
             + "|client_secret|access_token|refresh_token|id_token|token|api[-_]?key|authorization)"
-            + "(\"?\\s*[:=]\\s*)" + QUOTED_OR_BARE);
-    // A cookie header carries several pairs: everything up to the end of the line (or the quote) goes.
-    private static final Pattern COOKIE = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\"?\\s*[:=]\\s*)"
-            + "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'|[^\\r\\n\"]+)");
+            + "(\"?\\s*[:=]\\s*)(" + QUOTED + "|(?:(?:Bearer|Basic)\\s+)?[^\\s\"'&,;}]+)");
+    // "cookie":"…" (a quoted field): the quoted value goes. Cookie: … (header text, several pairs, quoted
+    // pair values included): everything up to the end of the line goes.
+    private static final Pattern COOKIE_FIELD = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\"\\s*:\\s*)("
+            + QUOTED + ")");
+    private static final Pattern COOKIE_HEADER = Pattern.compile("(?i)(?<![\\w-])(set-cookie|cookie)(\\s*[:=]\\s*)"
+            + "([^\\r\\n]+)");
     // Emirates ID: 784-YYYY-NNNNNNN-C, dashes optional.
     private static final Pattern EMIRATES_ID = Pattern.compile("(?<![\\w-])784-?\\d{4}-?\\d{7}-?\\d(?![\\w-])");
     // Card numbers, issuer digit 2–6 (keeps 13-digit epoch millis out), Luhn-valid: contiguous 13–19
@@ -106,7 +112,16 @@ public final class PlatformValueMasker implements ValueMasker {
         if (underCredentialKey(context)) {
             return MASK;
         }
+        String key = nearestName(context);
+        boolean sanitizable = key != null && !(topLevel(context) && PLATFORM_FIELDS.contains(key));
         if (value instanceof Number number) {
+            if (sanitizable && !LogSanitizerRegistry.current().isEmpty()) {
+                String original = number.toString();
+                String sanitized = sanitize(key, original);
+                if (!sanitized.equals(original)) {
+                    return maskPatterns(sanitized);
+                }
+            }
             // kv("card", 4111111111111111L): a card number written as a JSON number.
             String digits = integralDigits(number);
             return digits != null && DIGITS.matcher(digits).matches() && luhn(digits) ? MASK : null;
@@ -114,28 +129,25 @@ public final class PlatformValueMasker implements ValueMasker {
         if (!(value instanceof CharSequence text)) {
             return null;
         }
-        String field = context.getCurrentName();
         String original = text.toString();
-        if (field != null && (ENCODER_FIELDS.contains(field)
-                || ("traceId".equals(field) || "spanId".equals(field)) && TRACE_OR_SPAN_ID.matcher(original).matches())) {
+        if (topLevel(context) && ("traceId".equals(key) || "spanId".equals(key))
+                && TRACE_OR_SPAN_ID.matcher(original).matches()) {
             return null;
         }
-        String result = original;
-        if (field != null && !PLATFORM_FIELDS.contains(field)) {
-            result = sanitize(field, result);
-        }
+        String result = sanitizable ? sanitize(key, original) : original;
         result = maskPatterns(result);
         return result.equals(original) ? null : result;
     }
 
     /** Applies the pattern masks (credentials, PII, extra patterns) to free text. */
     String maskPatterns(String text) {
-        String result = JWT.matcher(text).replaceAll(MASK);
+        String result = maskJwts(text);
         // RFC 6750 tokens may be letters only: whatever follows "Bearer" goes, prose included.
         result = BEARER.matcher(result).replaceAll("$1$2" + MASK);
         result = replace(result, BASIC, m -> basicCredential(m.group(3)) ? m.group(1) + m.group(2) + MASK : m.group());
         result = replace(result, KEY_VALUE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
-        result = replace(result, COOKIE, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
+        result = replace(result, COOKIE_FIELD, m -> m.group(1) + m.group(2) + maskValue(m.group(3)));
+        result = replace(result, COOKIE_HEADER, m -> m.group(1) + m.group(2) + MASK);
         result = EMIRATES_ID.matcher(result).replaceAll(MASK);
         result = replace(result, PAN_GROUPED, PlatformValueMasker::groupedCard);
         result = replace(result, PAN_CONTIGUOUS, m -> luhn(m.group()) ? MASK : m.group());
@@ -174,26 +186,128 @@ public final class PlatformValueMasker implements ValueMasker {
         return result;
     }
 
+    /**
+     * Whether a field name denotes a credential, compared case-insensitively and ignoring {@code -} and
+     * {@code _} ({@code api-key}, {@code API_KEY}, {@code apiKey}).
+     *
+     * @param name the field name; may be {@code null}
+     * @return {@code true} when the field's whole value must be masked
+     */
+    static boolean isCredentialKey(String name) {
+        return name != null && CREDENTIAL_KEYS.contains(normalize(name));
+    }
+
     private static boolean underCredentialKey(JsonStreamContext context) {
         for (JsonStreamContext c = context; c != null; c = c.getParent()) {
-            String name = c.getCurrentName();
-            if (name != null && CREDENTIAL_KEYS.contains(normalize(name))) {
+            if (isCredentialKey(c.getCurrentName())) {
                 return true;
             }
         }
         return false;
     }
 
+    // The key a value is filed under: its own field name, or for an array element the enclosing field's.
+    private static String nearestName(JsonStreamContext context) {
+        for (JsonStreamContext c = context; c != null; c = c.getParent()) {
+            if (c.getCurrentName() != null) {
+                return c.getCurrentName();
+            }
+        }
+        return null;
+    }
+
+    private static boolean topLevel(JsonStreamContext context) {
+        return context.inObject() && context.getParent() != null && context.getParent().inRoot();
+    }
+
     private static String integralDigits(Number number) {
-        if (number instanceof Long || number instanceof Integer || number instanceof BigInteger) {
+        if (number instanceof Long || number instanceof Integer) {
             return number.toString();
         }
-        if (number instanceof BigDecimal decimal && decimal.stripTrailingZeros().scale() <= 0) {
-            return decimal.toBigInteger().toString();
+        if (number instanceof BigInteger integer) {
+            // 19 digits fit in 64 bits; anything longer is no card number and is never expanded.
+            return integer.bitLength() <= 64 ? integer.toString() : null;
+        }
+        if (number instanceof BigDecimal decimal) {
+            BigDecimal stripped = decimal.stripTrailingZeros();
+            // Integer digits from precision and scale (long: scale may be near Integer.MIN_VALUE), so
+            // 1E+100000000 is rejected without expanding it.
+            long integerDigits = (long) stripped.precision() - stripped.scale();
+            return stripped.scale() <= 0 && integerDigits >= 13 && integerDigits <= 19
+                    ? stripped.toBigInteger().toString() : null;
         }
         return null;
     }
 
+    private static String maskJwts(String text) {
+        if (text.indexOf('.') < 0) {
+            return text;
+        }
+        Matcher run = DOTTED_RUN.matcher(text);
+        StringBuilder out = null;
+        int copied = 0;
+        while (run.find()) {
+            String masked = maskJwtRun(run.group());
+            if (masked != null) {
+                if (out == null) {
+                    out = new StringBuilder(text.length());
+                }
+                out.append(text, copied, run.start()).append(masked);
+                copied = run.end();
+            }
+        }
+        return out == null ? text : out.append(text, copied, text.length()).toString();
+    }
+
+    // Masks every header.payload.signature window in one dotted run; null when there is none.
+    private static String maskJwtRun(String run) {
+        String[] segments = run.split("\\.", -1);
+        if (segments.length < 3) {
+            return null;
+        }
+        StringBuilder out = new StringBuilder(run.length());
+        boolean found = false;
+        int i = 0;
+        while (i < segments.length) {
+            if (i + 2 < segments.length && jsonObjectHeader(segments[i]) && segments[i + 1].length() >= 2) {
+                out.append(MASK);
+                found = true;
+                i += 3;
+            } else {
+                out.append(segments[i]);
+                i++;
+            }
+            if (i < segments.length) {
+                out.append('.');
+            }
+        }
+        return found ? out.toString() : null;
+    }
+
+    private static boolean jsonObjectHeader(String segment) {
+        if (segment.length() < 10 || segment.length() % 4 == 1 || segment.indexOf('=') >= 0) {
+            return false;
+        }
+        try {
+            String header = new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.ISO_8859_1);
+            int start = 0;
+            int end = header.length();
+            while (start < end && jsonWhitespace(header.charAt(start))) {
+                start++;
+            }
+            while (end > start && jsonWhitespace(header.charAt(end - 1))) {
+                end--;
+            }
+            return end - start >= 2 && header.charAt(start) == '{' && header.charAt(end - 1) == '}';
+        } catch (IllegalArgumentException notBase64) {
+            return false;
+        }
+    }
+
+    private static boolean jsonWhitespace(char c) {
+        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
+    }
+
     private static String maskValue(String value) {
         char first = value.charAt(0);
         return first == '"' || first == '\'' ? first + MASK + first : MASK;
diff --git a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
index 828939e..cb7fc82 100644
--- a/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
+++ b/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/SanitizedMessageView.java
@@ -2,24 +2,43 @@ package ae.gov.dubaicustoms.platform.logging.autoconfigure.internal;
 
 import ae.gov.dubaicustoms.platform.logging.Kv;
 import ch.qos.logback.classic.spi.ILoggingEvent;
+import java.lang.reflect.Field;
 import java.lang.reflect.InvocationTargetException;
 import java.lang.reflect.Method;
 import java.lang.reflect.Proxy;
-import java.util.Objects;
+import java.text.MessageFormat;
+import java.util.ArrayList;
+import java.util.Collection;
+import java.util.LinkedHashMap;
+import java.util.List;
+import java.util.Map;
+import net.logstash.logback.argument.StructuredArguments;
+import net.logstash.logback.marker.MapEntriesAppendingMarker;
 import net.logstash.logback.marker.SingleFieldAppendingMarker;
 import org.slf4j.helpers.MessageFormatter;
 
 /**
- * Applies {@code LogSanitizer} beans to keyed message arguments ({@link Kv} and Logstash
- * {@code kv(...)}/{@code StructuredArguments} markers) before the message is rendered, so a sanitized
- * value does not survive in the formatted {@code message} while its structured field is clean.
+ * Renders the formatted {@code message} from the same sanitized values the JSON fields carry, for
+ * keyed message arguments: {@link Kv}, Logstash {@code kv(...)}/{@code v(...)}/{@code value(...)}
+ * markers and {@code entries(...)}. Credential-keyed values become {@code ****} (value-only markers
+ * render {@code ****}) whether or not {@code LogSanitizer} beans exist, without rendering the secret;
+ * {@code LogSanitizer} beans apply to scalars, numbers, maps, collections and object arrays, nested in
+ * any combination, keyed as in the JSON output (an array element by its enclosing field). Other objects
+ * keep their {@code toString}; the pattern masks still run over the whole message.
  *
- * <p>Returns an event-local view: the logged event and its argument array are never mutated. Without
- * registered sanitizers, or when no argument changes, the original event is returned. Thread-safe.
+ * <p>Each argument is rendered under its own guard: one whose rendering throws becomes
+ * {@value #UNRENDERABLE}, so neither the event nor a sibling's mask is lost and the exception text never
+ * reaches a diagnostic channel. Returns an event-local view: the logged event and its argument array
+ * are never mutated; when nothing changes, the original event is returned. Thread-safe.
  */
 public final class SanitizedMessageView {
 
-    private static final Method FIELD_VALUE = fieldValueAccessor();
+    static final String UNRENDERABLE = "[unrenderable]";
+
+    private static final int MAX_DEPTH = 16;
+    private static final Method FIELD_VALUE = accessor(SingleFieldAppendingMarker.class, "getFieldValue");
+    private static final Field FORMAT_PATTERN = field(SingleFieldAppendingMarker.class, "messageFormatPattern");
+    private static final Field MAP_ENTRIES = field(MapEntriesAppendingMarker.class, "map");
 
     private SanitizedMessageView() {
     }
@@ -32,22 +51,29 @@ public final class SanitizedMessageView {
      */
     public static ILoggingEvent of(ILoggingEvent event) {
         Object[] arguments = event.getArgumentArray();
-        if (arguments == null || arguments.length == 0 || LogSanitizerRegistry.current().isEmpty()) {
+        if (arguments == null || arguments.length == 0) {
             return event;
         }
+        String pattern = event.getMessage();
+        int used = Math.min(arguments.length, placeholders(pattern));
         Object[] display = arguments.clone();
         boolean changed = false;
-        for (int i = 0; i < display.length; i++) {
-            Object sanitized = sanitized(display[i]);
-            if (sanitized != display[i]) {
-                display[i] = sanitized;
+        for (int i = 0; i < used; i++) {
+            Object rendered;
+            try {
+                rendered = displayed(arguments[i]);
+            } catch (RuntimeException | LinkageError e) {
+                rendered = UNRENDERABLE;
+            }
+            if (rendered != arguments[i]) {
+                display[i] = rendered;
                 changed = true;
             }
         }
         if (!changed) {
             return event;
         }
-        String message = MessageFormatter.arrayFormat(event.getMessage(), display).getMessage();
+        String message = MessageFormatter.basicArrayFormat(pattern, display);
         return (ILoggingEvent) Proxy.newProxyInstance(ILoggingEvent.class.getClassLoader(),
                 new Class<?>[] {ILoggingEvent.class}, (proxy, method, args) -> {
                     if ("getFormattedMessage".equals(method.getName()) && method.getParameterCount() == 0) {
@@ -61,36 +87,135 @@ public final class SanitizedMessageView {
                 });
     }
 
-    private static Object sanitized(Object argument) {
+    // The argument itself when its rendering is unchanged, otherwise the text to show in its place.
+    private static Object displayed(Object argument) {
         if (argument instanceof Kv kv) {
-            String raw = String.valueOf(kv.value());
-            String clean = PlatformValueMasker.sanitize(kv.key(), raw);
-            return clean.equals(raw) ? argument : Kv.of(kv.key(), clean);
+            if (PlatformValueMasker.isCredentialKey(kv.key())) {
+                return kv.key() + "=" + PlatformValueMasker.MASK;
+            }
+            Object clean = sanitizedTree(kv.key(), kv.value(), 0);
+            return clean == kv.value() ? guardedToString(argument) : kv.key() + "=" + StructuredArguments.toString(clean);
         }
-        if (argument instanceof SingleFieldAppendingMarker marker && FIELD_VALUE != null) {
-            String raw = String.valueOf(fieldValue(marker));
-            String clean = PlatformValueMasker.sanitize(marker.getFieldName(), raw);
-            // Keeps the marker's own rendering ("key=value", "value", custom pattern) with the clean value.
-            return clean.equals(raw) ? argument : marker.toString().replace(raw, clean);
+        if (argument instanceof SingleFieldAppendingMarker marker) {
+            String name = marker.getFieldName();
+            if (PlatformValueMasker.isCredentialKey(name)) {
+                return render(marker, name, PlatformValueMasker.MASK);
+            }
+            if (FIELD_VALUE == null) {
+                return guardedToString(argument);
+            }
+            Object value = read(FIELD_VALUE, marker);
+            Object clean = sanitizedTree(name, value, 0);
+            return clean == value ? guardedToString(argument) : render(marker, name, StructuredArguments.toString(clean));
         }
+        if (argument instanceof MapEntriesAppendingMarker entries && MAP_ENTRIES != null) {
+            Object map = read(MAP_ENTRIES, entries);
+            Object clean = sanitizedTree(null, map, 0);
+            return clean == map ? guardedToString(argument) : String.valueOf(clean);
+        }
+        return argument;
+    }
+
+    // A structured argument whose own toString throws is shown as UNRENDERABLE, not handed to SLF4J.
+    private static Object guardedToString(Object argument) {
+        argument.toString();
         return argument;
     }
 
-    private static Object fieldValue(SingleFieldAppendingMarker marker) {
+    /**
+     * Applies credential masking and the {@code LogSanitizer} beans to a structured value: the same
+     * instance when nothing changes, otherwise a sanitized copy (maps, collections and object arrays
+     * become maps and lists).
+     */
+    static Object sanitizedTree(String key, Object value, int depth) {
+        if (key != null && PlatformValueMasker.isCredentialKey(key)) {
+            return PlatformValueMasker.MASK;
+        }
+        if (value == null || depth >= MAX_DEPTH) {
+            return value;
+        }
+        if (value instanceof Map<?, ?> map) {
+            Map<Object, Object> copy = new LinkedHashMap<>();
+            boolean changed = false;
+            for (Map.Entry<?, ?> entry : map.entrySet()) {
+                Object clean = sanitizedTree(String.valueOf(entry.getKey()), entry.getValue(), depth + 1);
+                changed |= clean != entry.getValue();
+                copy.put(entry.getKey(), clean);
+            }
+            return changed ? copy : value;
+        }
+        if (value instanceof Collection<?> || value instanceof Object[]) {
+            Iterable<?> elements = value instanceof Object[] array ? List.of(array) : (Collection<?>) value;
+            List<Object> copy = new ArrayList<>();
+            boolean changed = false;
+            for (Object element : elements) {
+                Object clean = sanitizedTree(key, element, depth + 1);
+                changed |= clean != element;
+                copy.add(clean);
+            }
+            return changed ? copy : value;
+        }
+        if (key != null && (value instanceof CharSequence || value instanceof Number)
+                && !LogSanitizerRegistry.current().isEmpty()) {
+            String raw = value.toString();
+            String clean = PlatformValueMasker.sanitize(key, raw);
+            return clean.equals(raw) ? value : clean;
+        }
+        return value;
+    }
+
+    // As SingleFieldAppendingMarker.toStringSelf, with the given value text.
+    private static String render(SingleFieldAppendingMarker marker, String name, String valueText) {
+        Object pattern = FORMAT_PATTERN == null ? null : read(FORMAT_PATTERN, marker);
+        if ("{1}".equals(pattern)) {
+            return valueText;
+        }
+        return pattern instanceof String format ? MessageFormat.format(format, name, valueText) : name + "=" + valueText;
+    }
+
+    // Placeholders SLF4J fills: "{}" not escaped by a single backslash ("\\{}" is a backslash + "{}").
+    private static int placeholders(String pattern) {
+        if (pattern == null) {
+            return 0;
+        }
+        int count = 0;
+        for (int i = pattern.indexOf("{}"); i >= 0; i = pattern.indexOf("{}", i + 2)) {
+            boolean escaped = i > 0 && pattern.charAt(i - 1) == '\\';
+            boolean doubleEscaped = escaped && i > 1 && pattern.charAt(i - 2) == '\\';
+            if (!escaped || doubleEscaped) {
+                count++;
+            }
+        }
+        return count;
+    }
+
+    private static Object read(Object accessor, Object target) {
         try {
-            return FIELD_VALUE.invoke(marker);
+            return accessor instanceof Method method ? method.invoke(target) : ((Field) accessor).get(target);
+        } catch (InvocationTargetException e) {
+            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
         } catch (ReflectiveOperationException e) {
-            return null;
+            throw new IllegalStateException(e);
         }
     }
 
-    // getFieldValue() is protected in logstash-logback-encoder; read it rather than re-implementing
-    // every marker type's rendering.
-    private static Method fieldValueAccessor() {
+    // logstash-logback-encoder keeps these protected/private; read them rather than re-implementing
+    // every marker type's rendering. Absent (another encoder version): the safe fallbacks apply.
+    private static Method accessor(Class<?> type, String name) {
         try {
-            Method method = SingleFieldAppendingMarker.class.getDeclaredMethod("getFieldValue");
+            Method method = type.getDeclaredMethod(name);
             method.setAccessible(true);
-            return Objects.requireNonNull(method);
+            return method;
+        } catch (ReflectiveOperationException | RuntimeException e) {
+            return null;
+        }
+    }
+
+    private static Field field(Class<?> type, String name) {
+        try {
+            Field field = type.getDeclaredField(name);
+            field.setAccessible(true);
+            return field;
         } catch (ReflectiveOperationException | RuntimeException e) {
             return null;
         }
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
new file mode 100644
index 0000000..f665fe9
--- /dev/null
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/EncoderRedactionTest.java
@@ -0,0 +1,211 @@
+package ae.gov.dubaicustoms.platform.logging.autoconfigure;
+
+import static net.logstash.logback.argument.StructuredArguments.entries;
+import static net.logstash.logback.argument.StructuredArguments.kv;
+import static net.logstash.logback.argument.StructuredArguments.v;
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.logging.Kv;
+import ae.gov.dubaicustoms.platform.logging.LogSanitizer;
+import ae.gov.dubaicustoms.platform.logging.autoconfigure.internal.LogSanitizerRegistry;
+import ch.qos.logback.classic.Level;
+import ch.qos.logback.classic.LoggerContext;
+import ch.qos.logback.classic.spi.LoggingEvent;
+import com.fasterxml.jackson.databind.JsonNode;
+import com.fasterxml.jackson.databind.ObjectMapper;
+import java.math.BigDecimal;
+import java.nio.charset.StandardCharsets;
+import java.time.Duration;
+import java.util.Base64;
+import java.util.LinkedHashMap;
+import java.util.List;
+import java.util.Map;
+import java.util.stream.Stream;
+import org.junit.jupiter.api.AfterEach;
+import org.junit.jupiter.api.Test;
+import org.junit.jupiter.params.ParameterizedTest;
+import org.junit.jupiter.params.provider.Arguments;
+import org.junit.jupiter.params.provider.MethodSource;
+import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.support.DefaultListableBeanFactory;
+
+/**
+ * The real {@link PlatformJsonEncoder} on hand-built events: what reaches the encoded bytes, through
+ * every generator path (string, number, boolean, binary, nested objects, arrays) and the formatted
+ * message. Phase-02 round-2 reproductions live here.
+ */
+class EncoderRedactionTest {
+
+    private static final ObjectMapper JSON = new ObjectMapper();
+    private static final String HS256_SPACE_HEADER = b64url(" {\"alg\":\"HS256\"}");
+    private static final String SPACED_JWT = HS256_SPACE_HEADER + "." + b64url("{\"sub\":\"alice\"}") + ".c2ln";
+
+    // The SLF4J-bound context: it carries the MDC adapter the encoder reads.
+    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
+
+    @AfterEach
+    void clearSanitizers() {
+        publish();
+    }
+
+    static Stream<Arguments> leaks() {
+        return Stream.of(
+                // R2-F2: quoted cookie pairs.
+                row("quoted cookie pair", "{}", new Object[] {"Cookie: sid=\"abc123\"; session=deadbeef"},
+                        "abc123", "deadbeef"),
+                row("quoted set-cookie", "{}", new Object[] {"Set-Cookie: sid=\"abc123\"; Path=/; session=deadbeef"},
+                        "abc123", "deadbeef"),
+                // R2-F3: boolean and binary values under credential keys, at depth 1 and 2.
+                row("boolean credential", "stored", new Object[] {kv("token", true)}, "\"token\":true"),
+                row("binary credential", "stored", new Object[] {kv("secret", "hunter2-bytes".getBytes(StandardCharsets.UTF_8))},
+                        Base64.getEncoder().encodeToString("hunter2-bytes".getBytes(StandardCharsets.UTF_8))),
+                row("nested binary credential", "stored",
+                        new Object[] {kv("auth", Map.of("password", "hunter2-bytes".getBytes(StandardCharsets.UTF_8)))},
+                        Base64.getEncoder().encodeToString("hunter2-bytes".getBytes(StandardCharsets.UTF_8))),
+                // R2-F4: credential-keyed structured arguments rendered in the message.
+                row("kv password with spaces", "{}", new Object[] {kv("password", "correct horse battery staple")},
+                        "horse", "staple"),
+                row("Kv password with spaces", "{}", new Object[] {Kv.of("password", "correct horse battery staple")},
+                        "horse", "staple"),
+                row("value-only password", "{}", new Object[] {v("password", "hunter2")}, "hunter2"),
+                // R2-F6: reserved names are not an exemption for consumer values.
+                row("nested logger field", "probe", new Object[] {kv("payload", Map.of("logger", "password=hunter2"))},
+                        "hunter2"),
+                row("nested level field", "probe", new Object[] {kv("payload", Map.of("level", "card 4111111111111111"))},
+                        "4111111111111111"),
+                // R2-F7: a JWT whose header has leading JSON whitespace.
+                row("whitespace-led JWT header", "token {}", new Object[] {SPACED_JWT}, HS256_SPACE_HEADER));
+    }
+
+    @ParameterizedTest(name = "{0}")
+    @MethodSource("leaks")
+    void nothingSecretReachesTheEncodedBytes(String name, String message, Object[] arguments, List<String> secrets) {
+        String line = encode(event(message, arguments));
+        for (String secret : secrets) {
+            assertThat(line).as(name).doesNotContain(secret);
+        }
+    }
+
+    @Test
+    void aCredentialFieldIsReplacedWholeWithNoPartialObjectWritten() throws Exception {
+        Map<String, Object> token = new LinkedHashMap<>();
+        token.put("kind", "refresh");
+        token.put("expires", 3600);
+        String line = encode(event("stored", new Object[] {kv("config", Map.of("token", token, "region", "dxb"))}));
+        JsonNode config = JSON.readTree(line).get("config");
+        assertThat(config.get("token").asText()).isEqualTo("****");
+        assertThat(config.get("region").asText()).isEqualTo("dxb");
+        assertThat(line).doesNotContain("refresh").doesNotContain("3600");
+    }
+
+    @Test
+    void customSanitizerReachesMapsNestedValuesArraysAndNumbersInFieldAndMessage() throws Exception {
+        // R2-F5: one traversal for field output and message rendering.
+        publish((key, value) -> "customerName".equals(key) ? "C***" : value,
+                (key, value) -> "accountNo".equals(key) ? "A***" : value);
+        Map<String, Object> person = new LinkedHashMap<>();
+        person.put("customerName", "Fatima");
+        String line = encode(event("{} {} {} {}", new Object[] {
+                entries(Map.of("customerName", "Fatima")),
+                kv("person", person),
+                kv("customerName", new String[] {"Fatima", "Omar"}),
+                kv("accountNo", 12345678L)}));
+        assertThat(line).doesNotContain("Fatima").doesNotContain("Omar").doesNotContain("12345678");
+        JsonNode node = JSON.readTree(line);
+        assertThat(node.get("message").asText()).contains("C***").contains("A***");
+    }
+
+    @Test
+    void aThrowingToStringCostsOnlyThatArgument() throws Exception {
+        // R2-F8: a sibling's toString failure must not lose the event or the credential mask.
+        publish((key, value) -> value);
+        Object hostile = new Object() {
+            @Override
+            public String toString() {
+                throw new IllegalStateException("lazy state unavailable, password=hunter3");
+            }
+        };
+        java.io.PrintStream originalErr = System.err;
+        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
+        String line;
+        System.setErr(new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
+        try {
+            line = encode(event("{} {}", new Object[] {kv("password", "hunter2"), Kv.of("entity", hostile)}));
+        } finally {
+            System.setErr(originalErr);
+        }
+        assertThat(line).isNotEmpty().doesNotContain("hunter2").doesNotContain("hunter3");
+        assertThat(JSON.readTree(line).get("message").asText()).isEqualTo("password=**** [unrenderable]");
+        // Not handed to SLF4J's formatter, which would report the exception text on stderr, unmasked.
+        assertThat(err.toString(StandardCharsets.UTF_8)).doesNotContain("hunter3");
+    }
+
+    @Test
+    void longQuotedValuesDoNotOverflowTheStack() {
+        // R2-F1: a long quoted credential and a long escaped one, on the logging thread.
+        String plain = "password=\"" + "x".repeat(200_000) + "\"";
+        String escaped = "password=\"" + "\\x".repeat(100_000) + "\"";
+        String unterminated = "password=\"" + "x".repeat(200_000);
+        for (String text : List.of(plain, escaped, unterminated)) {
+            String line = encode(event("{}", new Object[] {text}));
+            assertThat(line).contains("password=").doesNotContain("xxxxxxxxxx");
+        }
+    }
+
+    @Test
+    void hugeDecimalExponentsAreNotExpanded() {
+        // R2-F9: 1E+100000000 must be rejected by digit count, not by expanding it.
+        long started = System.nanoTime();
+        String line = encode(event("{}", new Object[] {kv("amount", new BigDecimal("1E+100000000"))}));
+        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
+        assertThat(line).contains("\"amount\":1E+100000000");
+    }
+
+    @Test
+    void encoderFieldsAndOrdinaryValuesStayReadable() throws Exception {
+        // Negatives for the reserved-name change (R2-F6) and the JWT change (R2-F7).
+        String stack = "java.lang.IllegalStateException: boom\n\tat ae.gov.dubaicustoms.platform.logging.autoconfigure"
+                + ".internal.PlatformValueMasker.mask(PlatformValueMasker.java:57)\n\tat org.springframework.web.servlet"
+                + ".DispatcherServlet.doDispatch(DispatcherServlet.java:1089)\n\tat java.base/java.util.concurrent"
+                + ".ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)";
+        String text = "host orders.customs.svc.cluster.local file backup.tar.gz version 4.1.0-SNAPSHOT exceptions.internal.Handler "
+                + "exception.handler.registry ewallet.payments.provider at 2026-10-27T05:00:00.123+04:00";
+        LoggingEvent event = event("{} | {}", new Object[] {text, stack});
+        String line = encode(event);
+        JsonNode node = JSON.readTree(line);
+        assertThat(node.get("message").asText()).isEqualTo(text + " | " + stack);
+        assertThat(node.get("logger").asText()).isEqualTo("ae.gov.dubaicustoms.platform.orders.OrderService");
+        assertThat(node.get("level").asText()).isEqualTo("INFO");
+    }
+
+    private static Arguments row(String name, String message, Object[] arguments, String... secrets) {
+        return Arguments.of(name, message, arguments, List.of(secrets));
+    }
+
+    private LoggingEvent event(String message, Object[] arguments) {
+        LoggingEvent event = new LoggingEvent("fqcn", context.getLogger("ae.gov.dubaicustoms.platform.orders.OrderService"),
+                Level.INFO, message, null, arguments);
+        event.setLoggerContext(context);
+        return event;
+    }
+
+    private String encode(LoggingEvent event) {
+        PlatformJsonEncoder encoder = new PlatformJsonEncoder();
+        encoder.setContext(context);
+        encoder.getFieldNames().setLogger("logger"); // as logback-platform.xml
+        encoder.start();
+        return new String(encoder.encode(event), StandardCharsets.UTF_8);
+    }
+
+    private static String b64url(String json) {
+        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
+    }
+
+    private static void publish(LogSanitizer... sanitizers) {
+        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
+        for (int i = 0; i < sanitizers.length; i++) {
+            beans.registerSingleton("s" + i, sanitizers[i]);
+        }
+        new LogSanitizerRegistry(beans.getBeanProvider(LogSanitizer.class)).afterSingletonsInstantiated();
+    }
+}
diff --git a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
index c7e7bfb..4a2bbc1 100644
--- a/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
+++ b/logging/platform-logging-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMaskerTest.java
@@ -139,7 +139,11 @@ class PlatformValueMaskerTest {
         assertThat(masker.mask(field("traceId"), "abcd4111111111111111abcdefabcdef")).isNull();
         assertThat(masker.mask(field("spanId"), "4111111111111111")).isNull();
         assertThat(masker.mask(field("traceId"), "password=hunter2")).isEqualTo("password=****");
-        assertThat(masker.mask(field("level"), "password=hunter2")).isNull();
+        // Round 2 R2-F6: no name exempts a consumer value — not the encoder's own field names, and not
+        // traceId/spanId below the top level.
+        assertThat(masker.mask(field("level"), "password=hunter2")).isEqualTo("password=****");
+        assertThat(masker.mask(field("logger"), "card 4111111111111111")).isEqualTo("card ****");
+        assertThat(masker.mask(nested("payload", "spanId"), "4111111111111111")).isEqualTo(MASK);
     }
 
     @Test
@@ -219,12 +223,25 @@ class PlatformValueMaskerTest {
         new LogSanitizerRegistry(beans.getBeanProvider(LogSanitizer.class)).afterSingletonsInstantiated();
     }
 
+    // A top-level field: its object's parent is the root context.
     private static JsonStreamContext field(String name) {
-        return context(name, null);
+        return context(name, ROOT);
     }
 
+    private static final JsonStreamContext ROOT = new JsonStreamContext(JsonStreamContext.TYPE_ROOT, 0) {
+        @Override
+        public JsonStreamContext getParent() {
+            return null;
+        }
+
+        @Override
+        public String getCurrentName() {
+            return null;
+        }
+    };
+
     private static JsonStreamContext nested(String outer, String inner) {
-        return context(inner, context(outer, null));
+        return context(inner, context(outer, ROOT));
     }
 
     private static JsonStreamContext context(String name, JsonStreamContext parent) {
diff --git a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/CapabilityMetricsAutoConfiguration.java b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/CapabilityMetricsAutoConfiguration.java
index e34ea68..0db006c 100644
--- a/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/CapabilityMetricsAutoConfiguration.java
+++ b/observability/platform-observability-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/CapabilityMetricsAutoConfiguration.java
@@ -18,10 +18,14 @@ import org.springframework.context.annotation.Bean;
  * Backs off when: user defines a CapabilityGaugeRegistrar bean
  * Beans: platformCapabilityGaugeRegistrar — emits platform.capability.active{capability=...} (0/1)
  *        from the CapabilityDescriptor beans once all singletons exist (adoption telemetry, F.1)
- * Order: none — the registrar is a SmartInitializingSingleton, so it runs after every capability
- *        has contributed its descriptor regardless of configuration order.
+ * Order: after Boot's registry auto-configurations (MetricsAutoConfiguration and
+ *        CompositeMeterRegistryAutoConfiguration, which every export configuration precedes), so the
+ *        @ConditionalOnBean(MeterRegistry) sees the registry Boot creates; the registrar itself is a
+ *        SmartInitializingSingleton, so it runs after every capability has contributed its descriptor.
  */
-@AutoConfiguration
+@AutoConfiguration(afterName = {
+        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
+        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"})
 @ConditionalOnClass(MeterRegistry.class)
 @ConditionalOnProperty(prefix = "dc.platform.observability", name = "enabled",
                        havingValue = "true", matchIfMissing = true)
diff --git a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java
index a244391..2f3bf96 100644
--- a/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java
+++ b/observability/platform-observability-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/observability/autoconfigure/PlatformEndpointWebTest.java
@@ -60,7 +60,9 @@ class PlatformEndpointWebTest {
             get(environment, "/actuator/health/readiness");
             String scrape = get(environment, "/actuator/prometheus").body();
             assertThat(scrape).contains("service=\"observability-web-test\"").contains("platform_version=\"")
-                    .contains("env=\"default\"");
+                    .contains("env=\"default\"")
+                    // R5.1: the platform's own meter, with Boot creating the registry (no registry bean of ours).
+                    .contains("platform_capability_active{");
             Matcher labels = LABEL_VALUE.matcher(scrape);
             while (labels.find()) {
                 assertThat(labels.group(1)).as("label value")
```

(`docs/llms-full.txt` is regenerated output; it is in the checkout — inspect it there.)
