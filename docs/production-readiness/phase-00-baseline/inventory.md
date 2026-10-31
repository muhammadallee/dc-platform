# Phase 0 — repository baseline and capability inventory

## Baseline

| Item | Value |
|---|---|
| Baseline commit | `7b248e919a7ffed9b2ae36379c8e9a84105f0b75` (main, merge of PR #1 `fix/chassis-generated-service-audit`) |
| Working branch | `hardening/v1` off the baseline commit |
| Version | `${revision}` = `1.0.0-SNAPSHOT` (single train, ADR-005); last tag `1.0.0-RC1` |
| Spring Boot | 4.1.0 (root `spring-boot.version`) |
| Java | 25 (`maven.compiler.release`, enforcer `[25,)`) |
| Modules | 119 POMs including root and aggregators |

Pre-baseline fixes that had to land first (PR #1, CI red before them):
- `852ac15` — pin compiler/surefire/failsafe/resources plugins (failsafe 3.6.0 ignored `-DskipTests`; JaCoCo failed at 0%).
- `ba8790f` — `StartupBudgetTest` measured in an isolated serial CI step (D88).

### Build structure

- Root `pom.xml` (`dc-platform`) — aggregator, `revision`, plugin versions, flatten plugin.
- `build/platform-parent` — parent of every platform module: compiler `release 25` + `proc full`,
  surefire/failsafe (`excludedGroups=${platform.test.excludedGroups}`, `useManifestOnlyJar=false`),
  JaCoCo (BUNDLE LINE ≥ 0.80, excludes `*AutoConfiguration*`, `*Properties`), enforcer
  (Java 25, Maven 3.9, no duplicate deps, dependency convergence, upper-bound deps, `PlatformLayerRule`),
  checkstyle, japicmp.
- `build/platform-dependencies` — imports `spring-boot-dependencies`, pins only what Boot does not manage.
- `build/platform-bom` — consumer BOM of platform artifacts.
- `build/platform-service-parent` — consumer parent (`spring-boot-starter-parent`).
- `build/platform-build-tools` (enforcer rule, ArchUnit rules), `build/platform-build-maven-plugin`
  (`new-module`, `check-bom`, upgrade report).

### Gates

| Gate | Configuration |
|---|---|
| Enforcer | `requireJavaVersion [25,)`, `requireMavenVersion [3.9,)`, `banDuplicatePomDependencyVersions`, `dependencyConvergence`, `requireUpperBoundDeps`, `PlatformLayerRule` (dependency constitution) |
| ArchUnit | `ArchConstitutionTest` per module (rules in `platform-build-tools`, `PlatformArchRulesTest`) |
| japicmp | `oldVersion` = `RELEASE` of the same artifact, `onlyModified`, excludes `*.internal.*`, `ignoreMissingOldVersion=true`, `skip=${platform.japicmp.skip}`, `breakBuild=${platform.japicmp.breakBuild}` (api/spi opt in). No release in the local/central repo, so japicmp has nothing to compare today; Phase 8 compares against `~/.m2-baseline` (HC4). |
| Checkstyle | per module, 0 violations at baseline |
| JaCoCo | BUNDLE LINE COVEREDRATIO ≥ 0.80, `haltOnFailure` |
| Test tags | `docker` excluded by default; `-Pdocker` clears it |

### Archetype

- Module `tooling/platform-service-archetype`, coordinates
  `ae.gov.dubaicustoms.platform:platform-service-archetype:1.0.0-SNAPSHOT`.
- Generate with `maven-archetype-plugin:3.1.2` (D66), `-DarchetypeCatalog=local`, features token list
  `messaging,data,restclient` (exact tokens; unknown tokens fail generation).
- `tooling/scripts/golden-path.sh` installs the platform into an isolated repository and generates,
  builds, boots and probes all 8 feature combinations (CI job `generator-gate`).

## In-scope capability inventory

| Capability | Modules (api / spi / impl / autoconfigure / starter) | `@AutoConfiguration` | Properties prefix (kill switch) | Test files | Docs |
|---|---|---|---|---|---|
| Core | `platform-core-api` / — / — / `platform-core-autoconfigure` / `platform-starter-core` | `CoreContextAutoConfiguration`, `PlatformBannerAutoConfiguration` | `dc.platform.core` (`enabled`) | 5 | `docs/modules/core.md` |
| Errors | `platform-errors-api` / — / — / `platform-errors-autoconfigure` / `platform-starter-errors` | `PlatformErrorHandlingAutoConfiguration` | `dc.platform.errors` (`enabled`) | 5 | `errors.md` |
| Logging | `platform-logging-api` / — / — / `platform-logging-autoconfigure` / `platform-starter-logging` | `LoggingAutoConfiguration` (+ `PlatformLoggingEnvironmentPostProcessor`) | `dc.platform.logging` (`enabled`) | 4 | `logging.md` |
| Observability | — / — / — / `platform-observability-autoconfigure` / `platform-starter-observability` | `CommonTagsAutoConfiguration`, `PlatformInfoEndpointAutoConfiguration`, `CapabilityMetricsAutoConfiguration` (+ env post-processor) | `dc.platform.observability` (`enabled`, `health.groups.enabled`, `otlp.enabled`) | 6 | `observability.md` |
| Data access | `platform-data-api` / — / — / `platform-data-jpa-autoconfigure` / `platform-starter-data-jpa` | `PlatformDataJpaAutoConfiguration` (+ env post-processor) | `dc.platform.data.jpa` (`enabled`, `require-migrations`) | 11 (1 `docker`: `PostgresParityIT`) | `data.md` |
| Outbox | **none** — `platform-events-*` has only an after-commit relay (D32) | `PlatformEventsAutoConfiguration` | `dc.platform.events` (`enabled`, `relay.*`) | 9 | `events.md` |
| Messaging (RabbitMQ) | `platform-messaging-api` / `platform-messaging-spi` / `platform-messaging-rabbit` / `platform-messaging-autoconfigure` / `platform-starter-messaging-rabbit` (+ `platform-messaging-test`, `platform-tck-messaging`) | `PlatformMessagingAutoConfiguration` | `dc.platform.messaging` (`enabled`, `handler.retry.*`, `dlq.suffix`, `correlation.propagate`, `rabbit.quorum-queues`) | 8 + 4 (1 `docker`) | `messaging.md` |
| REST client | `platform-restclient-api` / — / — / `platform-restclient-autoconfigure` / `platform-starter-restclient` | `PlatformRestClientAutoConfiguration` | `dc.platform.restclient` (`enabled`, `propagate-correlation`, `defaults.*`, `clients.*`) | 4 | `restclient.md` |
| Resilience | `platform-resilience-api` / — / — / `platform-resilience-autoconfigure` / `platform-starter-resilience` | `PlatformResilienceAutoConfiguration` (+ env post-processor) | `dc.platform.resilience` (`enabled`) | 5 | `resilience.md` |
| Security | `platform-security-api`, `platform-security-authz-api` / `platform-security-authz-spi` / — / `platform-security-autoconfigure`, `platform-security-authz-autoconfigure` / `platform-starter-security`, `platform-starter-security-authz` | `PlatformSecurityAutoConfiguration`, `PlatformAuthzAutoConfiguration` | `dc.platform.security` (`enabled`, `mode`, `permit-paths`), `dc.platform.authz` (`enabled`) | 4 + 4 | `security.md`, `authz.md` |

Kafka (`platform-messaging-kafka`, `platform-starter-messaging-kafka`) is retained and out of scope;
it must keep compiling and passing its unit tests (plan §3.9).

Known defects at baseline: see [`gap-inventory.md`](gap-inventory.md).

## Downstream map (plan §3.9)

Computed from the POM dependency graph (direct `ae.gov.dubaicustoms.platform` dependencies,
reverse-transitive closure). After changing any module, run
`mvn -q -pl <changed> -amd -DskipTests test-compile`, which includes the out-of-scope dependents below.

| Module | Direct dependents | Transitive | Out-of-scope dependents (must still compile, plan 3.9) |
|---|---|---|---|
| `platform-core-api` | platform-audit-autoconfigure, platform-cache-api, platform-cache-autoconfigure, platform-core-autoconfigure, platform-data-api, platform-data-jpa-autoconfigure ... (+27) | 85 | platform-audit-autoconfigure, platform-audit-messaging-autoconfigure, platform-cache-api, platform-cache-autoconfigure, platform-files-autoconfigure, platform-flags-autoconfigure, platform-idempotency-autoconfigure, platform-locking-api, platform-locking-autoconfigure, platform-locking-jdbc, platform-locking-redis, platform-locking-spi, platform-messaging-kafka, platform-openapi-autoconfigure, platform-ratelimit-autoconfigure, platform-redis-autoconfigure, platform-scheduling-autoconfigure, platform-starter-audit, platform-starter-cache-caffeine, platform-starter-cache-redis, platform-starter-files, platform-starter-flags, platform-starter-idempotency, platform-starter-locking-jdbc, platform-starter-locking-redis, platform-starter-messaging-kafka, platform-starter-openapi, platform-starter-ratelimit, platform-starter-redis, platform-starter-scheduling, platform-starter-storage-fs, platform-starter-storage-s3, platform-starter-validation, platform-storage-api, platform-storage-autoconfigure, platform-storage-fs, platform-storage-s3, platform-storage-spi, platform-tck-locking, platform-tck-messaging, platform-tck-storage, platform-validation-autoconfigure |
| `platform-core-autoconfigure` | platform-docs, platform-starter-core | 7 | - |
| `platform-errors-api` | platform-errors-autoconfigure, platform-idempotency-autoconfigure | 9 | platform-idempotency-autoconfigure, platform-starter-idempotency |
| `platform-errors-autoconfigure` | platform-docs, platform-starter-errors | 6 | - |
| `platform-logging-api` | platform-logging-autoconfigure | 7 | - |
| `platform-logging-autoconfigure` | platform-docs, platform-starter-logging | 6 | - |
| `platform-observability-autoconfigure` | platform-docs, platform-starter-observability | 5 | - |
| `platform-data-api` | platform-data-jpa-autoconfigure | 8 | - |
| `platform-data-jpa-autoconfigure` | platform-docs, platform-starter-data-jpa, platform-test-api | 7 | - |
| `platform-events-api` | platform-events-autoconfigure | 3 | - |
| `platform-events-autoconfigure` | platform-docs, platform-starter-events | 2 | - |
| `platform-messaging-api` | platform-audit-messaging-autoconfigure, platform-events-autoconfigure, platform-messaging-autoconfigure, platform-messaging-inmemory, platform-messaging-kafka, platform-messaging-rabbit ... (+2) | 19 | platform-audit-messaging-autoconfigure, platform-messaging-kafka, platform-starter-messaging-kafka, platform-tck-messaging |
| `platform-messaging-spi` | platform-messaging-autoconfigure, platform-messaging-inmemory, platform-messaging-kafka, platform-messaging-rabbit, platform-messaging-test, platform-tck-messaging | 17 | platform-messaging-kafka, platform-starter-messaging-kafka, platform-tck-messaging |
| `platform-messaging-autoconfigure` | platform-docs, platform-starter-messaging-inmemory, platform-starter-messaging-kafka, platform-starter-messaging-rabbit, platform-test-api | 9 | platform-starter-messaging-kafka |
| `platform-messaging-rabbit` | platform-messaging-autoconfigure, platform-starter-messaging-rabbit | 10 | platform-starter-messaging-kafka |
| `platform-restclient-api` | platform-restclient-autoconfigure | 3 | - |
| `platform-restclient-autoconfigure` | platform-docs, platform-starter-restclient | 2 | - |
| `platform-resilience-api` | platform-resilience-autoconfigure | 4 | - |
| `platform-resilience-autoconfigure` | platform-docs, platform-starter-resilience | 3 | - |
| `platform-security-api` | platform-audit-autoconfigure, platform-data-jpa-autoconfigure, platform-flags-autoconfigure, platform-restclient-autoconfigure, platform-security-authz-autoconfigure, platform-security-authz-spi ... (+1) | 19 | platform-audit-autoconfigure, platform-flags-autoconfigure, platform-starter-audit, platform-starter-flags |
| `platform-security-autoconfigure` | platform-docs, platform-restclient-autoconfigure, platform-security-authz-autoconfigure, platform-starter-security | 7 | - |
| `platform-security-authz-api` | platform-security-authz-autoconfigure, platform-security-authz-spi | 4 | - |
| `platform-security-authz-spi` | platform-security-authz-autoconfigure | 3 | - |
| `platform-security-authz-autoconfigure` | platform-docs, platform-starter-security-authz | 2 | - |

Regenerate: `python -I docs/production-readiness/phase-00-baseline/downstream_map.py .`
