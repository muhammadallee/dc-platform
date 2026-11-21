# Logging

Every generated service has it. Logging gives you one JSON object per line on stdout, with the request's trace on
every line and credentials and personal data masked. Your code logs with SLF4J. There is no platform type to learn.

## What the platform configures

- **Spring Boot's structured logging, switched on.** The platform sets `logging.structured.format.console` and
  `logging.structured.format.file` to its own formatter: Boot's Logstash layout, written by Boot's own JSON writer.
  A log file (`logging.file.name`) gets the same masked JSON.
- **The fields:** `@timestamp`, `message`, `logger`, `level`, then the MDC (`traceId`, `spanId` from core, and your own
  keys) and the key-value pairs of the event, `tags` (markers), `stack_trace` and `service`. `service` is
  `dc.platform.logging.service-name`, else `spring.application.name`, else `application`.
- **Masking**, in every value of every line:
  - **Credentials:** JWTs, `Bearer` and `Basic` credentials, `password=…`-style text, cookie headers.
  - **Credential-named values,** masked whole whatever their type: `password`, `token`, `secret`, `apiKey`,
    `authorization`, `cookie` and compound names such as `sessionToken` or `db_password`, at any depth.
  - **Personal data:** Emirates ID, Luhn-valid card numbers (written as text, numbers or doubles), e-mail addresses,
    phone numbers.
  - **Your own patterns:** `dc.platform.logging.redaction.patterns`, applied to text, numbers and booleans.
  - Masked text reads `****`.
- **Structure is kept, so names count.** A map, list, record or bean you log, as a key-value pair or a message argument,
  is written by its structure: a credential name inside it masks its whole value. JDK value types, character data and
  `Path` are masked text. A resource (`AutoCloseable`: a JDBC connection, a stream) or a proxy (JDK dynamic, Hibernate
  lazy) is never walked, because its getters may query or load: it is its own `toString()`, masked. A raw JSON value
  (`WritableJson`) is masked whole. A value whose `toString()` or getter
  throws becomes `[unrenderable]` and costs only its place; a container holding itself, anything deeper than 64 levels
  and anything past 10,000 values in one argument is masked.
- **No banner in JSON mode.** It would put non-JSON lines on stdout.
- **Readable console lines locally:** with the `local` profile or `dc.platform.logging.format=console` you get Boot's
  own console logging.
- **One banner line at startup** reports the effective format: `logging[ACTIVE] (json)` or `(console)`.

## Log structured fields

Add fields with SLF4J's fluent API. Each pair becomes a JSON field:

```java snippet:logging-key-values
@Service
class ShipmentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ShipmentDispatcher.class);

    void dispatch(String shipmentId, String carrier) {
        log.atInfo()
                .addKeyValue("shipmentId", shipmentId)
                .addKeyValue("carrier", carrier)
                .log("shipment dispatched");
    }
}
```

```json
{"@timestamp":"2026-11-20T10:15:30.123+04:00","message":"shipment dispatched","logger":"…ShipmentDispatcher",
 "level":"INFO","traceId":"4bf92f…","spanId":"00f067…","shipmentId":"SHP-1","carrier":"DHL","service":"orders"}
```

Do not add the trace ID yourself: it is already a field on the line.

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-logging</artifactId>
</dependency>
```

## Zero-config behaviour

| Works with only the starter | You must set |
|---|---|
| JSON on stdout and in a log file, masked; no banner; console under `local` | nothing |
| `traceId` and `spanId` fields | a tracer: the core or observability starter (every generated service has both) |

## Change it with Spring

| To… | Set |
|---|---|
| Mask your own identifiers | `dc.platform.logging.redaction.patterns[0]=CASE-\d{6}` |
| Keep only some MDC keys and key-value pairs | `dc.platform.logging.mdc.include=tenant,channel` (strict mode: `traceId`, `spanId` and these) |
| Leave the MDC and key-value pairs out | `dc.platform.logging.include-mdc=false` (the trace fields go too: rarely wise) |
| Add fields of your own to every line | your own `StructuredLoggingJsonMembersCustomizer`, named in `logging.structured.json.customizer`; the platform's masking still applies |
| Rename or drop fields | Boot's `logging.structured.json.rename.*` and `.exclude` |
| Use Boot's ECS or GELF format | `logging.structured.format.console=ecs` (masking applies; masking message arguments by structure and strict mode need the platform's format) |

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.logging.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.logging.format` | `json` | `json` or `console`; unset with the `local` profile means console. |
| `dc.platform.logging.include-mdc` | `true` | Write the MDC and key-value pairs. |
| `dc.platform.logging.service-name` | `${spring.application.name}` | The `service` field. |
| `dc.platform.logging.mdc.include` | empty | Strict mode: only `traceId`, `spanId` and these keys of the MDC and key-value pairs. |
| `dc.platform.logging.redaction.enabled` | `true` | Mask credentials and personal data. |
| `dc.platform.logging.redaction.patterns` | empty | Extra regular expressions whose matches are masked. One that is invalid, or matches empty text or the mask `****` itself, fails startup. |

## Back-off limits

- **Your own Logback file** (`logging.config`, `logback-spring.xml`) replaces Boot's structured logging, and with it
  the platform's format and masking, unless your file uses Boot's structured appender
  (`org/springframework/boot/logging/logback/structured-console-appender.xml`). A WARN at startup says so.
- **Boot's ECS or GELF format:** their own nested objects pass the masking only when the console format and, where
  a log file is written, the file format are the same; a mixed setup masks them (fails closed).
- **Your own customizer** runs inside Boot's JSON writer. Values the service logs, and flat members your customizer
  adds, are masked. A nested object your customizer adds is masked whole, unless it is named like one of ECS's
  objects (`log`, `process`, `service`, `ecs`, `error`) while you use Boot's ECS format. Whatever your customizer's own
  value or name processors produce is yours to keep safe.
- **Text masking is by pattern.** A credential in free text is masked up to the next space after `password=`, so log a
  secret, if at all, as a key-value pair under a credential name: it is masked whole.
- **Logback's appender formats the message itself, before any encoder runs** (`prepareForDeferredProcessing`). So an
  argument's own `toString()` always runs once, unbounded: one that throws is reported on stderr by SLF4J, with the
  exception text, and a huge or endless collection costs that time there. The JSON line shows `[unrenderable]` or the
  bounded, masked rendering. Log large structures as bounded key-value pairs, never as message arguments.
- **Console output is not masked,** and a CR/LF in a message can split a console line. Never ship console format to
  log aggregation.

## Error codes

None. Logging fails soft by design: a broken value never takes the service down or loses the line.

## Testing

`OutputCaptureExtension` and a real `SpringApplicationBuilder(...).run()` exercise the whole chain; assert JSON fields
on `output.getOut()`. Logging is JVM-global: reset Logback after such tests
(`((LoggerContext) LoggerFactory.getILoggerFactory()).reset()`), and restore system properties, because Boot copies
the structured format into `CONSOLE_LOG_STRUCTURED_FORMAT`.

## Local dev notes

Run with `--spring.profiles.active=local` for readable console lines. If production output is not JSON, check the
banner line `logging[ACTIVE] (json|console)` and `/actuator/env`: a value above the `platform-logging-defaults` source
outranks the platform's.
