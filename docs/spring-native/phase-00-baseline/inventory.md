# Phase 0 inventory

Plan §6 steps 1, 2 and 5. Recorded 2026-11-17.

## Baseline

| Item | Value |
|---|---|
| Baseline commit | `bdf2845` (`main`), tag `pre-spring-native` |
| Working branch | `develop` (from `bdf2845`); phase branches `native/phase-XX-<slug>` |
| JDK / Maven | Corretto 25.0.3; Maven 3.9.9 |
| Spring Boot | 4.1.2 (D133) |
| Reactor | 124 `pom.xml` files (modules, the root, the archetype's template POM and examples) |
| Platform BOM | 114 `<artifactId>` entries in `build/platform-bom/pom.xml` |
| Archetype starters, every service | core, errors, logging, validation, observability, openapi, security, test |
| Archetype starters, by option | `data` → data-jpa (+ `database=oracle` → data-jpa-oracle); `restclient`; `messaging` (in-memory) |

## Compatibility baseline (step 2)

- **Where it lives:** `~/.m2-native-baseline`, a file repository holding only the platform artifacts of `pre-spring-native`.
- **How it was built:**
  - from a clean detached worktree at the tag;
  - `mvn -T1 -B -ntp deploy -DskipTests -Djacoco.skip=true -DaltDeploymentRepository=native-baseline::file:///C:/Users/Ali/.m2-native-baseline`;
  - exit 0, `BUILD SUCCESS` in 3:32.
- **What it contains:** 111 jars, 116 POMs; the BOM has no unresolved `${…}`.
- **Pin:** the SHA-256 manifest of every jar and POM is
  [`evidence/m2-native-baseline-manifest.sha256`](evidence/m2-native-baseline-manifest.sha256) (227 files).
- **Rule:** never install or deploy into it.
- **The v3 baseline is untouched:** `~/.m2-baseline` (commit `7b248e9`).

## v3 consumer counts: the regression floor (plan §3.5)

| Run | Count | Source |
|---|---|---|
| Phase 1 Core + Errors | 92 | `production-readiness/STATE.md` |
| Phase 2 Logging + Observability | 76 | Phase 8 rerun on Boot 4.1.2 (closed at 75; 76 after a Phase 8 addition) |
| Phase 3 Data Access | 92 | same |
| Phase 4 Outbox | 106 | same |
| Phase 5 RabbitMQ | 63 | same |
| Phase 6 REST + Resilience | 182 | same |
| Phase 7 Security | 170 | same |
| C1 | 106 plus the Phase 5 consumer's AC1 and AC5 | `phase-05-rabbitmq-messaging/composition-c1.md` |
| C2 | 61 | `phase-07-security/closure.md` |
| All capabilities (v3 Phase 8) | 63 | `phase-08-final/run-results.md` |

## What the cut removes (step 5)

> **Superseded by plan v2 (2026-11-19).** This section and `evidence/depmap.py` describe the v1 cut. The v2 cut is wider
> (plan §2.5: also security, validation, events, cache, locking and scheduling). Codex's Phase 0 review lists every kept
> module's reference to a v2-cut module, by file and line ([`codex/r1-review.out.md`](codex/r1-review.out.md), question 2).
> Phase 1 Gate A regenerates this inventory from an explicit v2 module manifest and checks every dependency scope and
> Java reference.

**Modules (44):**

| Area | Modules |
|---|---|
| Audit | `audit/*` (7) |
| Idempotency | `idempotency/*` (3) |
| Redis | `redis/*` (2) |
| Rate limiting | `ratelimit/*` (6) |
| Storage | `storage/*` (7) |
| Files | `files/*` (3) |
| Feature flags | `flags/*` (6) |
| Kafka | `messaging/platform-messaging-kafka`, `messaging/platform-starter-messaging-kafka` |
| Redis variants of kept capabilities | `cache/platform-starter-cache-redis`, `locking/platform-locking-redis`, `locking/platform-starter-locking-redis` |
| TCKs | `test/tck/platform-tck-{audit,flags,idempotency,ratelimit,storage}` |

**No module exists** for secrets, multi-tenancy or schema-per-tenant. They live only as:
- docs;
- test-kit container helpers (Vault, LocalStack in `platform-test-api`);
- the archetype POM (native image).

Phase 1 confirms each and removes it.

**`scratch/` and `tmpviol/`** are not tracked by git; they hold local build leftovers (`platform-scratch-api`,
`platform-starter-beta`). Removing them is a local clean-up, not a repository change (ND2).

## Kept modules that reference cut ones (step 5)

A scan of every kept `pom.xml` for cut modules and for libraries only cut modules need: Lettuce, `spring-data-redis`,
`kafka-clients`, `spring-kafka`, OpenFeature, AWS. The script and full output are
[`evidence/depmap.txt`](evidence/depmap.txt).

| Kept module | References | Phase 1 work |
|---|---|---|
| `pom.xml` (root) | `<module>` entries for every cut module | Remove the entries |
| `build/platform-bom` | 42 cut artifacts | Remove the entries |
| `docs/platform-docs` | 8 cut auto-configure modules (generator inputs) | Remove; regenerate the reference docs |
| `messaging/platform-messaging-autoconfigure` | `platform-messaging-kafka`, `spring-kafka` (optional) | **Code change in a certified module:** remove the Kafka auto-configuration branch; run the v3 Phase 5 regression |
| `locking/platform-locking-autoconfigure` | `platform-locking-redis`, `spring-data-redis` (optional) | Remove the Redis lock branch |
| `cache/platform-cache-autoconfigure` | `spring-data-redis` (optional) | Remove the Redis cache branch |
| `examples/example-golden-path` | `platform-starter-audit` | **CI runs `StartupBudgetTest` on this example:** remove audit from it and keep the test green |
| `examples/example-extension-provider` | `platform-starter-storage-fs`, `platform-tck-storage` | It demonstrates the extension model on storage: rebase it on a kept capability, or remove it and update `docs/examples.md` |

Not yet scanned:
- `build/platform-dependencies`, for third-party versions only cut modules use (Phase 1 step 2, with a dependency tree);
- Java sources outside the POMs, for example `@ConditionalOnClass` on Kafka or Redis types;
- the archetype template and `adopt-platform.yml`.

Phase 1 Gate A does these.
