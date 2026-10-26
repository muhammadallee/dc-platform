# Developer Experience (archetype · upgrade-check · conformance · golden path)

The DX tooling turns "assemble a service by hand from a dozen starters and rediscover every
convention" into a one-command generate-and-go, and keeps generated services on the platform's rails.

## Service archetype (`tooling/platform-service-archetype`)

A `maven-archetype` that generates a ready-to-run service. See [quickstart](../quickstart.md) for the
generate command. Properties:

| Property | Default | Meaning |
|---|---|---|
| `platformVersion` | the archetype's own version (stamped at build time by resource filtering) | platform version the service builds against (parent + BOM) |
| `features` | `none` | `none` or a comma-separated subset of `messaging`, `data`, `restclient` (no spaces or duplicates) — adds starters, sample code, and sample tests |

Feature tokens are matched exactly; any other value fails generation with `unsupported features value`
(batch-mode `archetype:generate` ignores `<validationRegex>`, so the check lives in the `pom.xml`
template). Structured logging is part of every service, not a feature.

The generated project ships `CLAUDE.md`/`AGENTS.md` (so coding agents use platform APIs instead of
vanilla Spring), a `PlatformConformanceTest`, platform slice tests, real-HTTP tests
(`SecurityIntegrationTest` with a loopback JWKS issuer, `StructuredLoggingTest` parsing the emitted JSON
events), per-feature tests (`data/NoteRepositoryTest`, `client/GreetingClientTest`), and a
profile-aware `application.yml`: offline placeholders apply to every profile except `prod`, and `prod`
fails fast when the IdP, datasource, or downstream base URL is missing. Feature samples are
conditional: when a feature is off, its sample sources render empty (Velocity `#if`) and its starter
is omitted.

The archetype is also checked Maven-natively by `mvn -Parchetype-it -pl tooling/platform-service-archetype
verify`, which generates `basic` (every optional property omitted), `service` (`data,restclient`), and
`full` (all features, relocated package, hyphenated artifactId) and runs `verify` on each. That profile
is off by default (it needs the platform installed first); the default reactor build never runs it.

## Consumer conformance rules (`PlatformUsageRules`)

`ae.gov.dubaicustoms.platform.test.arch.PlatformUsageRules.all()` (in `platform-test-api`) is a set of
ArchUnit rules a service runs over its own production classes via the generated
`PlatformConformanceTest`. Each failure names the platform alternative:

| Rule | Banned | Use instead |
|---|---|---|
| `noDirectMessagingInfrastructure` | `KafkaTemplate` / `RabbitTemplate` / listener containers | `EventPublisher` / `@EventHandler` |
| `noHandRolledExceptionHandler` | `@RestControllerAdvice extends ResponseEntityExceptionHandler` | throw `PlatformException` subtypes |
| `noSystemGetenv` | `System.getenv(...)` | Spring config placeholders (`${...}`, populated by Spring Cloud Vault) |

Rules match banned types by fully-qualified name, so a service that pulls neither Kafka nor Rabbit
still compiles the test.

**Escape hatch (discouraged):** a team with a documented reason may delete `PlatformConformanceTest`
from its service. The rules teach the platform way — deleting them silences that guidance.

## `upgrade-check` goal

Reports what changes when a service moves to a new platform version, without touching sources:

```bash
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check -Dplatform.target=<version>
```

It resolves the target `platform-bom` and diffs its managed versions against the project's current
ones, and scans the project's `application*.{yml,yaml,properties}` for keys deprecated in the target
version (read from the target platform jars' `spring-configuration-metadata.json`). Output: a console
summary plus `target/platform-upgrade-report.md` linking the train release notes. v1 scope is version
diff + property deprecation scan; binary-compatibility (japicmp) checks are future work.

## Golden path (`tooling/scripts/golden-path.sh`)

The executable DX contract, run by the CI `generator-gate` job:

1. install the platform (tests skipped; the `build` job is the tested gate) into an **isolated** Maven
   repository (`GP_MAVEN_REPO`, default a fresh one) and record the installed archetype;
2. check that invalid `features` values are rejected;
3. for all 8 combinations of `messaging`/`data`/`restclient` — one with every optional property
   omitted, one relocated into a directory with a space — generate **outside the checkout**, assert the
   generated files, run the generated `mvn verify` (every expected test class must run, none skipped),
   check the repackaged jar, boot it with `java -jar` on a free port and probe readiness, the capability
   report (present *and* absent capabilities), 401 for missing/untrusted/expired/malformed tokens, 200
   for a token from the loopback JWKS issuer, correlation echo, and the JSON log contract;
4. for `data,restclient`: `mvn spring-boot:run`, the `local` profile, `prod` with each mandatory
   setting missing (must refuse to start), and `prod` with supplied settings booted twice on one file
   database.

Each service must finish generate → verify → packaged boot within a 10-minute SLA (`GP_SLA_SECONDS`).
Owned processes are killed on exit; the work directory and evidence (`GP_EVIDENCE`) are kept on failure.
It uses `maven-archetype-plugin:3.1.2` (the documented version; 3.4.0 generates project-less on Maven
3.9.x too). The archetype must not use `archetype-post-generate.groovy`: 3.4.0's Groovy cannot parse
Java 25 class files, so feature pruning stays in Velocity.
