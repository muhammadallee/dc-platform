# Capabilities

| Capability | Starter(s) | Summary |
|------------|-----------|---------|
| core | `platform-starter-core` | The smallest useful chassis: request correlation by W3C trace ID, context propagation, the platform |
| data | `platform-starter-data-jpa` `platform-starter-data-jpa-oracle` | Tech-neutral persistence value objects (platform-data-api) plus opinionated Spring Data JPA |
| errors | `platform-starter-errors` | RFC-9457 problem responses with stable, machine-readable error codes: throw a |
| logging | `platform-starter-logging` | Structured JSON logs on stdout with service identity and correlation, credentials and common PII |
| messaging | `platform-starter-messaging-inmemory` `platform-starter-messaging-rabbit` | Publish and handle integration events without coupling application code to a broker: inject |
| observability | `platform-starter-observability` | Metrics with a consistent identity, health probes that answer "can this pod serve" without turning a |
| openapi | `platform-starter-openapi` | A springdoc-generated OpenAPI document that already knows how the platform reports errors, so no |
| outbox | `platform-starter-messaging-outbox` | Guaranteed publish alongside a database change. The event is written to a table in the same transaction as your |
| resilience | `platform-starter-resilience` | Fault-tolerance for calls to unreliable dependencies, built on Resilience4j(https://resilience4j.readme.io/). |
| restclient | `platform-starter-restclient` | Platform-conventional outbound REST calls: inject PlatformRestClientFactory instead of |
| testing | `platform-starter-test` | The platform ships a test kit so a service tests the same way the platform is built: one starter, |
