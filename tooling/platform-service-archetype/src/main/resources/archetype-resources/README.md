# Service

A service on the **DC Platform** chassis (its name is the `artifactId` in `pom.xml`). The platform
provides correlation IDs, JSON logging,
RFC-9457 error handling, metrics/tracing, OpenAPI, and an authenticated-by-default security filter
chain. You write business logic; see `CLAUDE.md` before adding cross-cutting code.

## Run

```bash
mvn spring-boot:run                      # no profile: JSON logs, offline placeholders, H2 if data is on
mvn spring-boot:run -Dspring-boot.run.profiles=local   # same, with human-readable console logs
java -jar target/<artifactId>-<version>.jar            # the packaged service, after mvn verify
```

Health and readiness are always open; the platform capability report is open outside `prod`:

```bash
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/platform      # active capabilities + providers
```

Business endpoints need a bearer token that your identity provider signed. The generated
`application.yml` points `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` at a placeholder so the
service boots offline; override it to call authenticated endpoints:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://idp.example/jwks
curl -H "Authorization: Bearer <jwt>" "http://localhost:8080/hello?name=alice"
```

## Production (`prod` profile)

Run with `--spring.profiles.active=prod` and supply every real coordinate from the environment or the
platform secrets source — never from `application.yml`:

| Setting | When |
|---|---|
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (or `issuer-uri`) | always |
| `spring.datasource.url` / `username` / `password` (+ the JDBC driver dependency) | `data` feature |
| `app.greeting.base-url` | `restclient` feature sample |

A missing value fails startup with a message naming it: `prod` never falls back to the offline
placeholders or to an embedded H2 database, and the capability report requires authentication there.

## Test

```bash
mvn verify            # platform slices, real-HTTP security/logging tests, PlatformConformanceTest; no Docker
mvn -Pdocker verify   # additionally runs @Tag("docker") tests (needs Docker)
```

The generated tests run against local fixtures only: H2 for data, a loopback HTTP stub for outbound
calls, and a loopback JWKS issuer (`TestJwtIssuer`) so bearer tokens are really validated.

## Add a capability

Add the starter and use the platform API — nothing is auto-injected (ADR: explicit dependencies).

| Capability | Starter | Use |
|---|---|---|
| Messaging | `platform-starter-messaging-inmemory` (or `-kafka`/`-rabbit`) | `EventPublisher` / `@EventHandler` |
| Persistence (JPA) | `platform-starter-data-jpa` | Spring Data repositories, Flyway migrations under `db/migration` |
| Outbound HTTP | `platform-starter-restclient` | `PlatformRestClientFactory.builder("name")` |
| Caching | `platform-starter-cache-caffeine` (or `-redis`) | `@Cacheable` + platform key convention |
| Locking | `platform-starter-locking-jdbc` (or `-redis`) | `@LockedSchedule` |
| Rate limiting | `platform-starter-ratelimit` | platform rate-limit API |
| Feature flags | `platform-starter-flags` | `FeatureFlags` |
| Authorization | `platform-starter-security-authz` | `@RequiresPermission` |

Full catalog: https://platform.dubaicustoms.gov.ae/docs/modules/

## Upgrade the platform

Bump the `platform-service-parent` version in `pom.xml`, then:

```bash
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check -Dplatform.target=<version>
```

Review `target/platform-upgrade-report.md` and fix every reported deprecation.
