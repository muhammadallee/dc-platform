# Phase 8 — Data/JPA, Cache, Redis (P1, size M; Docker only for pg/redis tagged tests)

## A. Data (`data/`): data-api, data-jpa-autoconfigure, starter-data-jpa
- data-api (tech-neutral): `AuditedEntity` NO base entity (avoid #7/#8 in things-to-avoid) — instead:
  `@CreatedAtColumn`-style? Use Spring Data's own auditing; data-api ships only:
  `Money` record (amount BigDecimal, currency) + attribute converter contract note,
  `PersistenceConventions` constants (naming, column lengths), `EntityId<T>` typed-id record helper.
- data-jpa-autoconfigure (`acme.platform.data.jpa`):
  * Spring Data JPA auditing enabled (`@EnableJpaAuditing` via autoconfig, auditor = CurrentUserAccessor
    if security present else "system" — guarded optional edge, commented);
  * physical naming strategy (snake_case) + comment; sensible Hibernate defaults
    (batch size 50, timezone UTC, open-in-view **false** with a comment explaining why);
  * converters: MoneyConverter, CorrelationId/typed-id converters registered;
  * Flyway conventions: locations `classpath:db/migration`, baseline settings; fail if JPA present but
    Flyway absent unless `acme.platform.data.jpa.require-migrations=false` (startup check bean, commented).
- Tests: H2 slice tests (`@DataJpaTest`) for auditing/naming/converters; `@Tag("docker")` Postgres parity test.
- starter-data-jpa: boot starter-data-jpa + flyway-core + platform modules.

## B. Cache (`cache/`): cache-api, cache-autoconfigure, starter-cache-caffeine, starter-cache-redis
- cache-api: `CacheKeyConvention { String key(String cacheName, Object... parts) }` (default impl joins
  with ':' + app name prefix), `@PlatformCacheable`? NO — use standard `@Cacheable` (things-to-avoid #25);
  api ships only the convention + `CacheNames` constants guidance.
- cache-autoconfigure (`acme.platform.cache`): `spec` map `caches.<name>.ttl/max-size`; decorates any
  `CacheManager` with metrics + key convention; caffeine default manager when caffeine on classpath and
  no user manager; redis manager config when redis classpath (serialization: String keys, JSON values, commented).
- Tests: matrix; caffeine ttl/size behavior; redis `@Tag("docker")`.

## C. Redis conventions (`redis/`): redis-autoconfigure, starter-redis
- Client conventions only: `RedisProperties` extras (`acme.platform.redis`): key-prefix (default app name),
  `StringRedisTemplate` customizer applying prefix via key serializer wrapper (commented caveat re: SCAN),
  timeouts, `CapabilityDescriptor`. Tests: unit + `@Tag("docker")` prefix round-trip.

Acceptance: root verify docker-free (H2/caffeine paths); `-Pdocker` pg+redis suites green; docs; BOM; CHANGELOG.
