# Developer Experience (archetype · upgrade-check · conformance · golden path)

The DX tooling turns "assemble a service by hand from a dozen starters and rediscover every
convention" into a one-command generate-and-go, and keeps generated services on the platform's rails.

## Service archetype (`tooling/platform-service-archetype`)

A `maven-archetype` that generates a ready-to-run service. See [quickstart](../quickstart.md) for the
generate command. Properties:

| Property | Default | Meaning |
|---|---|---|
| `platformVersion` | current release | platform version the service builds against (parent + BOM) |
| `features` | `none` | comma-separated subset of `messaging`, `data` — adds starters + sample code |

The generated project ships `CLAUDE.md`/`AGENTS.md` (so coding agents use platform APIs instead of
vanilla Spring), a `PlatformConformanceTest`, platform slice tests, and a profile-aware
`application.yml`. Feature samples are conditional: when a feature is off, its sample source renders
empty (Velocity `#if`) and its starter is omitted.

The archetype is validated by `mvn -Parchetype-it -pl tooling/platform-service-archetype verify`,
which generates a `basic` (no features) and a `full` (messaging+data) project and runs `verify` on
each. That profile is off by default (it needs the platform installed first); the default reactor
build never runs it.

## Consumer conformance rules (`PlatformUsageRules`)

`ae.gov.dubaicustoms.platform.test.arch.PlatformUsageRules.all()` (in `platform-test-api`) is a set of
ArchUnit rules a service runs over its own production classes via the generated
`PlatformConformanceTest`. Each failure names the platform alternative:

| Rule | Banned | Use instead |
|---|---|---|
| `noDirectMessagingInfrastructure` | `KafkaTemplate` / `RabbitTemplate` / listener containers | `EventPublisher` / `@EventHandler` |
| `noHandRolledExceptionHandler` | `@RestControllerAdvice extends ResponseEntityExceptionHandler` | throw `PlatformException` subtypes |
| `noSystemGetenv` | `System.getenv(...)` | the platform secrets property source |

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

The executable DX contract and CI health check: install the platform → generate a service from the
archetype → build it (asserting `CLAUDE.md` and a run `PlatformConformanceTest`) → boot it with
`spring-boot:start` → probe `/actuator/health` and `/actuator/platform | grep messaging` → stop. It
fails past a 10-minute wall-clock SLA. It pins `maven-archetype-plugin:3.1.2` because 3.2.0+ made
`archetype:generate` fork a lifecycle that fails project-less on Maven 3.9.x.
