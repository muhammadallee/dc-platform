# Changelog

All notable changes to the DC Platform. Format: [Keep a Changelog](https://keepachangelog.com),
versioning: Semantic Versioning on the release train (all artifacts share one version).

## [Unreleased]

### Changed
- Platform baseline moved to Java 25 and Spring Boot 4.1.0 (decision D6); toolchain plugins bumped
  for Java 25 class files (jacoco, sisu, maven-plugin-tools, enforcer, japicmp, archunit, flatten).
- Platform identity renamed to `dc` / `ae.gov.dubaicustoms.platform` (decision D7): Maven groupId,
  Java root package, property prefix `dc.platform.*`, error-code namespace `DC-*`, specs and docs.

### Added
- Phase 3: `platform-starter-core` — the smallest useful chassis (POM-only starter: core-api +
  core-autoconfigure + `spring-boot-starter`); `docs/modules/core.md`; service-parent README
  usage snippet (capabilities stay explicit, ADR-007).
- Phase 3: `platform-core-autoconfigure` — `CoreContextAutoConfiguration` (correlation-id servlet
  filter at highest precedence, kill switch `dc.platform.core.enabled`) and
  `PlatformBannerAutoConfiguration` (`platform: core[ACTIVE], …` startup line collected from
  `CapabilityDescriptor` beans in `ae.gov.dubaicustoms.platform.core.report`).
- Phase 3: `platform-core-api` — `@PlatformApi`/`@PlatformInternal` markers, `PlatformException` +
  `ErrorCode` (`DC-<CAP>-<NNNN>`), `CorrelationId` + MDC-backed `RequestContext`; public API surface
  capped at 25 types by an executable test.
- Phase 1: reactor foundation — aggregator, `platform-parent`, `platform-service-parent`,
  `platform-bom`, `platform-dependencies`, optional local docker compose, repo hygiene.
- Phase 2: `platform-build-tools` — `PlatformLayerRule` enforcer rule (dependency constitution:
  layer matrix, fan-out ceilings, version-tag ban), `PlatformArchRules` ArchUnit library,
  shared checkstyle config, `ArchConstitutionTest` template.
- Phase 2: gates wired into `platform-parent` — checkstyle, `platformLayerRule` +
  `requireUpperBoundDeps`, jacoco line coverage ≥ 0.80 (halt on failure), japicmp configured
  (inert until first release).
- Phase 2: `platform-build-maven-plugin` — `new-module` scaffolding goal (registers new modules
  in the root reactor and `platform-bom`) and `check-bom` BOM-completeness goal.
- Phase 2: GitHub Actions CI — push/PR verify + check-bom, nightly `-Pdocker` job.
