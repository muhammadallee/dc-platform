# Quickstart — a new platform service in under 10 minutes

The DC Platform ships a Maven archetype that generates a ready-to-run service wired with the
golden-path chassis (correlation IDs, JSON logging, RFC-9457 errors, validation, metrics/tracing,
OpenAPI, and an authenticated-by-default security filter chain). You add business logic; the platform
does the cross-cutting work.

## 1. Generate the service

```bash
mvn org.apache.maven.plugins:maven-archetype-plugin:3.1.2:generate -B \
  -DarchetypeGroupId=ae.gov.dubaicustoms.platform \
  -DarchetypeArtifactId=platform-service-archetype \
  -DarchetypeVersion=<platform-version> \
  -DgroupId=com.acme.orders -DartifactId=orders-service -Dpackage=com.acme.orders \
  -DplatformVersion=<platform-version> \
  -Dfeatures=messaging,data
```

- **`features`** (optional, default `none`): a comma-separated subset of `messaging`, `data`. Each adds
  the matching starter(s) and a sample under `src/main/java/.../{messaging,data}`. Omit it (or use
  `none`) for a bare web service.
- **`platformVersion`**: the platform release to build against (the generated parent + BOM version).

> Pin `maven-archetype-plugin:3.1.2`: newer versions make `archetype:generate` fork a lifecycle that
> fails when run outside a project on Maven 3.9.x.

## 2. What you get

```
orders-service/
  pom.xml                     parent=platform-service-parent; golden-path starters (+ feature starters)
  CLAUDE.md / AGENTS.md       how coding agents should use platform APIs (read at session start)
  README.md                   run / test / add-a-capability
  src/main/java/.../Application.java
  src/main/java/.../hello/HelloController.java   GET /hello (authenticated)
  src/main/resources/application.yml             commented platform defaults; local/test/prod profiles
  src/test/java/.../HelloControllerTest.java     @PlatformWebTest + TestTokens
  src/test/java/.../ApplicationSmokeTest.java    @PlatformTest (boots the full context)
  src/test/java/.../PlatformConformanceTest.java runs PlatformUsageRules over your code
```

## 3. Build, test, run

```bash
cd orders-service
mvn verify                 # platform slices + PlatformConformanceTest; no Docker required
mvn spring-boot:run        # boots on :8080

curl http://localhost:8080/actuator/health        # open by default
curl http://localhost:8080/actuator/platform       # active capabilities + providers
curl -H "Authorization: Bearer <jwt>" "http://localhost:8080/hello?name=alice"
```

Point `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` at your real IdP (the generated
`application.yml` ships a placeholder so the app boots offline).

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

The whole path — generate, build, boot, probe — is exercised by
[`tooling/scripts/golden-path.sh`](../tooling/scripts/golden-path.sh), the CI-required DX contract.
See [`docs/modules/dx.md`](modules/dx.md) for the archetype, `upgrade-check`, and conformance rules.
