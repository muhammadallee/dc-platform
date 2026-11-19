# Quickstart — a new platform service in under 10 minutes

The DC Platform ships a Maven archetype that generates a ready-to-run service wired with the
golden-path chassis (W3C trace correlation, JSON logging, RFC-9457 errors, Bean Validation and
metrics/tracing). You add business logic; the platform does the cross-cutting work.

> **Not secured yet.** Web security (basic auth behind the API gateway) and OpenAPI documentation come with
> the REST API capability in the next release. Until then, keep generated services inside the cluster.

## 1. Generate the service

Prerequisites: JDK 25, Maven 3.9+, and the platform artifacts (archetype, `platform-service-parent`,
BOM, starters) in a repository your Maven can reach — the organization's artifact repository, or your
local repository after `mvn install -DskipTests` in the platform checkout. Run it from any directory
outside a Maven project. A logging + data service:

```bash
mvn org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -B \
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform \
  -DarchetypeArtifactId=platform-service-archetype \
  -DarchetypeVersion=<platform-version> \
  -DgroupId=com.acme.orders -DartifactId=orders-service -Dpackage=com.acme.orders \
  -Dfeatures=data
```

- **`features`** (optional, default `none`): `none`, or a comma-separated subset of `messaging` and
  `data` — no spaces, no duplicates, any order. Each adds the matching starter and a sample with its
  tests: `messaging/OrderEvents`, `data/Note` + `NoteRepository` + the Flyway migration
  `db/migration/V1__create_note.sql`. Anything else (`kafka`, `restclient`, `data, messaging`,
  `none,data`) fails generation with `unsupported features value …`.
  Structured JSON logging is always on — it is not a feature toggle.
- **`platformVersion`** (optional): the platform release to build against (parent + BOM version).
  Defaults to the archetype's own version, which is the right choice unless you deliberately target
  another train.

> `maven-archetype-plugin` 3.1.2 is the documented, gate-tested version; 3.4.0 also works. On Windows
> PowerShell, quote every `-D` argument (`"-DgroupId=com.acme.orders"`): PowerShell splits unquoted
> arguments at the first `.`, which Maven then reports as "The goal you specified requires a project".

## 2. What you get

```
orders-service/
  pom.xml                     parent=platform-service-parent; golden-path starters (+ feature starters)
  CLAUDE.md / AGENTS.md       how coding agents should use platform APIs (read at session start)
  README.md                   run / test / add-a-capability
  src/main/java/.../Application.java
  src/main/java/.../hello/HelloController.java   GET /hello
  src/main/resources/application.yml             offline dev defaults (every profile but prod); local/test/prod
  src/main/java/.../data/, messaging/            feature samples (empty files when the feature is off)
  src/test/java/.../HelloControllerTest.java     @PlatformWebTest
  src/test/java/.../ApplicationSmokeTest.java    @PlatformTest (boots the full context)
  src/test/java/.../StructuredLoggingTest.java   parses the emitted JSON log events: fields, traceId, no tokens
  src/test/java/.../data/NoteRepositoryTest.java Flyway-created schema, CRUD across transactions, rollback, constraint
  src/test/java/.../PlatformConformanceTest.java runs PlatformUsageRules over your code
```

## 3. Build, test, run

```bash
cd orders-service
mvn verify                 # all of the above; no Docker, database, or broker required
mvn spring-boot:run        # boots on :8080 with no profile (JSON logs); -Dspring-boot.run.profiles=local for console logs
java -jar target/orders-service-1.0-SNAPSHOT.jar

curl http://localhost:8080/actuator/health/readiness   # open
curl http://localhost:8080/actuator/platform           # capability report (open outside prod)
curl "http://localhost:8080/hello?name=alice"
```

In production run with `--spring.profiles.active=prod` and supply `spring.datasource.*` (data)
externally: `prod` refuses to start without it rather than falling back to an embedded H2 database.

## 4. Add a capability

Nothing is auto-injected (ADR: explicit dependencies). Add the starter, use the platform API:

| Capability | Starter | Use |
|---|---|---|
| Messaging | `platform-starter-messaging-inmemory` / `-rabbit` | `EventPublisher` / `@EventHandler` |
| Transactional outbox | `platform-starter-messaging-outbox` | `OutboxPublisher` in a `@Transactional` method |
| Persistence | `platform-starter-data-jpa` (`-oracle` for Oracle) | Spring Data + platform JPA conventions |

Full catalog: [`docs/modules/`](modules/). Read your service's `CLAUDE.md` before adding cross-cutting
code — the `PlatformConformanceTest` fails the build if you hand-roll what the platform already owns.

## 5. Keep it healthy

The whole path — generate, build, boot, probe — is exercised for every feature combination by
[`tooling/scripts/golden-path.sh`](https://github.com/muhammadallee/dc-platform/blob/main/tooling/scripts/golden-path.sh), the CI `generator-gate` job.
See [`docs/modules/dx.md`](modules/dx.md) for the archetype, `upgrade-check`, and conformance rules.
