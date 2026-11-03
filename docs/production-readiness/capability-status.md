# Capability status

| # | Capability | Phase | Verdict | Evidence level reached | Notes |
|---|---|---|---|---|---|
| 1 | Core + Error Handling | 1 | PASS (2026-10-26, `76cfcc7`) | SLICE + CONSUMER | No backing dependency; ENVIRONMENT in Phase 8 |
| 2 | Logging + Observability | 2 | PASS (2026-10-28, `5b8e820`) | SLICE + CONSUMER | Prometheus scrape token-authenticated until Phase 7; tail sampling is a deployment responsibility (D103) |
| 3 | Database / Data Access (Oracle; PostgreSQL out for now, HD3) | 3 | PASS (2026-10-31, `29c6ed2`) | SLICE + CONTAINER + CONSUMER | Prod Oracle 19c, certified on 23ai Free in 19c mode (HD13); RAC failover `BLOCKED` (HD4); commit-outcome evidence revisited in Phase 4 (HI-0071) |
| 4 | Transactional Outbox | 4 | PASS with parked issues (2026-11-01, `03dee13`) | SLICE + CONTAINER + CONSUMER | Platform JDBC module (HD11). HI-0071, HI-0079, HI-0086 parked, to fix before production use |
| 5 | RabbitMQ Messaging | 5 | PASS (2026-11-02, `5cafcf3`) | UNIT + SLICE + CONTAINER + CONSUMER | C1 passed. One broker node; quorum failover unverified. HI-0087, HI-0093 accepted; HI-0088 accepted, review at the end of all phases |
| 6 | REST Client + Resilience | 6 | PASS (2026-11-02, `78636c0`) | SLICE + CONSUMER | No TLS, HTTP/2 or load run; the `@Retry` aspect not run. HI-0094 to HI-0098 accepted (D131); HI-0099 to fix in Phase 7 |
| 7 | Security | 7 | PASS, residuals with the owner (2026-11-03, `d56d0a0`) | SLICE + CONSUMER | C2 passed. Real Keycloak and a scripted provider; no TLS, no spans, no Keycloak HA. HI-0099 fixed; HI-0100 to HI-0103 Low, for the owner |

## Not certified (ships in the BOM, outside this initiative)

Kafka messaging, audit (all sinks — **services with regulated audit requirements must not rely on it
for compliance**), idempotency (**no server-side protection against duplicate execution of
non-idempotent requests**), cache (Caffeine/Redis), Redis, locking, rate limiting, multi-tenancy data
isolation, storage, files, feature flags, secrets, OpenAPI, scheduling, validation, native image,
schema-per-tenant. The final release assessment repeats this list.
