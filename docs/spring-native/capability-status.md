# Capability status — Spring-native realignment

Updated at each phase closure. Until a capability's phase closes, its v3 status
([`../production-readiness/capability-status.md`](../production-readiness/capability-status.md)) describes the code.

| Capability | Iteration | v3 verdict | Phase | Lab | Status |
|---|---|---|---|---|---|
| Foundation: core + errors | 1 | PASS | 2 | first service | in progress (Gate B) |
| Logging + observability | 1 | PASS | 3 | logging and observability | not started |
| Data (Spring Data JPA, Oracle) | 1 | PASS, RAC `BLOCKED` | 4 | data | not started |
| Outbox (RabbitMQ) | 1 | PASS with parked issues | 5 (trial), 6 | outbox | not started |
| Messaging (RabbitMQ) | 1 | PASS | 6 | messaging | not started |
| REST API (basic auth) | 2 | new (v3 certified JWT security, which is cut) | 8 | REST API | not started |
| REST client + resilience | 2 | PASS | 9 | REST client | parked during iteration 1 |
| Cut (plan §2.5) | — | — | 1 | — | removed in Phase 1 (69 modules; tag `pre-spring-native` restores them) |
