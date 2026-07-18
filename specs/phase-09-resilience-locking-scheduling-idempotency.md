# Phase 9 — Resilience, Locking, Scheduling, Idempotency (P2, size M, no Docker required)

## A. Resilience (`resilience/`): resilience-api, resilience-autoconfigure, starter-resilience
- Use Resilience4j spring-boot3 (pin now). resilience-api: NO wrapper annotations (use r4j's own —
  things-to-avoid #25); api ships `ResilienceDefaults` constants + `RetryableOperation` functional helper
  `T call(String name, Supplier<T>)` interface for programmatic use.
- autoconfigure (`acme.platform.resilience`): default instance configs (retry 3/exp, cb 50% window 10,
  timelimiter 5s) injected as r4j configuration properties defaults via EnvironmentPostProcessor
  (user yaml wins — same pattern as health groups, comment it); Micrometer binding on;
  `RetryableOperation` bean over r4j registries.
- Tests: matrix; behavior tests (fail-then-succeed retry count; cb opens).

## B. Locking (`locking/`): locking-api, locking-spi, locking-jdbc (default), locking-redis, autoconfigure, starters
- locking-api:
```java
/** Distributed lock manager. Locks are reentrant=NO, auto-expiring. */
public interface LockManager {
    /** Try to run action while holding the named lock; skip (return empty) if not acquired. */
    <T> Optional<T> withLock(String name, Duration atMost, Callable<T> action) throws LockException;
}
```
- spi: `LockProvider { Optional<LockHandle> tryAcquire(String name, Duration atMost); }`.
- jdbc impl: single `platform_lock` table (flyway script SHIPPED as `db/migration-platform-locking/…`,
  location auto-appended by autoconfigure — comment mechanism), H2-tested.
- redis impl: SET NX PX + safe-release Lua (commented script), `@Tag("docker")` + docker-free unit tests
  against a fake RedisTemplate? Use embedded? No embedded redis — mock command-level, docker for integration.
- autoconfigure: back-off order comment: user bean > redis (if classpath+enabled) > jdbc (if DataSource).

## C. Scheduling (`scheduling/`): scheduling-autoconfigure, starter-scheduling
- `@EnableScheduling` + virtual-thread scheduler; `@LockedSchedule(name, atMost)` composed annotation:
  aspect wraps method in `LockManager.withLock` when a LockManager bean exists, else plain (WARN once, commented).
- Tests: two-context test proving single execution via shared H2 lock table.

## D. Idempotency (`idempotency/`): idempotency-api, idempotency-autoconfigure, starter-idempotency
- api: `@Idempotent(keyExpression="...", ttl="PT24H")` for handler/service methods;
  `IdempotencyStore { boolean putIfAbsent(String key, Duration ttl); }` (SPI-lite in api, jdbc default impl
  reusing the locking table pattern with its own `platform_idempotency` table; cache/redis-backed store
  auto-chosen if present — commented order).
- HTTP filter mode (`acme.platform.idempotency.http.enabled=false` default): honors `Idempotency-Key` header
  on POST, replays 409 on duplicates (response replay is OUT of scope v1 — reject-duplicate only; comment).
- Tests: SpEL key eval, duplicate rejection, ttl expiry (H2 + clock abstraction).

Acceptance: root verify docker-free; docs pages ×4; BOM; CHANGELOG.
