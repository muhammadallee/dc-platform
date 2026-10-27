# Logging

Structured JSON logs on stdout with service identity and correlation, credentials and common PII
masked, configured before the application context exists — and readable console output when a human
is watching.

## What you get

- **JSON by default** — one logstash-encoded line per event with ECS-ish fields:
  `@timestamp`, `level`, `logger`, `message`, `service`, `traceId`, `spanId` (and every other MDC
  entry), `stack_trace`.
- **Console for humans** — activate the `local` profile and output falls back to Boot's
  human-readable console format (explicit `dc.platform.logging.format` always wins).
- **Correlation built in** — the core filter publishes the W3C `traceId` (and `spanId`) to the MDC; the encoder
  lifts MDC entries into JSON fields, so log lines correlate with problem responses for free. These two
  are the only MDC keys platform code writes.
- **Redaction** — in every string the JSON encoder writes (message, MDC values, structured arguments,
  exception message, stack trace) the platform replaces with `****`:
  - JWTs (three dot-separated base64url segments whose first decodes to a JSON object, whitespace
    around it allowed); whatever follows `Bearer` (so prose such as "Bearer token missing" reads
    "Bearer **** missing"); `Basic` credentials that decode to `user:password`;
  - the value after a credential key (`password`, `passwd`, `pwd`, `secret`, `client_secret`, `token`,
    `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`, and compound keys
    ending in one of them or in `cookie`: `session_token`, `db-password`, `smtpSecret`, `sessionCookie`)
    in `key=value`,
    `key: value`, `"key":"value"` and `key='value'` text — quoted values whole (escapes included, an
    unterminated quote to the end), quotes kept;
  - everything after `Cookie:` / `Set-Cookie:` up to the end of the line (all pairs, quoted ones too);
  - the whole value — of any type (string, number, boolean, `byte[]`, object, array) — of an MDC entry or
    structured-argument field, at any depth, whose **name** is one of those keys or `cookie` /
    `set-cookie`, or ends in one of them ignoring case, `-` and `_` (`sessionToken`, `x-auth-token`,
    `db_password`); in the formatted `message` such an argument reads `password=****` (`****` for a
    value-only `v(...)`);
  - Emirates ID numbers (`784-YYYY-NNNNNNN-C`, dashes optional) and Luhn-valid card numbers starting
    2–6: 13–19 contiguous digits, or the printed layouts `4111 1111 1111 1111` (optionally `+1..3`
    digits) and `3782 822463 10005`, with one consistent space or dash separator — in text and when
    logged as a number;
  - e-mail addresses;
  - phone numbers: international with a `+` or `00` prefix (`+971 50 123 4567`, `00971501234567`,
    `+44 20 7946 0958`) and UAE mobiles in local form (`050 123 4567`, `0501234567`).

  Timestamps, epoch millis, trace/span IDs (also hex IDs with digit runs inside), UUIDs, IP addresses,
  version strings, `Object@1a2b3c` identity strings and ordinary numeric IDs are left alone. The
  top-level `traceId`/`spanId` fields are never masked while they hold W3C hex IDs; no other field name
  exempts a value (the encoder's own `level`/`logger` included). Masking is linear in the input length
  and stack-safe on long values. Residual risks: a numeric ID that happens to
  be a Luhn-valid 13–19 digit number starting 2–6, or a 10-digit ID starting `050`–`058`, is masked;
  UAE landlines written without a prefix (`04 123 4567`) are **not** masked — add a pattern if you log
  them; a card number your code logs as a top-level field named `traceId`/`spanId` that is also a
  16-character hex string is not masked.
- **No forged lines** — JSON string escaping keeps CR/LF, quotes and embedded JSON inside one field, so
  user input cannot add a line or a field. In JSON mode Boot's startup banner is off
  (`spring.main.banner-mode=off`, overridable), so every stdout line is one JSON object.
- **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
- **`LogSanitizer` beans** — your own scrubbing, applied in `@Order` before the platform masks run, to
  MDC values and to keyed arguments — `Kv.of`, Logstash `kv(...)`/`v(...)`/`value(...)` and
  `entries(...)` — in both the structured field and the argument's text in `message`, but not to the
  platform's own top-level fields. Supported values: strings, numbers, maps, collections and object
  arrays, nested in any combination; each value is sanitized under its own key (map entries under
  theirs, array elements under the enclosing field's). Other objects (beans) are sanitized field by
  field in the JSON output only; in `message` they keep their `toString()`, over which the platform
  masks still run (`fields(bean)` likewise). A `Kv` or marker nested inside a value is treated like a
  top-level one (a nested `Kv` is written `{"<key>": value}` in JSON, so its credential name masks it);
  chained markers (`append(...).and(...)`) render each one, `, `-joined. In `message`, a value nested
  more than 64 levels deep, or
  a container that contains itself, reads `****`. An argument whose rendering throws reads
  `[unrenderable]` in `message`, and the exception text is not reported anywhere (stderr, Logback
  status).

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-logging</artifactId>
</dependency>
```

## Zero-config behavior

An `EnvironmentPostProcessor` (the platform's one `spring.factories` registration — logging must
configure before the context) defaults `logging.config` to the shipped `logback-platform.xml`
through a lowest-precedence property source named `platform-logging-defaults` (visible in
`/actuator/env`). `service` defaults to `spring.application.name`, then `application`.

```json
{"@timestamp":"2026-01-01T00:00:00.000Z","message":"login for alice with password=****",
 "logger":"ae.gov.dc.orders.OrderService","level":"INFO","service":"orders",
 "traceId":"0123456789abcdef0123456789abcdef","spanId":"b7ad6b7169203331"}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.logging.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.logging.format` | `json` | `json` or `console`; unset + `local` profile ⇒ console. |
| `dc.platform.logging.include-mdc` | `true` | Copy MDC entries into JSON fields. |
| `dc.platform.logging.service-name` | `${spring.application.name}` | Service stamped on every line. |
| `dc.platform.logging.mdc.include` | empty | Strict mode: when set, only `traceId`, `spanId` and these MDC keys are written. |
| `dc.platform.logging.redaction.enabled` | `true` | Mask credentials and PII in JSON output. |
| `dc.platform.logging.redaction.patterns` | empty | Extra regular expressions whose matches are masked. |

(Hand-written until the generated reference lands in phase 14.)

## Customize

- `dc.platform.logging.service-name=customs-orders` overrides the stamped service identity.
- `dc.platform.logging.mdc.include=tenant,channel` keeps only those MDC keys (plus `traceId`/`spanId`)
  — use it when other libraries put noisy or sensitive entries in the MDC.
- `dc.platform.logging.redaction.patterns[0]=CASE-\d{6}` masks your own identifiers too.
- A `LogSanitizer` bean scrubs by key:

  ```java
  @Bean
  LogSanitizer customerNameSanitizer() {
      return (key, value) -> "customerName".equals(key) ? "C***" : value;
  }
  ```
- `dc.platform.logging.include-mdc=false` drops MDC fields (correlation included — rarely wise).

## Replace / Disable

- Set `logging.config` to your own file — any user-set value outranks the platform default. Your file
  then decides about masking: reuse
  `ae.gov.dubaicustoms.platform.logging.autoconfigure.PlatformJsonEncoder` or accept that
  nothing is masked.
- `dc.platform.logging.format=console` keeps Boot's default logging everywhere (no masking).
- `dc.platform.logging.redaction.enabled=false` writes values unmasked (also removes the
  `LogSanitizerRegistry` bean that hands your `LogSanitizer` beans to the encoder).
- `dc.platform.logging.enabled=false` switches the capability off wholesale.

## Error codes

None — logging fails soft by design; a broken logging setup must never take the service down. A
`LogSanitizer` that throws is skipped for that value; the platform masks still apply.

## Testing

`OutputCaptureExtension` + a real `SpringApplicationBuilder(...).run()` exercises the whole
chain (EPP → logback → encoder); assert JSON keys on `output.getOut()`. Logback state is
JVM-global — `((LoggerContext) LoggerFactory.getILoggerFactory()).reset()` after such tests.
`LogSanitizer` beans are handed to the encoder once the context has started; with several contexts in
one JVM the most recently started one wins.

## Local dev notes

Run with `--spring.profiles.active=local` for readable console lines. Console output is for humans and
is **not** masked, and a CR/LF in a message can split a console line — never ship console format to log
aggregation. If production output is not JSON: check the banner line `logging[ACTIVE] (json|console)` —
it reports the effective format decision, and `/actuator/env` shows whether `platform-logging-defaults`
was outranked.
