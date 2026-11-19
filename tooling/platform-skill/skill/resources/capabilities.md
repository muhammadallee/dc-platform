# Capabilities

| Capability | Starter(s) | Summary |
|------------|-----------|---------|
| core | `platform-starter-core` | Every generated service has it. Core makes one ID follow a request everywhere: the W3C trace ID. It is in every log |
| data | `platform-starter-data-jpa` `platform-starter-data-jpa-oracle` | Opinionated Spring Data JPA wiring (platform-data-jpa-autoconfigure): auditing, snake_case naming, sane |
| errors | `platform-starter-errors` | Every error a service returns is an RFC 9457 application/problem+json body with a stable code, the request's |
| logging | `platform-starter-logging` | Structured JSON logs on stdout with service identity and correlation, credentials and common PII |
| messaging | `platform-starter-messaging-inmemory` `platform-starter-messaging-rabbit` | Publish and handle integration events without coupling application code to a broker: inject |
| observability | `platform-starter-observability` | Metrics with a consistent identity, health probes that answer "can this pod serve" without turning a |
| outbox | `platform-starter-messaging-outbox` | Guaranteed publish alongside a database change. The event is written to a table in the same transaction as your |
| testing | `platform-starter-test` | The platform ships a test kit so a service tests the same way the platform is built: one starter, |
