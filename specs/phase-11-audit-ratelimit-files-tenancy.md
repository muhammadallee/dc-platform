# Phase 11 — Audit, Rate Limiting, Files, Tenancy (P2/P3, size M, no Docker required)

## A. Audit (`audit/`): audit-api, audit-spi, audit-log (default sink), audit-jdbc, audit-messaging, autoconfigure, starter-audit
- api: `@Audited(action="order.create", resourceExpression="#result.id")` +
  `AuditEvent` record (action, actor, resource, outcome, at, correlationId, details map) +
  `Auditor { void record(AuditEvent e); }` for programmatic use.
- spi: `AuditSink { void write(AuditEvent e); }`.
- sinks: log (structured logger `AUDIT`, default), jdbc (`platform_audit` table + flyway, H2-tested),
  messaging (publishes via EventPublisher to `acme.audit` when messaging active — guarded edge).
- autoconfigure: aspect for @Audited (actor from CurrentUserAccessor, outcome from return/exception),
  async-by-default with bounded queue + drop policy WARN (comment the tradeoff), graceful degradation
  chain messaging→jdbc→log with startup WARN naming active sink (this exact behavior is in the arch doc §7).

## B. Rate limiting (`ratelimit/`): api, spi, inmemory (default), redis, autoconfigure, starter
- api: `@RateLimited(name, permits=100, window="PT1M", keyExpression="#user")`;
  `RateLimiter { Decision tryAcquire(String key, int permits, Duration window); }` Decision(allowed, retryAfter).
- inmemory: sliding-window counters (caffeine), per-JVM (documented limitation, WARN at startup in prod profile).
- redis: fixed-window Lua (commented), `@Tag("docker")`.
- autoconfigure: web filter mode (`http.enabled=false` default) keyed by user|ip; 429 ProblemDetail with
  Retry-After; method aspect for annotation; metrics.

## C. Files (`files/`): files-api, files-autoconfigure, starter-files
- api: `FileUploadPolicy` record (maxSize, allowedTypes via content sniffing not extension — comment),
  `SafeFilename.sanitize(String)`, `StreamingDownloads.write(ObjectStore ref → ResponseEntity<StreamingResponseBody>)` helper.
- autoconfigure: multipart limits defaults, content-type sniffing validator (Tika-core pin? prefer
  simple magic-bytes table for the v1 set: pdf/png/jpg/zip/csv/txt — comment scope), ties to storage when present.

## D. Tenancy (`tenancy/`, OPTIONAL — P3, skip unless multi-tenant org): api, spi, jpa, autoconfigure, starter
- api: `TenantId` (reuse core? core has none — define here), `TenantContext` (mirrors RequestContext pattern).
- spi: `TenantResolver { Optional<TenantId> resolve(HttpServletRequest) }`; defaults: header `X-Tenant-Id`,
  then JWT claim `tenant`.
- jpa: Hibernate discriminator strategy (`@TenantId` Hibernate 6 support) wiring; schema-per-tenant is
  documented-only v1 (decision note).
- Everything `acme.platform.tenancy.enabled=false` by default.

Acceptance: root verify docker-free; docs ×4; BOM; CHANGELOG. **Full catalog complete.**
