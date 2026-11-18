# Decisions — Spring-native realignment

The owner answered the Phase 0 decision record on 2026-11-19. Those answers reshaped the plan into
[`plan.md`](plan.md) v2: two iterations, seven capabilities, basic auth only.

- **Answered:** decisions the owner answered directly say so.
- **Accepted by default:** decisions the owner did not comment on are marked "accepted as recommended". Say the word and any
  of them reopens.
- **Still open:** ND5 (after the Phase 5 trial) and ND11b (Phase 9 Gate A). ND12–ND15 were answered "recommended" on 2026-11-19.

Evidence: [`phase-00-baseline/spikes.md`](phase-00-baseline/spikes.md) (S1–S7),
[`phase-00-baseline/inventory.md`](phase-00-baseline/inventory.md),
[`phase-00-baseline/removal-map.md`](phase-00-baseline/removal-map.md).

## Decided

| ID | Decision | Answer | Date |
|---|---|---|---|
| NC1 | Where the work happens | Long-running branch `develop`, from `main` at `bdf2845`. Phase PRs target `develop`. Only the owner merges `develop` into `main` | 2026-11-17 |
| NC2 | Labs | Every capability has a guided lab, from nothing to a running archetype-generated service (plan §5.10) | 2026-11-17 |
| ND1 | Timing and version | **Owner: the realigned platform becomes 1.0.0.** `main` keeps the v3 platform and its RC1 GO until the owner merges `develop`; Phase 1 adds a "superseded on `develop`" line to the v3 assessment and release decision | 2026-11-19 |
| ND2 | What is cut | **Owner: keep only the needed list.** See the answer to "why did validation and OpenAPI stay?" below. Iteration 1: logging, observability, data (Spring Data JPA), messaging and outbox (RabbitMQ only). Iteration 2: REST API (basic auth, with OpenAPI / Swagger UI documentation, owner 2026-11-19), REST client (basic auth + OAuth client credentials). Everything else is cut; the module list is plan §2.5 | 2026-11-19 |
| ND3 | Spring types in service code; an API module only where no Spring type fits | Accepted as recommended. Recorded as ADR-011 in Phase 1 | 2026-11-19 |
| ND4 | How APIs are secured | **Owner: basic auth only.** The API gateway authenticates callers (JWT or OAuth client credentials) and calls the service with basic auth. No JWT resource server, no `@RequiresPermission`, no `@PreAuthorize` layer. Built new in Phase 8 | 2026-11-19 |
| ND6 | `platform-test-api` | Accepted as recommended: keep, slimmed. `TestJwtIssuer` stays only if Phase 9 uses it as the lab's token endpoint (ND11b); otherwise it goes with JWT security | 2026-11-19 |
| ND7 | `EntityId`, `Money`, `PersistenceConventions` | Accepted as recommended: drop (Phase 2) | 2026-11-19 |
| ND8 | How cut code is preserved | Accepted as recommended: deleted from `develop`; tag `pre-spring-native` is the restore point | 2026-11-19 |
| ND9 | Locking API | **Owner: drop it unless the chassis itself needs it.** Checked 2026-11-19: no kept module depends on `locking/*` (the outbox drainer uses `FOR UPDATE SKIP LOCKED` directly). Dropped, with scheduling | 2026-11-19 |
| ND10 | Kafka | Accepted as recommended: messaging is RabbitMQ-only Spring AMQP. Kafka can return later as its own capability (plan §14) | 2026-11-19 |
| ND11 | Lab infrastructure for iteration 1 | Accepted as recommended, trimmed to the new scope: H2 first, then Oracle Free, plus RabbitMQ. No identity provider is needed in iteration 1. Phase 1 removes Kafka, Redis, Vault, LocalStack and Postgres from the compose file and adds Oracle Free | 2026-11-19 |

| ND12 | Version per iteration | **Owner: recommended.** **Iteration 1 ships as 1.0.0, iteration 2 as 1.1.0.** Iteration 2 only adds capabilities, which semantic versioning calls a minor release. To keep 1.0.0 honest, the iteration 2 modules (`restclient/*`, `resilience/*`) are parked during iteration 1: built and tested, but not in the BOM, not deployed and not in the archetype (plan §2.4). **The tradeoff:** 1.0.0 would ship with no HTTP security. Iteration 1 keeps Spring MVC (actuator and C1 start from an HTTP request), so any controller a 1.0.0 adopter writes is open until 1.1.0. Fine for messaging-only services, not for API services. *Alternative:* iteration 1 as `1.0.0-M1`, and 1.0.0 only after iteration 2 adds basic auth | 2026-11-19 |
| ND13 | Actuator in iteration 1, which has no web security | **Owner: recommended.** **Run management on its own port** (`management.server.port`, default `8081`), never routed through the API gateway or the OpenShift route. Probes stay reachable for the kubelet; `/actuator/prometheus` is reachable only inside the cluster. The application port then serves only the API. *Alternative:* bring basic auth forward into iteration 1 for actuator only. That mixes iteration 2 work into iteration 1 | 2026-11-19 |
| ND14 | Where the OpenAPI document and Swagger UI are reachable | **Owner: recommended.** **On by default, behind the same basic auth as the API.** A property (`springdoc.swagger-ui.enabled=false`) turns the UI off for production while `/v3/api-docs` stays for the gateway or a developer portal. v3 required docs disabled or protected by default; this keeps them protected | 2026-11-19 |
| ND15 | Module layout per capability | **Owner: recommended.** **Two modules per capability:** `platform-<cap>-autoconfigure` (auto-configuration, properties, internal beans) and `platform-starter-<cap>` (POM only). No `-api` module: services use Spring types (ND3). No `-spi` module: each capability has one provider (RabbitMQ, JPA). A third module only with a decision, e.g. a provider starter such as `platform-starter-data-jpa-oracle`, or a small API if ND5 keeps the platform outbox. Resilience merges into the REST client, and OpenAPI into the REST API. Applied in each capability's phase, recorded in ADR-011 and `CLAUDE.md` rules 4, 5 and 7 in Phase 1 | 2026-11-19 |

### Why did validation and OpenAPI stay in v1 of the plan?

**Why they stayed:**
- Only because the archetype put both starters into every generated service, so cutting them would have changed every
  service.
- Neither was certified by the hardening initiative.

**Why they can go now:**
- **Validation:** Spring Boot already ships Bean Validation (`@Valid`, `@NotBlank`, …). The platform module only added four
  custom constraints and its own message bundle. What a service really needs is for a validation failure to become a clean
  400 `ProblemDetail` with field paths. The errors module already does that, and Phase 2 proves it.
- **OpenAPI:** *update, owner 2026-11-19:* it comes back as part of the REST API capability (iteration 2). The `openapi/*`
  modules are parked, not cut, and Phase 8 reworks them: basic security scheme, docs behind basic auth.
- **Coming back:** either one can return later as its own capability (plan §14).

## Still open

| ID | Decision | Recommendation | Blocks |
|---|---|---|---|
| ND5 | Outbox: Spring Modulith, or keep the platform outbox | **Decide on the Phase 5 trial's evidence.** Rule agreed now: Modulith wins only if it passes every v3 outbox scenario and every carried guarantee, or each gap has an accepted mitigation. Known gaps to check: a 4000-byte payload column (the platform outbox has a `BLOB`), DDL that needs Oracle 23c while production is 19c, unconfirmed publisher-confirm handling | Phase 6 |
| ND11b | Token endpoint for the REST client lab and tests (OAuth client credentials) | **Decide at Phase 9 Gate A.** Candidates: Keycloak in the compose file (realistic; ~1 GB memory), or a small local token endpoint built on the test kit (no Docker) | Phase 9 |
