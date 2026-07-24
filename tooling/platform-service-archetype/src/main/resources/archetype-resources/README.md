# @ARTIFACT_ID@

A service on the **DC Platform** chassis. The platform provides correlation IDs, JSON logging,
RFC-9457 error handling, metrics/tracing, OpenAPI, and an authenticated-by-default security filter
chain. You write business logic; see `CLAUDE.md` before adding cross-cutting code.

## Run

```bash
mvn spring-boot:run
# then, with a bearer token from your IdP:
curl -H "Authorization: Bearer <jwt>" "http://localhost:8080/hello?name=alice"
```

Health and the platform capability report are open by default:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/platform      # active capabilities + providers
```

## Test

```bash
mvn verify            # platform slices + PlatformConformanceTest, no Docker required
mvn -Pdocker verify   # additionally runs @Tag("docker") tests (needs Docker)
```

## Add a capability

Add the starter and use the platform API — nothing is auto-injected (ADR: explicit dependencies).

| Capability | Starter | Use |
|---|---|---|
| Messaging | `platform-starter-messaging-inmemory` (or `-kafka`/`-rabbit`) | `EventPublisher` / `@EventHandler` |
| Persistence (JPA) | `platform-starter-data-jpa` | Spring Data repositories, platform JPA conventions |
| Caching | `platform-starter-cache-caffeine` (or `-redis`) | `@Cacheable` + platform key convention |
| Locking | `platform-starter-locking-jdbc` (or `-redis`) | `@LockedSchedule` |
| Rate limiting | `platform-starter-ratelimit` | platform rate-limit API |
| Feature flags | `platform-starter-flags` | `FeatureFlags` |
| Authorization | `platform-starter-security-authz` | `@RequiresPermission` |

Full catalog: @DOCS_URL@/modules/

## Upgrade the platform

Bump the `platform-service-parent` version in `pom.xml`, then:

```bash
mvn ae.gov.dubaicustoms.platform:platform-build-maven-plugin:upgrade-check -Dplatform.target=<version>
```

Review `target/platform-upgrade-report.md` and fix every reported deprecation.
