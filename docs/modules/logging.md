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
  - JWTs, `Bearer …` and `Basic …` credentials;
  - the value after a credential key (`password`, `passwd`, `pwd`, `secret`, `client_secret`, `token`,
    `access_token`, `refresh_token`, `id_token`, `api-key`/`apikey`, `authorization`, `cookie`,
    `set-cookie`) in `key=value`, `key: value` and `"key":"value"` text;
  - the whole value of an MDC entry or structured argument whose **name** is one of those keys;
  - Emirates ID numbers (`784-YYYY-NNNNNNN-C`, dashes optional) and Luhn-valid card numbers (13–19
    digits starting 2–6, single spaces or dashes allowed — in text and when logged as a number).

  Timestamps, epoch millis, trace/span IDs, UUIDs, phone numbers and ordinary numeric IDs are left
  alone. Residual risk: a numeric ID that happens to be a Luhn-valid 13–19 digit number starting 2–6 is
  masked.
- **No forged lines** — JSON string escaping keeps CR/LF, quotes and embedded JSON inside one field, so
  user input cannot add a line or a field.
- **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
- **`LogSanitizer` beans** — your own scrubbing, applied in `@Order` to MDC values and structured
  arguments (not to the platform's own fields) before the platform masks run.

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
