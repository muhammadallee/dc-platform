# Phase 2 — Build Gates as Code (P0, size M, no Docker)

**Goal:** the dependency constitution and quality gates become executable and merge-blocking.
**Modules:** `build/platform-build-tools`, `build/platform-build-maven-plugin`.

## 1. `platform-build-tools` (Build Plugin category; plain jar)
Contents:
- `src/main/resources/checkstyle/platform-checkstyle.xml` — minimal, non-annoying: unused imports,
  import order, no star imports, no `System.out`, javadoc REQUIRED on public types in packages not
  matching `.*\.internal.*` (SeverityMatchFilter), line length 140.
- `src/main/resources/enforcer/` — nothing; custom enforcer rule is a class:
- `ae.gov.dubaicustoms.platform.build.enforcer.PlatformLayerRule` implements `EnforcerRule2`:
  reads the current module's artifactId + declared platform deps and enforces:
  * category inferred from artifactId suffix: `-api`, `-spi`, `-autoconfigure`, `starter-` prefix,
    `-test`/`tck-` = Test Support, `build/` = build, `example-` = examples, else Implementation.
  * allowed platform-dep matrix from CLAUDE.md rule 5; forbidden edges fail with a message that
    names the violated rule and the fix (e.g., "starter → starter is forbidden: move the shared
    dependency into the autoconfigure module or the consumer's POM").
  * fan-out ceilings: api≤1, spi≤2, impl≤3, autoconfigure≤6, starter≤4 (platform deps only).
  * Unit-test the rule with fake MavenProject fixtures (happy + each violation).
- `ae.gov.dubaicustoms.platform.build.arch.PlatformArchRules` — an ArchUnit library (not tests) exposing
  `public static ArchRule[] all()`:
  * no classes outside `..internal..` depend on classes in another module's `..internal..`
  * api packages (`ae.gov.dubaicustoms.platform.(cap)` root + `.annotation`) depend only on jdk, spring core
    annotations, and `ae.gov.dubaicustoms.platform.core`
  * no field injection (`noFields().should().beAnnotatatedWith(Autowired.class)`)
  * no cycles within `ae.gov.dubaicustoms.platform.(cap)..` slices
  * autoconfigure classes are annotated `@AutoConfiguration` and reside in `..autoconfigure..`
  * `@ConfigurationProperties` types are records
- `resources/arch/ArchConstitutionTest.java.template` — a 15-line test class each module copies
  (or gets from the scaffolder) that runs `PlatformArchRules.all()` over its own classes.

Wire-up in `platform-parent` (edit it this phase):
- checkstyle plugin using the config from build-tools (dependency on build-tools in plugin deps), fail on violation;
- enforcer plugin gains `PlatformLayerRule` (custom rule dependency) + `banCircularDependencies` +
  `requireUpperBoundDeps` + a `banVersions` rule: no `<version>` outside build/ (implement as
  `RequireProperty`-style custom check inside PlatformLayerRule for simplicity);
- jacoco check: line coverage ≥ 0.80 excluding `*.internal.*`? NO — include internal, exclude only
  generated + `*AutoConfiguration` config-props records; ratio 0.80, per-module, `haltOnFailure=true`;
- japicmp: `postAnalysisScript` none; `oldVersion` = latest release from repo metadata,
  `skip` if no prior release exists; `onlyModified=true`; exclude `*.internal.*`;
  `breakBuildOnBinaryIncompatibleModifications=true` for `-api`/`-spi` artifacts only.

## 2. `platform-build-maven-plugin` (maven-plugin packaging)
Goals now (upgrade-check comes in phase 13):
- `new-module` — params: `capability`, `kind` (api|spi|impl|autoconfigure|starter|test), `provider`
  (for impl/starter). Generates directory, POM (correct parent/deps for its kind), package skeleton,
  `package-info.java` with javadoc header, ArchConstitutionTest from template, and for autoconfigure:
  the canonical template files from reference/autoconfigure-pattern.md with TODO markers; appends the
  module to root `<modules>` and to `platform-bom`. Integration-test with maven-plugin-testing-harness:
  generate into a temp reactor and `verify` it.
- `check-bom` — fails if any reactor artifact (non-example) is missing from platform-bom. Bind to
  aggregator verify.

## 3. CI (GitHub Actions or equivalent, local-runnable via `act` optional)
`ci.yml`: job1 build+verify (no docker, cache ~/.m2 keyed on poms); job2 nightly cron: `-Pdocker verify`
with services via docker; PR path-filter incremental build is a stretch goal — leave TODO comment.

## Acceptance
```bash
mvn -T1C verify                                    # green, gates active
git checkout -b tmp/violation                      # seed a starter→starter dep in a scratch module
mvn -T1C verify                                    # MUST fail with PlatformLayerRule message
git checkout - && git branch -D tmp/violation
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:new-module -Dcapability=scratch -Dkind=api
mvn -T1C verify                                    # generated module compiles & passes gates
git clean -fd && git checkout .                    # remove scratch
```
DoD: rule unit tests cover every forbidden edge; enforcer messages name rule + fix; CHANGELOG updated.
