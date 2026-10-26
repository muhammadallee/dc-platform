# Quickstart — a new platform service in under 10 minutes

The DC Platform ships a Maven archetype that generates a ready-to-run service wired with the
golden-path chassis (correlation IDs, JSON logging, RFC-9457 errors, validation, metrics/tracing,
OpenAPI, and an authenticated-by-default security filter chain). You add business logic; the platform
does the cross-cutting work.

## 1. Generate the service

Prerequisites: JDK 25, Maven 3.9+, and the platform artifacts (archetype, `platform-service-parent`,
BOM, starters) in a repository your Maven can reach — the organization's artifact repository, or your
local repository after `mvn install -DskipTests` in the platform checkout. Run it from any directory
outside a Maven project. A logging + data + REST-client service:

```bash
mvn org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -B \
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform \
  -DarchetypeArtifactId=platform-service-archetype \
  -DarchetypeVersion=<platform-version> \
  -DgroupId=com.acme.orders -DartifactId=orders-service -Dpackage=com.acme.orders \
  -Dfeatures=data,restclient
```

- **`features`** (optional, default `none`): `none`, or a comma-separated subset of `messaging`, `data`,
  `restclient` — no spaces, no duplicates, any order. Each adds the matching starter and a sample with
  its tests: `messaging/OrderEvents`, `data/Note` + `NoteRepository` + the Flyway migration
  `db/migration/V1__create_note.sql`, `client/GreetingClient`. Anything else (`kafka`, `database`,
  `data, restclient`, `none,data`) fails generation with `unsupported features value …`.
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
  src/main/java/.../hello/HelloController.java   GET /hello (authenticated)
  src/main/resources/application.yml             offline dev defaults (every profile but prod); local/test/prod
  src/main/java/.../data/, client/, messaging/   feature samples (empty files when the feature is off)
  src/test/java/.../HelloControllerTest.java     @PlatformWebTest + TestTokens
  src/test/java/.../ApplicationSmokeTest.java    @PlatformTest (boots the full context)
  src/test/java/.../SecurityIntegrationTest.java real HTTP: 401 for missing/untrusted/expired tokens, 200 via a loopback JWKS
  src/test/java/.../StructuredLoggingTest.java   parses the emitted JSON log events: fields, correlation, no tokens
  src/test/java/.../data/NoteRepositoryTest.java Flyway-created schema, CRUD across transactions, rollback, constraint
  src/test/java/.../client/GreetingClientTest.java real calls to a loopback stub: JSON, errors, timeouts, relay
  src/test/java/.../PlatformConformanceTest.java runs PlatformUsageRules over your code
```

## 3. Build, test, run

```bash
cd orders-service
mvn verify                 # all of the above; no Docker, IdP, database, or downstream service required
mvn spring-boot:run        # boots on :8080 with no profile (JSON logs); -Dspring-boot.run.profiles=local for console logs
java -jar target/orders-service-1.0-SNAPSHOT.jar

curl http://localhost:8080/actuator/health/readiness   # open
curl http://localhost:8080/actuator/platform           # capability report (open outside prod)
curl -H "Authorization: Bearer <jwt>" "http://localhost:8080/hello?name=alice"
```

Point `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` at your real IdP to call business
endpoints (the generated `application.yml` ships a placeholder so the app boots offline). In production
run with `--spring.profiles.active=prod` and supply the IdP, `spring.datasource.*` (data) and
`app.greeting.base-url` (restclient sample) externally: `prod` refuses to start without them rather than
falling back to placeholders or an embedded H2 database.

## 4. Add a capability

Nothing is auto-injected (ADR: explicit dependencies). Add the starter, use the platform API:

| Capability | Starter | Use |
|---|---|---|
| Messaging | `platform-starter-messaging-inmemory` / `-kafka` / `-rabbit` | `EventPublisher` / `@EventHandler` |
| Persistence | `platform-starter-data-jpa` | Spring Data + platform JPA conventions |
| Caching | `platform-starter-cache-caffeine` / `-redis` | `@Cacheable` + platform key convention |
| Locking | `platform-starter-locking-jdbc` / `-redis` | `@LockedSchedule` |
| Rate limiting | `platform-starter-ratelimit` | platform rate-limit API |
| Feature flags | `platform-starter-flags` | `FeatureFlags` |
| Authorization | `platform-starter-security-authz` | `@RequiresPermission` |

Full catalog: [`docs/modules/`](modules/). Read your service's `CLAUDE.md` before adding cross-cutting
code — the `PlatformConformanceTest` fails the build if you hand-roll what the platform already owns.

## 5. Keep it healthy

The whole path — generate, build, boot, probe — is exercised for every feature combination by
[`tooling/scripts/golden-path.sh`](../tooling/scripts/golden-path.sh), the CI `generator-gate` job.
See [`docs/modules/dx.md`](modules/dx.md) for the archetype, `upgrade-check`, and conformance rules.
