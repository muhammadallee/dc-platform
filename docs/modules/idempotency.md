# Idempotency

Make a method (or an HTTP POST) take effect at most once per key within a time window, so a retried
or duplicated request does not double-apply.

## What you get

- **`@Idempotent(keyExpression, ttl)`** — annotate a method; the platform derives a key from the SpEL
  `keyExpression` (evaluated over the arguments — `#a0`, `#p0`, or by parameter name), records it, and
  rejects a duplicate within `ttl` as **HTTP 409** (via the errors capability's `ConflictException`).
- **`IdempotencyStore`** — the pluggable store, chosen by classpath:
  - **JDBC (default)** — a `platform_idempotency` table with expiry-column TTL; its Flyway migration
    ships and its location is appended automatically. Requires a `DataSource`.
  - **Redis** — `SET NX PX`, selected when a `StringRedisTemplate` is present.
- **`Idempotency-Key` HTTP filter (off by default)** — when
  `dc.platform.idempotency.http.enabled=true`, a duplicate POST carrying a repeated `Idempotency-Key`
  header is rejected with 409. Reject-duplicate only — the first response is **not** replayed (v1 scope).

## Starter coordinates

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-idempotency</artifactId>
</dependency>
```

The default JDBC store needs a `DataSource`; add Spring Data Redis to switch to the Redis store. Pair
with `platform-starter-errors` so `@Idempotent` duplicates render as RFC-9457 409 responses.

## Usage

```java
@Idempotent(keyExpression = "#command.orderId", ttl = "24h")
public void placeOrder(PlaceOrderCommand command) { ... }
```

## Properties

| Key | Default | Meaning |
|---|---|---|
| `dc.platform.idempotency.enabled` | `true` | Kill switch for the whole capability. |
| `dc.platform.idempotency.http.enabled` | `false` | Enable the `Idempotency-Key` POST filter. |
| `dc.platform.idempotency.http.header-name` | `Idempotency-Key` | The request header carrying the key. |
| `dc.platform.idempotency.http.ttl` | `24h` | How long a seen HTTP key is remembered. |

The store is selected by the classpath (Redis over JDBC), not by a property.

## Replace / Disable

- Define your own `IdempotencyStore` bean to plug a different backend (it backs off).
- `dc.platform.idempotency.enabled=false` switches the capability off wholesale.

## Local dev notes

The JDBC store is fully tested against H2 — no Docker. SpEL key evaluation, duplicate rejection, and
TTL expiry (with a clock abstraction) are covered by unit and end-to-end tests.
