# CLAUDE.md — dc-platform (place at repo root)

You are implementing an internal Spring Boot platform ("chassis"). The architecture is fixed
(see specs/00-MASTER-PLAN.md and the phase specs). Your job is faithful, incremental execution.

## Ground rules

1. **Never break the reactor.** `mvn -T1C verify` from the root must pass after every commit.
2. **Local first.** No test may require Docker, network (beyond Maven Central), or credentials
   unless tagged `@Tag("docker")` and guarded by `assumeTrue(DockerAvailable.check())`.
3. **Signatures are contracts.** Types/methods given in a phase spec must match exactly
   (names, params, return types, javadoc `@since`). Internals and private helpers are your choice.
4. **One module category per module** (Parent | BOM | API | SPI | Core | Implementation |
   Starter | Auto Configuration | Test Support | Documentation | Build Plugin | Examples).
   Starters contain no code — POM only.
5. **Dependency constitution** (enforced by build; do not fight the enforcer):
   - api → core-api only. spi → same-cap api. impl → same-cap spi/api + its 3rd-party lib.
   - autoconfigure → same-cap api/spi (+ optional impls, + other caps' **api** guarded by
     `@ConditionalOnClass`). starter → autoconfigure + named impl(s).
   - Forbidden: api→anything downward, impl→impl, starter→starter, anything→`*.internal` of
     another module, cycles, 3rd-party types in api/spi signatures.
6. **Every platform bean**: `@ConditionalOnMissingBean`, kill switch
   `dc.platform.<cap>.enabled` (`matchIfMissing = true`), constructor injection, no field injection,
   no `@Autowired` on constructors (implicit), no `@ComponentScan` of platform packages.
7. **Packages:** `ae.gov.dubaicustoms.platform.<cap>` (API), `.spi`, `.config`, `.autoconfigure`,
   `.internal`, provider packages `.<provider>` with their own `.internal`.
8. **Properties:** immutable records, prefix `dc.platform.<cap>`, defaults in code + metadata.
   Renames require deprecation metadata. See specs/reference/property-conventions.md.
9. **Javadoc/comments:** follow specs/reference/coding-standards.md §2 exactly. Public API and SPI
   types: full javadoc with purpose, usage snippet, thread-safety, nullability, `@since 0.1.0`.
   Autoconfigure classes: leading comment block listing activation conditions and back-off behavior.
   No noise comments (`// getter`), no commented-out code.
10. **Tests:** each autoconfigure module ships the 5-case ContextRunner matrix
    (see specs/reference/autoconfigure-pattern.md). Impl modules test against local providers
    (H2, in-memory, tmp dir); real infra tests are `@Tag("docker")`.
11. **Docs travel with code:** each capability PR updates `docs/modules/<cap>.md`.
12. **Commits:** conventional commits (`feat(scope): …`, `build:`, `test:`, `docs:`).
    One logical change per commit. Update `CHANGELOG.md` under `## [Unreleased]`.

## Working loop (per module)

scaffold (use platform-build-maven-plugin:new-module once it exists) → write api/spi with javadoc
→ write autoconfigure from the canonical template → write starter POM → write test matrix →
write/extend docs page → `mvn -T1C -pl <module> -am verify` → root `verify` → commit.

## Commands

- Full build: `mvn -T1C verify`
- One module + deps: `mvn -T1C -pl messaging/platform-messaging-autoconfigure -am verify`
- Docker-tagged tests: `mvn -Pdocker verify` (requires Docker running; `docker compose -f docker-compose.local.yml up -d` for manual runs)
- Golden path check (after phase 13): `./tooling/scripts/golden-path.sh`

## When the spec is silent

Do what `spring-boot-autoconfigure` / `spring-boot-starter-*` does for the analogous problem.
If two reasonable options exist, choose the simpler one, note it in the commit body as
`Decision: …`, and add a line to docs/decisions/decision-log.md.
