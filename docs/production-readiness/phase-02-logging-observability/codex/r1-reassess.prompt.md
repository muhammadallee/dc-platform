# Re-evaluation — DC Platform hardening Phase 2 (Logging + Observability), Round 1

You reviewed commit 482ea5a (your working directory is that checkout). Below are your findings and the
implementer's point-by-point response. For EACH finding F1–F7 and each AC row, answer exactly one of:
**accept** (the response/fix resolves it), **reject** (the response is wrong — say why with code
references), or **maintain concern** (partly addressed — say what remains). Judge the proposed fixes on
their merits; they are not implemented in this checkout yet, so assess whether each would resolve the
failure scenario. Be specific and brief. Output a table `| Item | Verdict | Reason |` and then any new
concern the response introduces.

## Your findings

The phase is not ready to accept: credential leakage and trace-ID corruption remain in the default JSON encoder, and the documented OTLP switch does not enable trace export with the supplied starter dependencies.

This is a source and dependency-metadata review. JVM probes failed because the sandbox denied access to Java’s security configuration; Maven, Docker, downstream compilation and the golden path were not run. Regex examples below were checked using translations of the source patterns, not JVM execution.

### F1 — Quoted secrets and cookie values are only partially redacted
- Severity: High
- Location: [PlatformValueMasker.java:45](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:45)
- Failure scenario: `password="correct horse battery staple"` becomes `password="**** horse battery staple"`. `password='hunter2'` remains unchanged. `Cookie: foo=abc; session=deadbeef` leaves `session=deadbeef` visible.
- Proof or reasoning: `KEY_VALUE` accepts only optional double quotes and stops at whitespace, quotes or semicolons. It neither consumes quoted values completely nor handles an entire cookie header. These leaks affect messages, exception messages and stack traces.
- Proposed fix: Handle quoted values, including escapes, separately from unquoted values. Mask complete cookie/header values. Add encoder-level tests asserting that no part of each secret survives anywhere in the event.

### F2 — Minimum token lengths exclude valid credentials
- Severity: High
- Location: [PlatformValueMasker.java:40](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:40)
- Failure scenario: A message containing `Basic YTpi` exposes the credentials `a:b`; `Bearer abc` also passes unchanged. A JWT with payload `{}`—encoded as `e30`—does not match the JWT pattern.
- Proof or reasoning: `AUTH_SCHEME` requires at least eight token characters, and `JWT` requires at least five payload characters. Neither minimum comes from the required masking contract. Credential-key masking cannot compensate when these appear in ordinary prose.
- Proposed fix: Remove the unsupported minimum lengths and match complete credential tokens. Test short Basic credentials, short opaque bearer tokens and JWTs with short valid payloads in messages and exception text.

### F3 — Non-string credential fields bypass credential-key masking
- Severity: High
- Location: [PlatformValueMasker.java:77](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:77)
- Failure scenario: `log.info("login {}", kv("password", 123456))` masks the formatted message but writes the structured numeric password unchanged.
- Proof or reasoning: Numeric handling and the non-`CharSequence` return happen before inspecting the field name. Thus integers bypass masking entirely, and longs receive only the PAN check. Similarly, numeric PAN coverage handles only `Long` and `BigInteger`, leaving `BigDecimal` and other numeric representations uncovered.
- Proposed fix: Apply credential-name masking independently of value type, preferably at the field/path level so containers are also covered. Normalize supported numeric representations before PAN detection. Test structured numeric credentials and PANs across numeric types.

### F4 — PAN detection corrupts correlation IDs and misses bounded card numbers
- Severity: High
- Location: [PlatformValueMasker.java:52](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:52)
- Failure scenario: The valid 32-hex trace ID `abcd4111111111111111abcdefabcdef` becomes `abcd****abcdefabcdef`; the valid span ID `4111111111111111` becomes `****`. Conversely, `card 4111111111111111 12` remains unmasked.
- Proof or reasoning: PAN boundaries exclude digits and hyphens but permit adjacent hex letters. `PLATFORM_FIELDS` excludes trace/span fields only from custom sanitizers, not built-in patterns. The greedy PAN matcher can also absorb a following numeric token, fail Luhn on the combined candidate, and never reconsider the valid card prefix. A trailing hyphen also prevents masking despite the specified non-digit boundary contract.
- Proposed fix: Preserve validated trace/span identifiers and recognize complete identifier tokens in free text. Rework PAN candidate segmentation so unrelated adjacent numbers do not defeat detection. Add adversarial IDs and PAN boundary tests through the encoder.

### F5 — Sanitized structured values remain exposed in the formatted message
- Severity: High
- Location: [PlatformValueMasker.java:90](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:90)
- Failure scenario: With the supplied `customerName` sanitizer, `log.info("hello {}", kv("customerName", "Fatima"))` writes a sanitized structured field but retains `Fatima` in `message`.
- Proof or reasoning: Sanitization runs during JSON field emission, after message formatting. The `message` field is explicitly excluded from custom sanitizers. `RedactedJsonLogOutputTest:117–121` exercises this exact call but checks only that `"customerName":"Fatima"` is absent, allowing the formatted copy to leak.
- Proposed fix: Sanitize structured argument values before both message formatting and structured serialization, using an event-local copy rather than mutating shared arguments. Assert that the original sensitive value is absent from the entire encoded event.

### F6 — E-mail matching introduces quadratic work on the synchronous logging path
- Severity: High
- Location: [PlatformValueMasker.java:55](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:55)
- Failure scenario: Logging a long user-controlled alphanumeric value without `@`, such as an invalid payload containing tens of thousands of `a` characters, causes disproportionate CPU work before the request can finish logging.
- Proof or reasoning: The unanchored `[A-Za-z0-9._%+-]+@...` pattern can consume and backtrack over the remaining suffix at each starting position: quadratic work. It runs on every string through `replaceAll`, and the shipped appender is synchronous. The translated-pattern probe showed approximately fourfold runtime growth whenever input length doubled; JVM timings remain unverified.
- Proposed fix: Use bounded, linear token scanning or constrain matching to token starts without retrying inside the same token. Add a JVM performance regression test using long non-matching input.

### F7 — The OTLP switch cannot enable trace export with the shipped starter
- Severity: High
- Location: [platform-starter-observability/pom.xml:38](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/observability/platform-starter-observability/pom.xml:38)
- Failure scenario: A service uses the observability starter and follows the documented collector configuration with `dc.platform.observability.otlp.enabled=true`. Metrics export can start, but no OTLP trace exporter is configured, while the capability descriptor reports `prometheus+otlp`.
- Proof or reasoning: The starter supplies the tracing bridge and Boot auto-configuration but no `opentelemetry-exporter-otlp` dependency. Their inspected transitive POMs do not supply it either. Boot 4.1’s auto-configuration metadata explicitly conditions `OtlpTracingAutoConfiguration` on `OtlpHttpSpanExporter`. Removing the disabling property cannot satisfy that missing-class condition.
- Proposed fix: Supply the Boot-managed OTLP exporter dependency and verify the ordinary starter’s on-switch against a real collector. Assert received spans, metrics and `service.name`. This is an existing gap left unresolved by the phase’s R4 requirement.

No Critical findings were identified. By inspection, I found no additional confirmed defect in actuator exposure, health-group membership, bean back-off, or MDC ownership/lifecycle. No downstream compilation regression was established; compilation remains unverified.

## Acceptance criteria not met

| AC | Gap |
|---|---|
| **AC1** | F4 breaks equality between logged trace IDs and request/ProblemDetail trace IDs. Existing tests use identifiers that avoid the masking collision. |
| **AC2** | JSON injection has local test coverage, but no Phase 2 consumer coverage was added, including hostile exception messages. The console requirement is waived by the owner’s explicit decision and is **not** a finding. |
| **AC3** | Slice coverage exists for strict/default MDC behavior, but the required consumer verification is absent. |
| **AC4** | Existing 200 × 3 tests inspect response/context snapshots; they do not correlate every encoded log line with its request under the new encoder. |
| **AC5** | F1–F4 demonstrate redaction leaks and false positives. Consumer redaction coverage is absent. |
| **AC6** | F5 leaves the original structured-argument value in the same event’s message. The current test misses that copy. |
| **AC7** | The new scrape test runs without the security fixture. It does not verify anonymous 401 followed by a harness-token scrape; it also omits an `env` assertion on the scrape. |
| **AC8** | The test makes **400**, not 1,000, calls; asserts no warning count; and tests a templated server route rather than unmatched paths collapsing to `NOT_FOUND`. Consumer coverage is absent. |
| **AC9** | Chassis exposure has slice coverage. Authenticated 404 versus anonymous 401 for unexposed endpoints is not tested by the new fixture. Existing golden-path assertions cover archetype access, but their result at this commit is unverified. |
| **AC10** | A permanently DOWN stub indicator replaces the required real DB stop/restart scenario. Recovery and DB-dependent versus independent request behavior are untested. |
| **AC11** | F7 prevents the ordinary starter from exporting traces. No real-collector receipt test exists in the reviewed surface. |
| **AC12** | No collector-down/slow consumer scenario verifies p99, status, memory bounds, warning bounds or startup duration. |
| **AC13** | Override tests are present, but the complete observability-properties snapshot and new logging EPP list/default snapshot are missing. Tests primarily establish bean/property absence rather than restored Boot behavior in a running app. |
| **AC14** | The synchronous appender is visible in XML, but no regression assertion enforces it. Existing shutdown harness checks were not demonstrated against this commit. |
| **AC15** | Downstream `-amd test-compile` and golden path 8/8 are **unverified**, not demonstrated failures. |
| **AC16** | The sampling explanation, tail-sampling configuration and OPEN decision are present. Boot’s `0.1` default matches inspected metadata, but no repository unit test pins that default. |
tokens used
125,777
The phase is not ready to accept: credential leakage and trace-ID corruption remain in the default JSON encoder, and the documented OTLP switch does not enable trace export with the supplied starter dependencies.

This is a source and dependency-metadata review. JVM probes failed because the sandbox denied access to Java’s security configuration; Maven, Docker, downstream compilation and the golden path were not run. Regex examples below were checked using translations of the source patterns, not JVM execution.

### F1 — Quoted secrets and cookie values are only partially redacted
- Severity: High
- Location: [PlatformValueMasker.java:45](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:45)
- Failure scenario: `password="correct horse battery staple"` becomes `password="**** horse battery staple"`. `password='hunter2'` remains unchanged. `Cookie: foo=abc; session=deadbeef` leaves `session=deadbeef` visible.
- Proof or reasoning: `KEY_VALUE` accepts only optional double quotes and stops at whitespace, quotes or semicolons. It neither consumes quoted values completely nor handles an entire cookie header. These leaks affect messages, exception messages and stack traces.
- Proposed fix: Handle quoted values, including escapes, separately from unquoted values. Mask complete cookie/header values. Add encoder-level tests asserting that no part of each secret survives anywhere in the event.

### F2 — Minimum token lengths exclude valid credentials
- Severity: High
- Location: [PlatformValueMasker.java:40](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:40)
- Failure scenario: A message containing `Basic YTpi` exposes the credentials `a:b`; `Bearer abc` also passes unchanged. A JWT with payload `{}`—encoded as `e30`—does not match the JWT pattern.
- Proof or reasoning: `AUTH_SCHEME` requires at least eight token characters, and `JWT` requires at least five payload characters. Neither minimum comes from the required masking contract. Credential-key masking cannot compensate when these appear in ordinary prose.
- Proposed fix: Remove the unsupported minimum lengths and match complete credential tokens. Test short Basic credentials, short opaque bearer tokens and JWTs with short valid payloads in messages and exception text.

### F3 — Non-string credential fields bypass credential-key masking
- Severity: High
- Location: [PlatformValueMasker.java:77](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:77)
- Failure scenario: `log.info("login {}", kv("password", 123456))` masks the formatted message but writes the structured numeric password unchanged.
- Proof or reasoning: Numeric handling and the non-`CharSequence` return happen before inspecting the field name. Thus integers bypass masking entirely, and longs receive only the PAN check. Similarly, numeric PAN coverage handles only `Long` and `BigInteger`, leaving `BigDecimal` and other numeric representations uncovered.
- Proposed fix: Apply credential-name masking independently of value type, preferably at the field/path level so containers are also covered. Normalize supported numeric representations before PAN detection. Test structured numeric credentials and PANs across numeric types.

### F4 — PAN detection corrupts correlation IDs and misses bounded card numbers
- Severity: High
- Location: [PlatformValueMasker.java:52](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:52)
- Failure scenario: The valid 32-hex trace ID `abcd4111111111111111abcdefabcdef` becomes `abcd****abcdefabcdef`; the valid span ID `4111111111111111` becomes `****`. Conversely, `card 4111111111111111 12` remains unmasked.
- Proof or reasoning: PAN boundaries exclude digits and hyphens but permit adjacent hex letters. `PLATFORM_FIELDS` excludes trace/span fields only from custom sanitizers, not built-in patterns. The greedy PAN matcher can also absorb a following numeric token, fail Luhn on the combined candidate, and never reconsider the valid card prefix. A trailing hyphen also prevents masking despite the specified non-digit boundary contract.
- Proposed fix: Preserve validated trace/span identifiers and recognize complete identifier tokens in free text. Rework PAN candidate segmentation so unrelated adjacent numbers do not defeat detection. Add adversarial IDs and PAN boundary tests through the encoder.

### F5 — Sanitized structured values remain exposed in the formatted message
- Severity: High
- Location: [PlatformValueMasker.java:90](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:90)
- Failure scenario: With the supplied `customerName` sanitizer, `log.info("hello {}", kv("customerName", "Fatima"))` writes a sanitized structured field but retains `Fatima` in `message`.
- Proof or reasoning: Sanitization runs during JSON field emission, after message formatting. The `message` field is explicitly excluded from custom sanitizers. `RedactedJsonLogOutputTest:117–121` exercises this exact call but checks only that `"customerName":"Fatima"` is absent, allowing the formatted copy to leak.
- Proposed fix: Sanitize structured argument values before both message formatting and structured serialization, using an event-local copy rather than mutating shared arguments. Assert that the original sensitive value is absent from the entire encoded event.

### F6 — E-mail matching introduces quadratic work on the synchronous logging path
- Severity: High
- Location: [PlatformValueMasker.java:55](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:55)
- Failure scenario: Logging a long user-controlled alphanumeric value without `@`, such as an invalid payload containing tens of thousands of `a` characters, causes disproportionate CPU work before the request can finish logging.
- Proof or reasoning: The unanchored `[A-Za-z0-9._%+-]+@...` pattern can consume and backtrack over the remaining suffix at each starting position: quadratic work. It runs on every string through `replaceAll`, and the shipped appender is synchronous. The translated-pattern probe showed approximately fourfold runtime growth whenever input length doubled; JVM timings remain unverified.
- Proposed fix: Use bounded, linear token scanning or constrain matching to token starts without retrying inside the same token. Add a JVM performance regression test using long non-matching input.

### F7 — The OTLP switch cannot enable trace export with the shipped starter
- Severity: High
- Location: [platform-starter-observability/pom.xml:38](C:/Users/Ali/AppData/Local/Temp/hd/codex-p2r1/observability/platform-starter-observability/pom.xml:38)
- Failure scenario: A service uses the observability starter and follows the documented collector configuration with `dc.platform.observability.otlp.enabled=true`. Metrics export can start, but no OTLP trace exporter is configured, while the capability descriptor reports `prometheus+otlp`.
- Proof or reasoning: The starter supplies the tracing bridge and Boot auto-configuration but no `opentelemetry-exporter-otlp` dependency. Their inspected transitive POMs do not supply it either. Boot 4.1’s auto-configuration metadata explicitly conditions `OtlpTracingAutoConfiguration` on `OtlpHttpSpanExporter`. Removing the disabling property cannot satisfy that missing-class condition.
- Proposed fix: Supply the Boot-managed OTLP exporter dependency and verify the ordinary starter’s on-switch against a real collector. Assert received spans, metrics and `service.name`. This is an existing gap left unresolved by the phase’s R4 requirement.

No Critical findings were identified. By inspection, I found no additional confirmed defect in actuator exposure, health-group membership, bean back-off, or MDC ownership/lifecycle. No downstream compilation regression was established; compilation remains unverified.

## Acceptance criteria not met

| AC | Gap |
|---|---|
| **AC1** | F4 breaks equality between logged trace IDs and request/ProblemDetail trace IDs. Existing tests use identifiers that avoid the masking collision. |
| **AC2** | JSON injection has local test coverage, but no Phase 2 consumer coverage was added, including hostile exception messages. The console requirement is waived by the owner’s explicit decision and is **not** a finding. |
| **AC3** | Slice coverage exists for strict/default MDC behavior, but the required consumer verification is absent. |
| **AC4** | Existing 200 × 3 tests inspect response/context snapshots; they do not correlate every encoded log line with its request under the new encoder. |
| **AC5** | F1–F4 demonstrate redaction leaks and false positives. Consumer redaction coverage is absent. |
| **AC6** | F5 leaves the original structured-argument value in the same event’s message. The current test misses that copy. |
| **AC7** | The new scrape test runs without the security fixture. It does not verify anonymous 401 followed by a harness-token scrape; it also omits an `env` assertion on the scrape. |
| **AC8** | The test makes **400**, not 1,000, calls; asserts no warning count; and tests a templated server route rather than unmatched paths collapsing to `NOT_FOUND`. Consumer coverage is absent. |
| **AC9** | Chassis exposure has slice coverage. Authenticated 404 versus anonymous 401 for unexposed endpoints is not tested by the new fixture. Existing golden-path assertions cover archetype access, but their result at this commit is unverified. |
| **AC10** | A permanently DOWN stub indicator replaces the required real DB stop/restart scenario. Recovery and DB-dependent versus independent request behavior are untested. |
| **AC11** | F7 prevents the ordinary starter from exporting traces. No real-collector receipt test exists in the reviewed surface. |
| **AC12** | No collector-down/slow consumer scenario verifies p99, status, memory bounds, warning bounds or startup duration. |
| **AC13** | Override tests are present, but the complete observability-properties snapshot and new logging EPP list/default snapshot are missing. Tests primarily establish bean/property absence rather than restored Boot behavior in a running app. |
| **AC14** | The synchronous appender is visible in XML, but no regression assertion enforces it. Existing shutdown harness checks were not demonstrated against this commit. |
| **AC15** | Downstream `-amd test-compile` and golden path 8/8 are **unverified**, not demonstrated failures. |
| **AC16** | The sampling explanation, tail-sampling configuration and OPEN decision are present. Boot’s `0.1` default matches inspected metadata, but no repository unit test pins that default. |


## Implementer response

# Phase 2 — Round 1: Claude's response to the Codex review

Reviewed commit `482ea5a`. Codex output: [`r1-review.out.md`](r1-review.out.md).

## Findings

### F1 — Quoted secrets and cookie values partially redacted — **Agree**
`KEY_VALUE` (`PlatformValueMasker.java:45`) takes the value up to whitespace/quote/semicolon, so
`password="correct horse battery staple"` leaks the tail, `password='hunter2'` is untouched (the optional
quote is only `"`), and a `Cookie:` header masks the first pair only.
Fix: the value alternative becomes double-quoted (with `\"` escapes) | single-quoted | unquoted; the
quote characters are kept and the content masked. `cookie` / `set-cookie` values run to the end of the
line (or the closing quote), so every pair of a cookie header is masked. Encoder-level test asserts no
fragment of any secret survives anywhere in the event.

### F2 — Minimum token lengths exclude valid credentials — **Partly agree**
- JWT: agree. `{}` encodes to `e30`; the payload minimum of 5 has no basis. The header segment always
  starts `eyJ`; payload and signature minimums are dropped to 2 / 0.
- `Basic`: agree in substance. Replace the length floor with a structural rule: a `Basic` token that is
  valid base64 and decodes to text containing `:` is masked whatever its length (`Basic YTpi` → `a:b`).
- `Bearer`: partly. Masking every word after "Bearer" turns prose ("Bearer token missing") into
  `Bearer ****` and hides the very diagnostic. The floor of 8 is replaced by: masked when the token
  contains a digit or any of `._~+/=-`, or is ≥ 16 characters. `Bearer abc` (letters only, 3 chars)
  stays visible — recorded as a residual in `docs/modules/logging.md`. Real opaque tokens and JWTs always
  meet the rule. Credential-named fields (`authorization`) are masked whole regardless.

### F3 — Non-string credential fields bypass credential-key masking — **Agree**
`mask()` returns before checking the field name for non-text values (`PlatformValueMasker.java:77`).
Fix: the credential-name check runs first, for any non-null value, and also when any **ancestor** field
of the current JSON path is a credential name (`kv("password", Map.of(...))`). Numeric PAN detection
normalizes `Integer`/`Long`/`BigInteger`/integral `BigDecimal` (`toPlainString`, scale ≤ 0).

### F4 — PAN detection corrupts trace IDs and misses bounded card numbers — **Agree**
Boundaries `(?<![\d-])`/`(?![\d-])` admit adjacent hex letters, so a hex identifier containing a
Luhn-valid digit run is cut; the separator-tolerant greedy pattern swallows a following number and then
fails Luhn. Fix:
- word boundaries on every numeric pattern (PAN, Emirates ID): `(?<![\w-])` … `(?![\w-])`;
- PAN candidates are either contiguous 13–19 digits, or the grouped card layouts `4-4-4-4[-1..3]` and
  `4-6-4|5`; an unrelated adjacent number no longer joins the candidate;
- the `traceId` and `spanId` fields (platform-written, validated hex) skip pattern masking entirely.
Tests: adversarial hex IDs with embedded Luhn runs (in fields and in free text), `card <pan> 12`,
trailing hyphen.

### F5 — Sanitized structured values remain in the formatted message — **Agree**
`LogSanitizer` runs per JSON field; the message was already formatted from the raw arguments.
Fix: `PlatformJsonEncoder.encode` hands the encoder an event view whose structured arguments
(`kv`/`StructuredArgument` markers with a field name) carry sanitized values and whose formatted message
is re-rendered from them — an event-local copy; the shared argument array is not mutated. Only when at
least one `LogSanitizer` is registered (no cost otherwise). Test asserts `Fatima` is absent from the
whole encoded line.

### F6 — E-mail matching is quadratic — **Agree**
The unanchored local-part `+` retries from every position of a long `[A-Za-z0-9._%+-]` run.
Fix: the local part must start a token (`(?<![A-Za-z0-9._%+-])`) and is bounded to 64 characters, the
domain to 253; plus an `indexOf('@')` fast path. JVM regression test: 200 000-character inputs without
`@` (and with a trailing `@`) are masked in under 250 ms.

### F7 — OTLP switch cannot enable trace export with the shipped starter — **Agree** (pre-existing)
Confirmed: `OtlpTracingAutoConfiguration` is `@ConditionalOnClass(… OtlpHttpSpanExporter …)`
(`spring-boot-micrometer-tracing-opentelemetry-4.1.0` autoconfigure metadata) and the starter brings no
`opentelemetry-exporter-otlp`. Fix: add the Boot-managed `io.opentelemetry:opentelemetry-exporter-otlp`
to `platform-starter-observability`; slice test: with `otlp.enabled=true` an `OtlpHttpSpanExporter` bean
exists, without it export is off. Real-collector receipt stays a Gate F consumer criterion (AC11).
Register: new HI entry (pre-existing defect surfaced in this phase).

## Acceptance criteria not met

| AC | Response |
|---|---|
| AC1 | Fixed with F4 (trace/span fields skip pattern masks; hex boundaries). |
| AC2, AC3, AC4, AC5, AC7, AC9, AC10, AC11, AC12, AC14 (harness part) | **Gate F (consumer)** — not yet run; the review was of Gate B. Each is in the Phase 2 scenario: security fixture (401 anonymous / 404 with token for unexposed endpoints, token scrape), real DB stop/start with DB-dependent vs independent requests and recovery, real collector receipt, collector down/slow with p99/status/memory/warn bounds and startup time, hostile exception messages, every log line correlated to its request. |
| AC6 | Fixed with F5. |
| AC8 | 400 vs 1 000 calls is a recorded scope amendment (400 > 4× the cap; the test fails with the cap lifted). Agree on the gaps: add the single-WARN assertion and an unmatched-path → `NOT_FOUND` server check. |
| AC13 | Agree: add the full `ObservabilityProperties` snapshot and the logging EPP resolved-key/default snapshot. Restored-Boot-behaviour with `enabled=false` in a running app: added to the web test (exposure back to Boot's `health`). |
| AC14 | Add a unit assertion that the platform root appender is a synchronous `ConsoleAppender` (no `AsyncAppender`); shutdown lines at Gate F. |
| AC15 | Downstream compile (14 modules, exit 0) and golden path 8/8 ran at Gate B; CI run 40387907881 on `482ea5a` is green (build + generator-gate). |
| AC16 | Agree: a unit test pins Boot's `management.tracing.sampling.probability` default `0.1` from the bound `TracingProperties`. |
