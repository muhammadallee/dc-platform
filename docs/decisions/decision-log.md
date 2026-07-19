# Decision Log

One line of context per decision; details live in the commit bodies referenced.

## Phase 2 — build gates

- **D1 — Enforcer API.** `PlatformLayerRule` uses the current enforcer API
  (`enforcer-api` 3.5.0: `@Named` + `AbstractEnforcerRule`) instead of the deprecated
  `EnforcerRule2` named in the spec. The rule name, behavior and messages are the contract.
- **D2 — check-bom binding.** Not bound to aggregator `verify`: a Maven plugin cannot be resolved
  from the reactor that is building it for the ROOT project on a clean machine, which would break
  ground rule 1 (first `mvn verify` after checkout must pass). It runs as an explicit invocation
  (`mvn com.acme.platform:platform-build-maven-plugin:check-bom` after an install of the plugin)
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
