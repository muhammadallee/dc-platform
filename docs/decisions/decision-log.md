# Decision Log

One line of context per decision; details live in the commit bodies referenced.

## Phase 2 — build gates

- **D1 — Enforcer API.** `PlatformLayerRule` uses the current enforcer API
  (`enforcer-api` 3.5.0: `@Named` + `AbstractEnforcerRule`) instead of the deprecated
  `EnforcerRule2` named in the spec. The rule name, behavior and messages are the contract.
- **D2 — check-bom binding.** Not bound to aggregator `verify`: a Maven plugin cannot be resolved
  from the reactor that is building it for the ROOT project on a clean machine, which would break
  ground rule 1 (first `mvn verify` after checkout must pass). It runs as an explicit invocation
  (`mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:check-bom` after an install of the plugin)
  and as a dedicated CI step. Revisit in phase 13 tooling scripts.
- **D3 — new-module verification.** The "generate into a temp reactor and verify it" integration
  test is realized as: unit tests over the generation internals + a plugin-descriptor wiring test;
  the phase Acceptance block performs the real `new-module` + root `mvn verify` end to end. A nested
  Maven invocation in tests would need a seeded local repo and network (violates local-first rule 2).
- **D4 — build modules' parent.** `platform-build-tools` and `platform-build-maven-plugin` are
  parented on the ROOT AGGREGATOR, not `platform-parent`: platform-parent's checkstyle/enforcer
  depend on build-tools, so inheriting them there would be a resolution cycle. Build category is
  exempt from the constitution; both modules wire their own compiler/jacoco basics.
- **D5 — japicmp activation.** Fully configured in platform-parent but gated behind
  `platform.japicmp.skip=true` until the first release tag exists (phase 4 flips it after `0.1.0`);
  `platform.japicmp.breakBuild=true` is set only by `-api`/`-spi` modules (scaffolder emits it).
- **Starter packaging.** Generated starters use `jar` packaging (Boot convention, empty jar):
  "POM only" refers to content, and pom-packaging would hide starters from check-bom's
  parent/BOM exclusion.
- **Spring-free build-tools.** `PlatformArchRules` matches Spring annotations by fully-qualified
  NAME, so build-tools has no Spring dependency and the rules run in modules where Spring is absent.

## Phase 3 — core

- **D8 — module builds include the third-party BOM.** Maven 3.9's `-am` does not pull
  import-scoped POMs into the reactor subset, so the per-module loop is
  `mvn -T1C -pl build/platform-dependencies,<module> -am verify` (or run the root build) until
  `platform-dependencies` is in the local repository. Root `mvn -T1C verify` is unaffected.
- **D9 — no customizer SPI in core.** The matrix's "customizers apply in order" case has no
  subject (the spec defines no core customizer); it is covered by the ordering-behavior tests
  instead (filter registered at `Ordered.HIGHEST_PRECEDENCE`, banner output sorted by name).
  A customizer interface would have invented public API the spec does not define.
- **D10 — starter does not depend on core-api directly.** Phase-03 wording lists
  "core-autoconfigure + core-api" in the starter, but the constitution (CLAUDE.md rule 5,
  enforced by `platformLayerRule`) allows only `starter -> autoconfigure + named impl(s)`.
  The constitution wins; applications still get core-api transitively via the autoconfigure
  module's compile dependency.

## Phase 4 — errors, logging, validation

- **D11 — api standard-model whitelist lives in the ArchUnit rule.** The spec's "Enforcer:
  whitelist spring-web for errors-api only" targets `PlatformLayerRule`, but that rule polices
  platform-group dependencies only; the gate that actually rejects third-party types in api
  packages is `PlatformArchRules.apiPackagesDependOnlyOnJdkSpringAnnotationsAndCore`. The
  whitelist is therefore a per-capability map in that rule (errors -> `org.springframework.http`,
  validation -> `jakarta.validation`), proven by pass/fail fixtures so it cannot silently widen.
- **D12 — CapabilityDescriptor moved to core-api.** The canonical autoconfigure pattern requires
  every capability to register a `CapabilityDescriptor` bean, but the constitution forbids
  `<cap>-autoconfigure -> core-autoconfigure` (other capabilities are reachable only via `-api`).
  Phase-3 placement made the type unreachable for every future capability; moved (same package
  `…core.report`) before `0.1.0` exists, while the move is still free of compatibility cost.

## Phase 5 — observability, openapi

- **D13 — one EnvironmentPostProcessor for the management defaults.** The spec's
  `HealthGroupsAutoConfiguration` defines no beans (it only contributes environment defaults),
  so health groups, actuator exposure, baggage and the OTLP-off posture all ship in
  `PlatformObservabilityEnvironmentPostProcessor` (`platform-observability-defaults` source),
  matching the spec's own "same EnvPostProcessor" wording. Details: commit 6a30f71.
- **D14 — no ObservationConvention beans; correlation via baggage only.** The spec reverses
  itself on tagging observations with the correlation id ("low-cardinality? NO"); correlationId
  is high-cardinality, so it propagates exclusively through tracing baggage
  (`management.tracing.baggage.remote-fields=X-Correlation-Id`); the mandatory cardinality
  comment lives on the EnvironmentPostProcessor. Details: commit 6a30f71.
- **D15 — readiness "when present" via membership validation off.** Readiness includes optional
  `db,rabbit,redis` members with `management.endpoint.health.validate-group-membership=false`
  (the group shows the intersection with EXISTING contributors) instead of classpath sniffing;
  `probes.enabled=true` makes the state contributors exist off Kubernetes. Details: commit 6a30f71.
- **D16 — Boot 4.1 EnvironmentPostProcessor API.** New platform EPPs implement
  `org.springframework.boot.EnvironmentPostProcessor` under the new spring.factories key; the
  `org.springframework.boot.env` variant is deprecated for removal (logging's phase-4 EPP still
  uses it — migrate when phase 4 is next touched). Details: commit 6a30f71.

## Pre-phase-3 baseline amendments

- **D6 — Java 25 / Spring Boot 4.x baseline.** Deliberate deviation from the spec pack's
  "Java 21, Spring Boot latest stable 3.x": the platform moves to Java 25 and Boot 4.1.0 BEFORE any
  capability code exists, when the cost is purely build plumbing. Toolchain bumps required for
  Java 25 class files (major 69): jacoco 0.8.15, sisu-maven-plugin 1.0.1, maven-plugin-tools 3.15.2,
  maven-enforcer-plugin/enforcer-api 3.6.3, japicmp 0.26.1, archunit 1.4.2, flatten 1.7.3.
  Boot 4 note: the modularized starters publish NEW canonical names (e.g.
  `spring-boot-starter-webmvc`) while the classic names the specs reference
  (`spring-boot-starter-web`, `spring-boot-autoconfigure`, `spring-boot-starter-test`) are still
  published at 4.1.0, so spec references remain valid; prefer the new names in new code where the
  spec does not pin one.
- **D7 — platform identity.** Placeholder replacement per the handoff runbook's one-time setup:
  base identity renamed from the spec pack's placeholder (short name and Maven groupId/Java root
  package) to `dc` / `ae.gov.dubaicustoms.platform`. Follows through everywhere the identity is
  load-bearing: Maven coordinates (aggregator artifactId now `dc-platform`), Java packages incl.
  build-tools test fixtures, `PlatformLayerRule`/`PlatformArchRules` constants and regexes, the
  scaffolder templates, property prefix (`dc.platform.<cap>`), error-code namespace
  (`DC-<CAP>-<NNNN>`), specs, runbooks, docs, and CI. The specs are the implementation authority
  for later phases, so they were swept too rather than left historical.
