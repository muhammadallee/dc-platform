# DC Platform — Implementation Spec Pack (Master Plan)

**Audience:** Claude Code, executing incrementally on a local machine.
**Repo name:** `dc-platform` · **groupId:** `ae.gov.dubaicustoms.platform` · **version scheme:** `${revision}` (start `0.1.0-SNAPSHOT`)
**Toolchain:** Java 25, Maven 3.9+, Docker Desktop *optional* (only for `-Pdocker` profiles), Spring Boot latest stable 4.x (amended from 3.x — decision D6).

---

## How to use this spec pack

1. Read `CLAUDE.md` first — it defines conventions, gates, and the per-module workflow. Copy it to the repo root.
2. Execute phases **in order**. Each phase spec is self-contained: goal, modules, file trees, code contracts, tests, and an acceptance script.
3. **A phase is done only when its Acceptance block passes locally** (`mvn -T1C verify` from repo root + phase-specific commands). Never start phase N+1 with phase N red.
4. Within a phase, follow the per-module **Definition of Done** in `reference/module-checklist.md`.
5. Where a spec gives full code, use it verbatim (adjusting only versions). Where it gives signatures, implement them exactly as written — signatures are contracts; internals are yours.
6. Anything not specified: follow `reference/autoconfigure-pattern.md` and Spring Boot's own conventions. When in doubt, do what `spring-boot-autoconfigure` does.

## Local-execution guarantees (non-negotiable)

- `mvn -T1C verify` from the repo root must pass on a laptop **with no Docker, no network beyond Maven Central, no cloud credentials**.
- Docker-dependent tests (Kafka, Rabbit, Redis, Postgres, Vault, LocalStack via Testcontainers) live behind Maven profile `-Pdocker` and JUnit tag `@Tag("docker")`; they are skipped by default and auto-skip (`assumeTrue(dockerAvailable)`) when Docker is absent.
- Every multi-provider capability ships a **local/in-memory provider first** (messaging-inmemory, storage-fs, locking-jdbc+H2, secrets-env, flags-inmemory, ratelimit-inmemory, audit-log-sink, cache-caffeine). Default tests and examples run against these.
- `docker-compose.local.yml` at repo root provides optional real infra for manual runs (runbooks/local-dev.md).
- No snapshot third-party deps; no `settings.xml` requirements beyond Central.

## Phase index, priority, and sizing

| Phase | Spec file | Priority | Docker needed | Size | Ships |
|---|---|---|---|---|---|
| 1 | phase-01-foundation.md | **P0** | no | S | parents, BOMs, aggregator, compose file |
| 2 | phase-02-build-gates.md | **P0** | no | M | build-tools (enforcer+ArchUnit), scaffolding plugin skeleton |
| 3 | phase-03-core.md | **P0** | no | M | core-api, core-autoconfigure, starter-core |
| 4 | phase-04-errors-logging-validation.md | **P0** | no | M | errors, logging, validation slices |
| 5 | phase-05-observability-openapi.md | **P1** | no | M | observability, openapi slices; `platform` actuator endpoint |
| 6 | phase-06-restclient-security.md | **P1** | no | M | restclient slice; security baseline + authz api/spi |
| 7 | phase-07-messaging-events.md | **P1** | kafka/rabbit only | L | messaging api/spi + **inmemory** + kafka + rabbit; domain events |
| 8 | phase-08-data-cache-redis.md | **P1** | redis/pg only | M | data-api, data-jpa (H2-tested), cache (caffeine default, redis), redis conventions |
| 9 | phase-09-resilience-locking-scheduling-idempotency.md | **P2** | no (H2) | M | resilience, locking (jdbc default, redis), scheduling, idempotency |
| 10 | phase-10-storage-secrets-flags.md | **P2** | vault/s3 only | M | storage (fs default, s3), secrets (env default, vault), flags (inmemory, openfeature) |
| 11 | phase-11-audit-ratelimit-files-tenancy.md | **P2/P3** | no | M | audit (log→jdbc→messaging sinks), ratelimit (inmemory, redis), files, tenancy(optional, P3) |
| 12 | phase-12-test-kit-tck.md | **P1** (start after 7) | no | M | test-api, starter-test, slices, TCKs |
| 13 | phase-13-dx-archetype.md | **P1** | no | M | service archetype, upgrade-check goal, golden-path script |
| 14 | phase-14-docs.md | **P1** (continuous) | no | M | docs site, generated config reference, ADRs |
| 15 | phase-15-examples-release.md | **P1** | no | M | 4 examples, smoke matrix, release automation, `0.1.0` tag |
| 16 | phase-16-discovery.md | **P1** (after M2) | no | M–L | layered discovery: FailureAnalyzers, service dep bans, MCP server, Skill, adopt-recipes, llms.txt, adoption telemetry |

Priorities: **P0** = platform unusable without it. **P1** = golden-path service (REST + errors + logs + metrics + security + messaging + JPA + tests + docs). **P2** = full catalog. **P3** = optional (tenancy). If time-boxed, stop after any phase — the reactor is always green and usable.

> **Scope note (2026-07, decision D80): `tenancy` (P3) is out of scope for the 1.0 train** and is not
> in the reactor. It remains a candidate for a later train. Also deferred from 1.0: the **`secrets`**
> capability (phase-10) — planned for the next train (see `docs/reviews/platform-review-2026-07.md`,
> Track 2). Kafka messaging ships but is not exercised end-to-end (examples/infra use RabbitMQ).

## Milestones

- **M1 (after Phase 4):** a service can be built by hand on `platform-service-parent` with correlation IDs, JSON logs, RFC-9457 errors. Tag `0.1.0`.
- **M2 (after Phase 8 + 12 + 13):** golden path complete; archetype generates a service in <10 min. Tag `0.2.0`.
- **M3 (after Phase 15):** full catalog, docs, examples, release pipeline. Tag `1.0.0-RC1`.

## Cross-cutting requirements (apply to every phase)

- **Javadoc:** policy in `reference/coding-standards.md` §2. Public API/SPI = full javadoc (class purpose, thread-safety, nullability, `@since`); internal = header comment stating why it exists; autoconfigure classes = comment block mapping conditions → behavior.
- **Properties:** every `@ConfigurationProperties` is an immutable record under prefix `dc.platform.<cap>`, with defaults in code and metadata; see `reference/property-conventions.md`.
- **Autoconfigure pattern:** copy the canonical template in `reference/autoconfigure-pattern.md` — including its comment skeleton.
- **Tests per module:** ContextRunner condition matrix (enabled default / `enabled=false` / class missing / user bean back-off / customizer ordering) is mandatory for every autoconfigure module.
- **Docs:** each capability adds `docs/modules/<cap>.md` in the same PR (template in phase-14 spec) — do not defer.
- **Commits:** conventional commits, one logical unit each: `feat(messaging): add EventPublisher API`.

## Runbooks & references in this pack

- `reference/coding-standards.md` — naming, javadoc/comment policy, nullability, error-code registry.
- `reference/property-conventions.md` — property naming, kill switches, metadata, deprecation.
- `reference/autoconfigure-pattern.md` — the canonical, fully-commented autoconfigure + starter + test template.
- `reference/module-checklist.md` — per-module Definition of Done.
- `runbooks/local-dev.md` — build/run/debug locally, docker profile, troubleshooting.
- `runbooks/new-capability.md` — adding a capability end-to-end.
- `runbooks/release.md` — cutting a train locally and in CI.
- `runbooks/upgrade.md` — consuming teams' upgrade procedure.
