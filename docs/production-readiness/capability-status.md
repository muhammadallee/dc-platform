# Capability status

| # | Capability | Phase | Verdict | Evidence level reached | Notes |
|---|---|---|---|---|---|
| 1 | Core + Error Handling | 1 | PASS (2026-10-26, `76cfcc7`) | SLICE + CONSUMER | No backing dependency; ENVIRONMENT in Phase 8 |
| 2 | Logging + Observability | 2 | NOT STARTED | — | |
| 3 | Database / Data Access (PostgreSQL + Oracle) | 3 | NOT STARTED | — | Oracle RAC failover `BLOCKED` (HD4) |
| 4 | Transactional Outbox | 4 | NOT STARTED | — | No implementation at baseline; approach decided at Gate A (HD11) |
| 5 | RabbitMQ Messaging | 5 | NOT STARTED | — | Composition checkpoint C1 |
| 6 | REST Client + Resilience | 6 | NOT STARTED | — | |
| 7 | Security | 7 | NOT STARTED | — | Composition checkpoint C2 |

## Not certified (ships in the BOM, outside this initiative)

Kafka messaging, audit (all sinks — **services with regulated audit requirements must not rely on it
for compliance**), idempotency (**no server-side protection against duplicate execution of
non-idempotent requests**), cache (Caffeine/Redis), Redis, locking, rate limiting, multi-tenancy data
isolation, storage, files, feature flags, secrets, OpenAPI, scheduling, validation, native image,
schema-per-tenant. The final release assessment repeats this list.
