# Phase 13 — Developer Experience (P1, size M, no Docker)

## A. `tooling/platform-service-archetype` (maven-archetype packaging)
Generates:
```
<service>/pom.xml            parent=platform-service-parent; deps: starter-core, starter-errors,
                             starter-logging, starter-validation, starter-observability, starter-openapi,
                             starter-security, starter-test (+ optional flags via archetype property
                             features=messaging,data → adds starters + sample code conditionally)
src/main/java/…/Application.java             (@SpringBootApplication, javadoc header)
src/main/java/…/hello/HelloController.java  (GET /hello, @RequiresPermission optional-commented)
src/main/java/…/hello/HelloService.java
src/main/resources/application.yml           (commented: every platform default it relies on, profiles local/test/prod)
src/test/java/…/HelloControllerTest.java     (@PlatformWebTest + TestTokens)
src/test/java/…/ApplicationSmokeTest.java    (@PlatformTest boots context)
README.md                                    (run, test, add-a-capability table linking docs)
```
Archetype integration test: generate with/without `features`, `mvn verify` the result (harness).

## B. `platform-build-maven-plugin:upgrade-check` goal
Input: target platform version. Steps: resolve new BOM; diff managed versions (report);
scan project yaml/properties for keys deprecated in new version (read spring-configuration-metadata
deprecation entries from platform jars); japicmp the app's compiled classes' references? v1 scope:
version diff + property scan + link to train release notes. Output: console + `target/platform-upgrade-report.md`.

## C. `tooling/scripts/golden-path.sh` (the DX contract, executable)
```bash
#!/usr/bin/env bash — set -euo pipefail
mvn -T1C install -DskipTests            # platform into local repo
workdir=$(mktemp -d)
(cd "$workdir" && mvn archetype:generate -B -DarchetypeGroupId=com.acme.platform \
   -DarchetypeArtifactId=platform-service-archetype -DarchetypeVersion=$REV \
   -DgroupId=com.acme.demo -DartifactId=demo -Dfeatures=messaging)
(cd "$workdir/demo" && mvn -q verify)
(cd "$workdir/demo" && mvn -q spring-boot:start)   # uses start/stop goals for CI-friendliness
curl -sf localhost:8080/actuator/health
curl -sf localhost:8080/actuator/platform | grep messaging
(cd "$workdir/demo" && mvn -q spring-boot:stop)
echo "GOLDEN PATH OK"
```
Wire into CI as a required job. Time budget: fail if wall clock > 10 min (comment: the DX SLA).

Acceptance: `./tooling/scripts/golden-path.sh` green locally; docs quickstart.md rewritten around the
archetype; **tag 0.2.0 (Milestone M2)**.

## D. Agent discoverability (REQUIRED additions)
1. **Archetype generates `CLAUDE.md`** in every new service from
   `reference/service-claude-md-template.md` (substitute artifactId, docs URL, error-code namespace).
   Rationale: coding agents (Claude Code) read CLAUDE.md at session start; without it they write
   vanilla Spring Boot instead of platform APIs. Also generate `AGENTS.md` as a symlink/copy for
   other agent tooling.
2. **Consumer conformance rules** (`platform-test-api`, package `…testing.arch`):
   `PlatformUsageRules.all()` — ArchUnit rules services run via a generated
   `PlatformConformanceTest` (archetype includes it): ban direct injection of
   KafkaTemplate/RabbitTemplate/listener containers (message: "use EventPublisher/@EventHandler"),
   ban user-defined @RestControllerAdvice extending ResponseEntityExceptionHandler
   (message: "throw PlatformException subtypes"), ban System.getenv in main sources
   (message: "use platform secrets"). Each violation message names the platform alternative —
   gates teach agents. Rules are deletable by teams (documented escape hatch, discouraged).
3. Golden-path script asserts the generated service contains CLAUDE.md and that
   PlatformConformanceTest runs.
