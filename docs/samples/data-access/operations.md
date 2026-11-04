# Operations

## Posture

| Situation | Behaviour | Verified |
|---|---|---|
| Oracle down (refused) | DB requests `503 DC-DATA-0503` (`retryable: true`) within the 5 s connection timeout; non-DB requests unaffected; readiness and liveness UP; `dependencies` DOWN; recovers without a restart | CONSUMER (≤ 12 s; recovery < 60 s) |
| Oracle slow | Requests bounded by the connection timeout (5 s), the transaction timeout (30 s) and the driver read timeout (60 s); overflow beyond the pool fails fast; no thread pile-up | CONSUMER (40 s latency: all ≤ 75 s, ≥ 10 fast 503s, threads +10) |
| Oracle silent mid-request | Ended by the driver read timeout: `503 DC-DATA-0503` | CONSUMER (63 s) |
| Pool exhausted | Requests beyond the pool size get `503 DC-DATA-0503` after 5 s; the pool recovers | CONSUMER (10 of 20 at ≤ 10 s) |
| Connection lost while committing | Non-retryable `500` (the outcome is unknown; for Spring's imperative transaction managers, HI-0071) | UNIT (real translator and transaction manager) |
| Constraint violation | `409 DC-DATA-0409`; concurrent update or lock `409 DC-DATA-0410` | CONTAINER + CONSUMER |
| Oracle down at startup | Exits non-zero in about 10 s naming the connection error | CONSUMER (8–11 s) |
| Oracle accepts but never answers at startup | Exits non-zero after Flyway's read timeout (10 min) | CONSUMER (608–610 s) |
| SIGTERM | In-flight transaction completes and commits; pool shut down; exit 143 | CONSUMER |

## Defaults (lowest precedence; set the key to change it)

| Key | Default | Why |
|---|---|---|
| `spring.datasource.hikari.connection-timeout` | `5000` | Hikari's 30 s lets request threads pile up behind an exhausted pool |
| `spring.transaction.default-timeout` | `30s` | bounds every transaction, repository methods included |
| `spring.jpa.properties.jakarta.persistence.query.timeout`, `spring.jdbc.template.query-timeout` | `30000` ms, `30s` | bounds queries outside a transaction |
| `dc.platform.data.jpa.oracle.read-timeout` | `60s` | driver backstop on the pool: the longest it waits for an answer (raise it for longer statements) |
| `dc.platform.data.jpa.oracle.flyway-read-timeout` | `10m` | the same on Flyway's own connection, so a long migration statement can finish |
| `oracle.net.CONNECT_TIMEOUT` (added to the pool and Flyway's connection) | `10000` ms | unreachable host; covers only the TCP connect |
| `spring.datasource.hikari.leak-detection-threshold` | `20000` under `local` only | never with `prod` |
| `spring.flyway.clean-disabled` / `validate-on-migrate` / `out-of-order` / `validate-migration-naming` | `true` / `true` / `false` / `true` | out-of-order is refused outside `local`, however enabled |

## Health groups

| Group | Contains `db`? | Use it for |
|---|---|---|
| `/actuator/health/readiness`, `/liveness` | no (pod-local) | probes: a database outage does not take every pod out of rotation |
| `/actuator/health/dependencies` | yes | dashboards and alerts on the database |

## What to watch

| Signal | Where | Why |
|---|---|---|
| `hikaricp_connections_pending` > 0 for minutes | Prometheus | pool too small or statements too slow |
| `hikaricp_connections_timeout_total` rising | Prometheus | requests getting 503 behind an exhausted pool |
| `hikaricp_connections_active` at `hikaricp_connections_max` | Prometheus | saturation |
| `spring_data_repository_invocations_seconds` p99 | Prometheus | slow repository methods (tags: repository, method, state — no IDs) |
| rate of `503 DC-DATA-0503` / `409 DC-DATA-0409` | access logs or `http_server_requests_seconds_count{status=…}` | database availability / conflicting writes |
| `dependencies` DOWN | `/actuator/health/dependencies` | the database is down while pods keep serving |
| WARN `data access failure [DC-DATA-…]` | logs, by `traceId` | the full exception for one failed request |

## Kill switch and replacement

- **Kill switch:** `dc.platform.data.jpa.enabled=false` turns off the whole capability (defaults, Oracle
  bounds, Flyway checks, auditing, descriptor). Tier: operational, a restart is needed; the service then runs
  on Boot's own defaults (unbounded waits). Break-glass only.
- **Replace a bean** by defining one of the same name:

| Bean | Replaces |
|---|---|
| `platformOracleConnectionDefaults` | the Oracle driver bounds on Hikari pools |
| `platformOracleFlywayConnectionDefaults` | the Oracle driver bounds on Flyway's own connection |
| `platformFlywayOutOfOrderGuard` | the out-of-order refusal outside `local` |
| `platformAuditorAware` | who is recorded in `@CreatedBy` / `@LastModifiedBy` |
| `dataJpaCapabilityDescriptor` | the capability report entry |

The data error mapping lives in the errors capability: an application `@ExceptionHandler` for a data exception
type still wins.

## Graceful shutdown

`server.shutdown=graceful` (Boot default): Tomcat stops accepting, in-flight requests finish within
`spring.lifecycle.timeout-per-shutdown-phase` (30 s), their transactions commit, then Hikari closes the pool
(`HikariPool-1 - Shutdown completed`). Verified with SIGTERM during an 8 s transaction (exit 143, row committed).
