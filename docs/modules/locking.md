# Locking

Cluster-wide mutual exclusion: run a block of code on at most one instance at a time. Locks are
**non-reentrant** and **auto-expiring** (a crashed holder never wedges the cluster), and acquisition
is **non-blocking** — if the lock is held, the call skips rather than waits.

## What you get

- **`LockManager`** — `withLock(name, atMost, action)` runs `action` while holding the named lock and
  returns its result, or returns `Optional.empty()` if the lock could not be acquired.
- **Two providers, chosen by classpath:**
  - **JDBC (default)** — a single `platform_lock` table with token-fenced, expiry-column-driven
    auto-expiry. Its Flyway migration ships with the provider and its location is appended
    automatically. Requires a `DataSource`.
  - **Redis** — `SET NX PX` to acquire, a token-fenced Lua script to release. Selected over JDBC when
    a `StringRedisTemplate` is present.

## Starter coordinates

Pick one provider:

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-locking-jdbc</artifactId>
</dependency>
```

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-locking-redis</artifactId>
</dependency>
```

## Usage

```java
lockManager.withLock("nightly-reconcile", Duration.ofMinutes(5), () -> {
    reconcile();
    return null;
});
```

An empty return means the lock was held elsewhere and the action was skipped — the normal way to make
a scheduled job singleton across the cluster (see the scheduling capability's `@LockedSchedule`).

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.locking.enabled` | `true` | Kill switch for the whole capability. |

The provider is selected by the classpath (Redis over JDBC), not by a property.

## Replace / Disable

- Define your own `LockManager` bean to replace the platform manager entirely (it backs off).
- Define your own `LockProvider` bean to plug a different backend under the platform `LockManager`.
- `dc.platform.locking.enabled=false` switches the capability off wholesale.

## Local dev notes

The JDBC provider is fully tested against H2 — no Docker. The Redis provider's command-level behavior
is unit-tested with mocks; its wire behavior is verified against a real Redis in a `@Tag("docker")`
integration test run under `-Pdocker`.
