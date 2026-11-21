# Service

A service on the **DC Platform** chassis (its name is the `artifactId` in `pom.xml`). The platform
provides correlation IDs, JSON logging,
RFC-9457 error handling and metrics/tracing. You write business logic; see `CLAUDE.md` before adding
cross-cutting code.

**Not secured yet.** Web security (basic auth behind the API gateway) and OpenAPI documentation come with
the platform's REST API capability in its next release. Until then, do not expose this service's
endpoints outside the cluster.

## Run

```bash
mvn spring-boot:run                      # no profile: JSON logs, offline placeholders, H2 if data is on
mvn spring-boot:run -Dspring-boot.run.profiles=local   # same, with human-readable console logs
java -jar target/<artifactId>-<version>.jar            # the packaged service, after mvn verify
```

Actuator has its own port, 8081, so it is never reachable through the route that serves the API on 8080. Health
and readiness are always available there; the platform capability report is exposed outside `prod`:

```bash
curl http://localhost:8081/actuator/health/readiness
curl http://localhost:8081/actuator/platform      # active capabilities + providers
```

Call the sample endpoint:

```bash
curl "http://localhost:8080/hello?name=alice"
```

## Production (`prod` profile)

Run with `--spring.profiles.active=prod` and supply every real coordinate from the environment or a
mounted secret — never from `application.yml`:

| Setting | When |
|---|---|
| `spring.datasource.url` / `username` / `password` (generate with `-Ddatabase=oracle` for the Oracle driver + Flyway module) | `data` feature |

A missing value fails startup with a message naming it: `prod` never falls back to the offline
placeholders or to an embedded H2 database, and the capability report is not exposed there.

## Test

```bash
mvn verify            # platform slices, real-HTTP logging tests, PlatformConformanceTest; no Docker
mvn -Pdocker verify   # additionally runs @Tag("docker") tests (needs Docker)
```

The generated tests run against local fixtures only: H2 for data and an in-memory transport for
messaging.

## Add a capability

Add the starter and use the platform API — nothing is auto-injected (ADR: explicit dependencies).

| Capability | Starter | Use |
|---|---|---|
| Messaging | `platform-starter-messaging-inmemory` (or `-rabbit`) | `EventPublisher` / `@EventHandler` |
| Transactional outbox | `platform-starter-messaging-outbox` | `OutboxPublisher` inside your `@Transactional` method |
| Persistence (JPA) | `platform-starter-data-jpa` (`-oracle` for Oracle) | Spring Data repositories, Flyway migrations under `db/migration` |

Full catalog: https://platform.dubaicustoms.gov.ae/docs/modules/

## Upgrade the platform

Bump the `platform-service-parent` version in `pom.xml`, then:

```bash
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check -Dplatform.target=<version>
```

Review `target/platform-upgrade-report.md` and fix every reported deprecation.
