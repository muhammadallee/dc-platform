# Phase 0 — gap inventory (baseline `7b248e9`)

Each gap is measured against plan §7 (cross-cutting contracts) and §9 (capability criteria) by
reading the baseline source. Tags: **P0** blocks production · **P1** should land in the phase ·
**Deferred** out of this initiative. Gate A of each phase re-inspects and may refine these; the
authoritative tracking of anything that becomes a finding is [`../issue-register.md`](../issue-register.md).

`file:line` references are to the baseline commit.

## Phase 1 — Core + Error Handling

| ID | Gap | Evidence | Tag |
|---|---|---|---|
| G1-01 | Correlation uses `X-Correlation-Id`, minted by the platform (`CorrelationId.random()`), not the W3C trace ID (HD5). Read in `CorrelationIdFilter`, propagated by the REST client, mirrored as tracing baggage. | `core/…/CoreProperties.java` (`headerName` default `X-Correlation-Id`, `generateIfMissing`); `restclient/…/DefaultPlatformRestClientFactory.java` (`CORRELATION_HEADER`); `observability/…/PlatformObservabilityEnvironmentPostProcessor.java:56` (`baggage.remote-fields`) | P0 |
| G1-02 | Removing `dc.platform.core.correlation.*` needs deprecation metadata (CLAUDE.md rule 8) and a consumer-visible changelog entry; archetype tests and `example-minimal/PingController` reference the header. | archetype `GreetingClientTest`, `StructuredLoggingTest`, `HttpIntegrationTestSupport` | P0 |
| G1-03 | `CorrelationId` is public `@API STABLE`; per plan keep the type, source its value from the trace ID (32 lowercase hex = W3C trace-id format, so the existing validation already fits). | `core/platform-core-api/…/CorrelationId.java` | P1 |
| G1-04 | Rejected inbound correlation value is logged via the exception message, which embeds the raw header value (CR/LF / oversize reaches the log at DEBUG). | `CorrelationIdFilter.resolve` + `CorrelationId` constructor message | P0 |
| G1-05 | ProblemDetail carries `correlationId`, not `traceId` (HD5). | `errors/…/internal/ProblemDetailFactory.java` | P0 |
| G1-06 | `PlatformException` with status ≥ 500 returns `exception.getMessage()` as `detail` — internal text reaches the caller (e.g. `RemoteCallException` message names the client and status). | `errors/…/PlatformExceptionHandler.java` `handlePlatformException` | P0 |
| G1-07 | Validation errors echo `rejectedValue`, redacted only by field-name substring (`password`/`secret`/`token`); other PII (national ID, email, phone) is echoed. Prefer allow-list or omit by default. | `ProblemDetailFactory.redact` | P1 |
| G1-08 | `type` URIs default to `https://errors.dc.com/` — a domain the platform does not own. | `ErrorsProperties` `typeBaseUri` | P1 |
| G1-09 | No `retryable` / `retryAfter` ProblemDetail properties. | `ProblemDetailFactory` | P1 |
| G1-10 | No context propagation across `@Async` / executors (no `TaskDecorator`, no Micrometer context-propagation wiring) — §7.6. | no matches for `TaskDecorator`/`ContextSnapshot` in `src/main` | P0 |
| G1-11 | Build-time error-code uniqueness: verify the existing registry check covers every module (`ErrorCodeTest` exists in core-api only). | `core/platform-core-api` tests | P1 |
| G1-12 | No config-default snapshot test (§7.2), no override test per `@Bean` beyond the matrix (§7.1) — applies to every phase. | all autoconfigure modules | P1 |

## Phase 2 — Logging + Observability

| ID | Gap | Evidence | Tag |
|---|---|---|---|
| G2-01 | Readiness group includes `db,rabbit,redis` — a shared-dependency outage marks **all** replicas unready (violates HD6 / §7.5). | `PlatformObservabilityEnvironmentPostProcessor.java:48-49` | P0 |
| G2-02 | Actuator exposure default `health,info,platform,metrics,prometheus`; plan wants `health,info,prometheus` only. `metrics` and `platform` exposed and (see G7-02) unauthenticated. | `…EnvironmentPostProcessor.java:41-42` | P0 |
| G2-03 | No redaction in the JSON encoder (LogstashEncoder with full MDC); no masking of `Authorization`, JWTs, passwords. `LogSanitizer` exists in the API but is opt-in per call. | `logging/…/logback-platform.xml`; `logging-api/LogSanitizer.java` | P0 |
| G2-04 | No log-injection neutralisation for user-controlled message arguments (JSON encoding escapes CR/LF in JSON mode; console/`local` mode does not). | logback config | P1 |
| G2-05 | MDC carries `correlationId`; HD5 requires `traceId`/`spanId` on every line (Boot's tracing MDC keys) and removal of the platform key. | logback comment, `RequestContext` | P0 |
| G2-06 | No metric tag-cardinality guard (§7.9). | — | P1 |
| G2-07 | Sampling policy: no "always record errored requests" decision documented. | — | P1 |
| G2-08 | OTLP export is off by default (`otlp.enabled=false`); document as interim posture, verify on-switch path. | `…EnvironmentPostProcessor.java:57-61` | P1 |

## Phase 3 — Database / Data Access

| ID | Gap | Evidence | Tag |
|---|---|---|---|
| G3-01 | **No Oracle support at all** (no driver, no dialect tests, no Testcontainers). Boot manages the `ojdbc11` version, so this is a dependency + test add, not a module. | no `ojdbc`/`oracle` in any POM or `src/main` | P0 |
| G3-02 | No Hikari defaults (max pool, connection timeout, leak detection), no statement/transaction timeouts. | `PlatformDataJpaEnvironmentPostProcessor` sets only JPA/Hibernate keys | P0 |
| G3-03 | Flyway posture not pinned: `clean-disabled`, `validate-on-migrate`, `out-of-order` left to Boot defaults; no naming-convention check. | `data-jpa` sources | P1 |
| G3-04 | Exception translation: constraint violation → ProblemDetail without SQL is untested; generic handler masks it as 500 today. | `PlatformExceptionHandler` has no `DataIntegrityViolationException` mapping | P0 |
| G3-05 | Only one `@Tag("docker")` test (`PostgresParityIT`); no Toxiproxy degradation tests, no pool-exhaustion test. | data-jpa tests | P0 |
| G3-06 | DDL vs runtime role separation undocumented / unverified. | — | P1 |
| G3-07 | RAC failover (FCF/ONS, Application Continuity). | HD4 | `BLOCKED` |

## Phase 4 — Transactional Outbox

| ID | Gap | Evidence | Tag |
|---|---|---|---|
| G4-01 | **No transactional outbox exists.** `DomainEventRelay` publishes after commit; a crash between commit and publish loses the event (platform decision D32). Requires a new module or provider feature — HD11, decided at Phase 4 Gate A. | `events/…/internal/DomainEventRelay.java`; decision-log D32 | P0 |
| G4-02 | Everything in plan §9 Phase 4 (schema, `SKIP LOCKED` drainer on PostgreSQL + Oracle, bounded drainer pool, retention, lag/parked metrics, context restore) is net-new. | — | P0 |

## Phase 5 — RabbitMQ Messaging

| ID | Gap | Evidence | Tag |
|---|---|---|---|
| G5-01 | **`EventEnvelope` has no event id.** Consumer dedupe by event id (HD2) is impossible until a stable, unique id is generated once and carried as a header. | `messaging-api/EventEnvelope.java:36` (`eventType, eventVersion, key, payload, headers, occurredAt`) | P0 |
| G5-02 | No publisher confirms / returns; `rabbitTemplate.send` without `mandatory` — unroutable messages are dropped silently. | `messaging-rabbit/RabbitEventTransport.java` `publish` | P0 |
| G5-03 | Classic queues by default (`quorumQueues=false`). | `MessagingProperties.Rabbit` | P1 |
| G5-04 | Stateless retry interceptor sleeps on the consumer thread for every attempt (blocks the container; with prefetch, delays all buffered messages). | `RabbitEventTransport.retryInterceptor` | P1 |
| G5-05 | Headers flattened to `String.valueOf`; no structured headers (event id, version, producer, `traceparent`). | `RabbitEventTransport.fromRabbitHeaders` | P0 |
| G5-06 | No DLQ replay procedure or tooling; no graceful-stop contract test (in-flight finish, no redelivery storm). | — | P1 |
| G5-07 | Context propagation publisher → consumer relies on the correlation header (G1-01); must become `traceparent`. | — | P0 |

## Phase 6 — REST Client + Resilience

| ID | Gap | Evidence | Tag |
|---|---|---|---|
| G6-01 | Error body read fully into memory (`readAllBytes`) before truncation to 1 KB — a large/streaming error body can exhaust memory. | `DefaultPlatformRestClientFactory.readBody`; `RemoteCallException` | P0 |
| G6-02 | Body snippet documented as "safe to log" but not redacted. | `RemoteCallException` javadoc | P1 |
| G6-03 | No retry / breaker / bulkhead integration in the REST client; the resilience module only sets Resilience4j property defaults, with no method classification — a consumer wiring the `default` retry config onto a POST would retry it (violates HD2). | `PlatformResilienceEnvironmentPostProcessor`; `RetryableOperation` | P0 |
| G6-04 | No overall deadline / deadline propagation; only connect (2 s) + read (10 s) timeouts. | `RestClientProperties.Defaults` | P1 |
| G6-05 | `Retry-After` not honoured; no jitter in backoff defaults. | resilience defaults | P1 |
| G6-06 | Bulkhead type vs virtual threads not decided. | — | P1 |

## Phase 7 — Security

| ID | Gap | Evidence | Tag |
|---|---|---|---|
| G7-01 | No audience (`aud`) validation, no explicit algorithm allow-list, no clock-skew bound — `oauth2.jwt(withDefaults())` only. | `PlatformSecurityAutoConfiguration` | P0 |
| G7-02 | Default `permitPaths` open `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**` (plus `/actuator/health/**`); `metrics`/`platform`/`prometheus` exposure needs an explicit authenticated posture. | `SecurityProperties` defaults | P0 |
| G7-03 | `mode=disabled` removes the chain; verify the result fails closed (no "security off because a bean was missing"). | `SecurityProperties.Mode.DISABLED` | P0 |
| G7-04 | Principal not propagated into request context / async boundaries (§7.6). | `JwtCurrentUserAccessor` reads `SecurityContextHolder` only | P1 |
| G7-05 | JWKS outage / unknown `kid` / key rotation behaviour untested. | — | P0 |

## Cross-cutting

| ID | Gap | Tag |
|---|---|---|
| GX-01 | Plugin versions were unpinned until `852ac15` (fixed before baseline; recorded for traceability). | fixed |
| GX-02 | `StartupBudgetTest` baseline (15 s) is tight even serially; local runs ranged 9–19 s (D88). Watch in Phase 8. | P1 |
| GX-03 | Golden-path gate validates generation but has no degradation, shutdown, or composition scenarios — the hardening harness adds them. | P0 |
