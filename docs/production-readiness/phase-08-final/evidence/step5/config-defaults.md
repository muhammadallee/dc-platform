baseline 72 current 121 {'deprecated': 3, 'added': 50, 'default changed': 3, 'removed': 1}
| Change | Property | Baseline | Now | Module |
|---|---|---|---|---|
| deprecated (error) | `dc.platform.core.correlation.generate-if-missing` | true | — | platform-core-autoconfigure |
| deprecated (error) | `dc.platform.core.correlation.header-name` | "X-Correlation-Id" | — | platform-core-autoconfigure |
| added | `dc.platform.data.jpa.oracle.flyway-read-timeout` |  | "10m" | platform-data-jpa-autoconfigure |
| added | `dc.platform.data.jpa.oracle.read-timeout` |  | "60s" | platform-data-jpa-autoconfigure |
| added | `dc.platform.errors.include-rejected-values` |  | false | platform-errors-autoconfigure |
| default changed | `dc.platform.errors.type-base-uri` | "https://errors.dc.com/" | "urn:dc:problem:" | platform-errors-autoconfigure |
| added | `dc.platform.logging.mdc.include` |  | — | platform-logging-autoconfigure |
| added | `dc.platform.logging.redaction.enabled` |  | true | platform-logging-autoconfigure |
| added | `dc.platform.logging.redaction.patterns` |  | — | platform-logging-autoconfigure |
| removed | `dc.platform.messaging.correlation.propagate` | true |  | platform-messaging-autoconfigure |
| added | `dc.platform.messaging.handler.retry.max-backoff` |  | "30s" | platform-messaging-autoconfigure |
| added | `dc.platform.messaging.outbox.batch-size` |  | 50 | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.enabled` |  | true | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.gauge-interval` |  | "5s" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.initial-backoff` |  | "1s" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.max-backoff` |  | "5m" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.max-rejections` |  | 10 | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.poll-interval` |  | "1s" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.pool-size` |  | 2 | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.read-timeout` |  | "60s" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.retention-interval` |  | "5m" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.sent-retention` |  | "24h" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.statement-timeout` |  | "30s" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.outbox.stop-timeout` |  | "30s" | platform-messaging-outbox-autoconfigure |
| added | `dc.platform.messaging.rabbit.confirm-timeout` |  | "5s" | platform-messaging-autoconfigure |
| added | `dc.platform.messaging.rabbit.delivery-limit` |  | 20 | platform-messaging-autoconfigure |
| added | `dc.platform.messaging.rabbit.prefetch` |  | 10 | platform-messaging-autoconfigure |
| default changed | `dc.platform.messaging.rabbit.quorum-queues` | false | true | platform-messaging-autoconfigure |
| added | `dc.platform.messaging.rabbit.shutdown-timeout` |  | "30s" | platform-messaging-autoconfigure |
| added | `dc.platform.restclient.defaults.bulkhead.enabled` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.bulkhead.max-concurrent` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.bulkhead.type` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.circuit-breaker.enabled` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.circuit-breaker.failure-rate-threshold` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.circuit-breaker.half-open-calls` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.circuit-breaker.minimum-calls` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.circuit-breaker.open-duration` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.circuit-breaker.sliding-window-size` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.deadline` |  | "30s" | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.retry.enabled` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.retry.initial-backoff` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.retry.max-attempts` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.retry.max-backoff` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.retry.methods` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.defaults.retry.multiplier` |  | — | platform-restclient-autoconfigure |
| deprecated (error) | `dc.platform.restclient.propagate-correlation` | true | — | platform-restclient-autoconfigure |
| added | `dc.platform.restclient.token-relay.clients` |  | — | platform-restclient-autoconfigure |
| added | `dc.platform.security.allow-partial-chains` |  | false | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.allow-insecure-jwks` |  | false | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.clock-skew` |  | "30s" | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.jwks.cache-ttl` |  | "5m" | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.jwks.connect-timeout` |  | "2s" | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.jwks.outage-tolerance` |  | "1h" | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.jwks.read-timeout` |  | "2s" | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.jwks.refresh-cooldown` |  | "30s" | platform-security-autoconfigure |
| added | `dc.platform.security.jwt.resource-client` |  | "" | platform-security-autoconfigure |
| default changed | `dc.platform.security.permit-paths` | "[/actuator/health, /actuator/health/**, /actuator/info, /v3/api-docs/**, /swagger-ui/**, /swagger-ui.html]" | "[/actuator/health/liveness, /actuator/health/readiness]" | platform-security-autoconfigure |
