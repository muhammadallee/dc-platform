# Decisions — Spring-native realignment

Plan §6 step 8. **Waiting for the owner.**
- **How to answer:** reply to each item with "recommended" or your own answer. Each row moves from `PENDING` to `ANSWERED`
  with the date.
- **What waits on what:** an unanswered decision blocks only the phase that needs it.

Evidence: [`phase-00-baseline/spikes.md`](phase-00-baseline/spikes.md) (S1–S7),
[`phase-00-baseline/inventory.md`](phase-00-baseline/inventory.md),
[`phase-00-baseline/removal-map.md`](phase-00-baseline/removal-map.md).

## Already decided by the owner

| ID | Decision | Date |
|---|---|---|
| NC1 | The realignment runs on the long-running branch `develop`, from `main` at `bdf2845`. Phase PRs target `develop`; nothing is merged into `main` by this plan; the owner merges `develop` into `main` when ready | 2026-11-17 |
| NC2 | Every capability has a guided lab that takes a developer from nothing to a running archetype-generated project (plan §4.10) | 2026-11-17 |

## Pending

| ID | Decision | Recommendation | Needed by | Status |
|---|---|---|---|---|
| ND1 | Timing: realign **before** `1.0.0-RC1`, or ship RC1 on the current API and realign for 2.0 | **Before RC1.** There are no users (HC7), so removing an API now costs nothing; after RC1 it is a breaking change for adopters. Since you chose a parallel stream, `main` keeps the v3 platform (and its RC1 GO) until you merge `develop`; the hardening record gets a "superseded" banner only on that merge | Phase 1 | PENDING |
| ND2 | The cut list | **As plan §1.3**, confirmed against the code: 44 modules (inventory). Validation and OpenAPI stay (every archetype service uses them); the Redis cache starter and the Redis lock starter go. `scratch/` and `tmpviol/` are untracked local leftovers: delete them locally, no repository change | Phase 1 | PENDING |
| ND3 | Constitution: service code uses Spring types; an API module exists only where no Spring type fits | **Yes**, as ADR-011 (refines ADR-009). `CLAUDE.md` rules 4, 5 and 9 and the enforcer/ArchUnit rules change in Phase 1. Spikes S2–S7 show Spring has a type for every rewrite except locking (ND9) | Phase 1 | PENDING |
| ND4 | Authorization: `@PreAuthorize("hasAuthority('…')")`, or keep `@RequiresPermission` | **`@PreAuthorize`.** S6 shows the two things the v3 fix was for now hold in Spring itself: a rule on an implementing method behind an interface proxy, and a stricter method rule beating a weaker class rule. The platform keeps deny-by-default and the open-path startup check, and adds a startup check of expressions against declared permissions (typos) | Phase 5 | PENDING |
| ND5 | Outbox: Spring Modulith, or keep the platform outbox | **Decide after the Phase 6 trial.** S1: Modulith 2.1.2 is built on Boot 4.1.2 and ships an Oracle schema and an AMQP module, so the trial can run. Seven open items go into it (M1–M7), among them a 4000-byte payload column where the platform outbox has a `BLOB`, DDL that needs Oracle 23c while production is 19c, and unconfirmed publisher-confirm handling. Rule agreed in advance: Modulith wins only if it passes every v3 Phase 4 scenario and every carried guarantee | Phase 7 | PENDING (confirm the rule now; the choice comes after Phase 6) |
| ND6 | `platform-test-api` | **Keep, slimmed:** `TestJwtIssuer`, `TestTokens`, the ProblemDetail assertions, the usage rules and `Containers` stay; the `@Platform*Test` meta-annotations go if Boot 4's test slices cover them (checked per phase) | Phase 2 | PENDING |
| ND7 | `EntityId`, `Money`, `PersistenceConventions` | **Drop.** The certified data contract does not use them | Phase 2 | PENDING |
| ND8 | How cut code is preserved | **Delete from `develop`**, with tag `pre-spring-native` (pushed) as the restore point. No archive branch | Phase 1 | PENDING |
| ND9 | Locking API | **Decide at Phase 9 Gate A.** Spring has no type for an explicit lock; the candidates are keeping `LockManager`, Spring Integration's `LockRegistry`, or ShedLock for scheduled jobs only | Phase 9 | PENDING (no answer needed now) |
| ND10 | Kafka portability is given up | **Accept.** Messaging becomes RabbitMQ-specific Spring AMQP; a future Kafka capability would be Spring Kafka in its own phase | Phase 1 | PENDING |
| ND11 | How a lab gets the infrastructure it needs to start, without turning security off | See below | Phase 2 | PENDING |

## ND11 — lab infrastructure

**What Phase 0 found:**
- **The app starts without an identity provider.** A generated app boots offline, because the archetype's
  `application.yml` sets placeholder `issuer-uri` / `jwk-set-uri` values and the JWKS is fetched lazily. It rejects every
  token until a real issuer is configured. Security stays fail-closed.
- **The golden path uses a local token issuer.** It boots generated apps and validates real tokens from a loopback issuer
  built on the test kit's `TestJwtIssuer`, with no Docker.
- **The compose file lacks what labs need.** `docker-compose.local.yml` has no Oracle and no Keycloak. It has Kafka, Redis,
  Postgres, Vault and LocalStack, mostly for cut capabilities.
- **The archetype's database defaults to H2;** `-Ddatabase=oracle` adds the Oracle starter.
- **Oracle Free is the heaviest container.** Low-memory kills ended runs in v3 Phases 3 and 4.

**Recommendation:**
1. **A local token issuer as a developer command.** Package the golden path's loopback issuer as a command a developer can
   run, for example a `dev-issuer` goal of `platform-build-maven-plugin`, or a runnable jar from the test kit. It prints the
   issuer URL, the JWKS URL and a ready-made token. Every lab except security uses it: real token validation, no Docker, no
   security switched off.
2. **Keycloak for the security lab only.** Add Keycloak to `docker-compose.local.yml` with a realm imported at start-up; the
   security lab shows the real flow, as v3 Phase 7 certified it.
3. **H2 first in the data lab, then Oracle.** The basic run uses H2, the archetype default, so the project is up in minutes.
   A second section switches to Oracle Free (added to the compose file, with its memory stated). The lab runner executes
   both.
4. **Clean up the compose file in Phase 1.** Remove Kafka, Redis, Vault and LocalStack (cut capabilities) and Postgres (HD3);
   add Oracle Free and Keycloak.

**Alternative:** Keycloak for every lab. More realistic, but every lab would need Docker and about 1 GB more memory.
