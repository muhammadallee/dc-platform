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
