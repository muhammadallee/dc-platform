# Logging

Structured JSON logs on stdout with service identity and correlation, configured before the
application context exists — and readable console output when a human is watching.

## What you get

- **JSON by default** — one logstash-encoded line per event with ECS-ish fields:
  `@timestamp`, `level`, `logger`, `message`, `service`, `correlationId` (and every other MDC
  entry), `stack_trace`.
- **Console for humans** — activate the `local` profile and output falls back to Boot's
  human-readable console format (explicit `dc.platform.logging.format` always wins).
- **Correlation built in** — the core filter publishes `correlationId` to the MDC; the encoder
  lifts MDC entries into JSON fields, so log lines correlate with problem responses for free.
- **`Kv.of(key, value)`** — structured argument helper that stays readable in console format.
- **`LogSanitizer` SPI-lite** — contribute beans to scrub sensitive values; collected by the
  log-enrichment components of later phases.

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
{"@timestamp":"2026-01-01T00:00:00.000Z","message":"order accepted orderId=42",
 "logger":"ae.gov.dc.orders.OrderService","level":"INFO","service":"orders",
 "correlationId":"0123456789abcdef0123456789abcdef"}
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.logging.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.logging.format` | `json` | `json` or `console`; unset + `local` profile ⇒ console. |
| `dc.platform.logging.include-mdc` | `true` | Copy MDC entries into JSON fields. |
| `dc.platform.logging.service-name` | `${spring.application.name}` | Service stamped on every line. |

(Hand-written until the generated reference lands in phase 14.)

## Customize

- `dc.platform.logging.service-name=customs-orders` overrides the stamped service identity.
- `dc.platform.logging.include-mdc=false` drops MDC fields (correlation included — rarely wise).
- Contribute `LogSanitizer` beans for value scrubbing (applied by later-phase enrichment).

## Replace / Disable

- Set `logging.config` to your own file — any user-set value outranks the platform default.
- `dc.platform.logging.format=console` keeps Boot's default logging everywhere.
- `dc.platform.logging.enabled=false` switches the capability off wholesale.

## Error codes

None — logging fails soft by design; a broken logging setup must never take the service down.

## Testing

`OutputCaptureExtension` + a real `SpringApplicationBuilder(...).run()` exercises the whole
chain (EPP → logback → encoder); assert JSON keys on `output.getOut()`. Logback state is
JVM-global — `((LoggerContext) LoggerFactory.getILoggerFactory()).reset()` after such tests.

## Local dev notes

Run with `--spring.profiles.active=local` for readable console lines. If production output is
not JSON: check the banner line `logging[ACTIVE] (json|console)` — it reports the effective
format decision, and `/actuator/env` shows whether `platform-logging-defaults` was outranked.
