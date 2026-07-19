# Phase 1 — Foundation (P0, size S, no Docker)

**Goal:** a green, publishable Maven reactor: root aggregator, two parents, two BOMs, repo hygiene.
**Ends with:** an empty demo app building on `platform-service-parent` and booting.

## Modules
`platform-parent`, `platform-dependencies`, `platform-bom`, `platform-service-parent`, root aggregator.

## Repo skeleton to create
```
dc-platform/
├── pom.xml                       (aggregator)
├── CLAUDE.md  CHANGELOG.md  README.md  .gitignore  .editorconfig
├── docker-compose.local.yml
├── build/
│   ├── platform-parent/pom.xml
│   ├── platform-dependencies/pom.xml
│   ├── platform-bom/pom.xml
│   └── platform-service-parent/pom.xml
└── tooling/scripts/              (empty; golden-path.sh arrives in phase 13)
```

## 1. Root aggregator `pom.xml` (use verbatim, update spring-boot.version to latest stable)
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>dc-platform</artifactId>
  <version>${revision}</version>
  <packaging>pom</packaging>
  <name>DC Platform (aggregator)</name>

  <properties>
    <revision>0.1.0-SNAPSHOT</revision>
    <maven.compiler.release>21</maven.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.build.outputTimestamp>2026-01-01T00:00:00Z</project.build.outputTimestamp>
  </properties>

  <modules>
    <module>build/platform-dependencies</module>
    <module>build/platform-parent</module>
    <module>build/platform-bom</module>
    <module>build/platform-service-parent</module>
    <!-- phases append here in order -->
  </modules>
</project>
```

## 2. `build/platform-dependencies/pom.xml` — third-party alignment BOM
- `packaging=pom`; parent = root aggregator (so it inherits `${revision}`), plus `flatten-maven-plugin`
  (`flattenMode=bom`) so the published POM is standalone.
- `<dependencyManagement>`: **import** `org.springframework.boot:spring-boot-dependencies:<latest 3.x>`
  (scope import), then pin only libraries Boot does NOT manage, as they are introduced by later phases:
  `springdoc-openapi-starter-webmvc-ui`, `resilience4j-spring-boot3`, `archunit-junit5`,
  `logstash-logback-encoder` (or ECS encoder), `shedlock-*` (if chosen in phase 9),
  `software.amazon.awssdk:bom` (import, phase 10), `open-feature sdk` (phase 10).
  Add each pin **in the phase that introduces it** — do not pre-pin speculatively; leave a
  `<!-- pins are added by the phase that introduces the dependency -->` comment now.
- Rule: **no `<version>` tags anywhere else in the repo** (enforced in phase 2).

## 3. `build/platform-parent/pom.xml` — parent for all platform modules
Parent = root aggregator. `packaging=pom`. Contains ONLY `<dependencyManagement>` importing
`platform-dependencies:${revision}`, plus `<build><pluginManagement>` + always-on plugins:
- `maven-compiler-plugin` (release 21, `-parameters`), `maven-surefire-plugin` (JUnit5; exclude tag
  `docker` by default: `<excludedGroups>docker</excludedGroups>`), `maven-failsafe-plugin` (same),
  `flatten-maven-plugin` (resolveCiFriendliesOnly) bound to process-resources — required for `${revision}`,
  `jacoco-maven-plugin` (prepare-agent + report; no threshold yet — threshold arrives phase 2),
  `maven-enforcer-plugin` with built-ins now: `requireJavaVersion [21,`, `requireMavenVersion [3.9,`,
  `banDuplicatePomDependencyVersions`, `dependencyConvergence` (custom rules arrive phase 2),
  `spring-boot-configuration-processor` via pluginManagement note (added per-module as annotationProcessor),
  `japicmp-maven-plugin` in pluginManagement only (activated phase 2, `<skip>true</skip>` until first release).
- Profile `docker`: sets `<excludedGroups/>` empty and `<groups>docker</groups>` OFF —
  i.e. in `docker` profile surefire/failsafe run ALL tests including tag `docker`.
- Test-scope dependencies managed here for uniform versions: JUnit BOM (via Boot), AssertJ, Mockito,
  `spring-boot-starter-test`, Testcontainers BOM (import in dependencyManagement).

## 4. `build/platform-bom/pom.xml` — platform artifact BOM
`packaging=pom`, parent = root aggregator, flatten as bom. `<dependencyManagement>` first imports
`platform-dependencies:${revision}`, then lists every `ae.gov.dubaicustoms.platform:*:${revision}` artifact.
Seed now with the four build POMs' coordinates commented as placeholder; **every later phase appends
its new artifacts here in the same PR** (checklist item in reference/module-checklist.md).

## 5. `build/platform-service-parent/pom.xml` — the application-facing parent
Parent = `spring-boot-starter-parent:<same Boot version>` (gives apps Boot's plugin defaults);
NOT the aggregator — this POM must stand alone for external repos. `flatten` for `${revision}`? No:
give it the literal platform version via property `platform.version` defaulting to `${project.version}`…
**Simplification (use this):** `platform-service-parent` has its own `<version>${revision}</version>`
via the aggregator parent chain is NOT possible outside the reactor, so:
- parent = `spring-boot-starter-parent`
- explicit `<groupId>ae.gov.dubaicustoms.platform</groupId><artifactId>platform-service-parent</artifactId><version>${revision}</version>`
  with flatten-maven-plugin resolving `${revision}` at deploy (standard CI-friendly pattern; it IS part of the reactor via `<modules>`, Maven allows a module whose parent is external).
- `<dependencyManagement>`: import `ae.gov.dubaicustoms.platform:platform-bom:${revision}`.
- `<properties>`: `java.version=21`, `maven.compiler.parameters=true`.
- `<build>`: spring-boot-maven-plugin (build-info + layered jar), surefire excludedGroups `docker`,
  git-commit-id plugin, jacoco. Nothing else — keep this POM under ~120 lines; comment each block
  with WHY it exists (apps' developers read this file).

## 6. `docker-compose.local.yml`
Services (all with healthchecks, low memory): `kafka` (single-node KRaft), `rabbitmq:management`,
`redis`, `postgres:16` (db/user/pass `platform`), `vault` (dev mode, root token `dev-root`),
`localstack` (s3). Comment header: "Optional. Only needed for -Pdocker tests or manual runs. Default build never requires this."

## 7. Hygiene files
`.gitignore` (target, .idea, *.iml, .DS_Store), `.editorconfig` (4-space java, LF, utf-8),
`CHANGELOG.md` (Keep-a-Changelog skeleton with `## [Unreleased]`), `README.md` (one screen:
what this is, build command, link to docs and specs).

## Acceptance (all from repo root)
```bash
mvn -T1C verify                          # green
mvn -T1C -Drevision=0.1.0 verify         # revision override works
# scratch demo app (delete after):
#  create /tmp/demo with parent platform-service-parent:${revision} (install parents first:
mvn -T1C install
#  demo pom + @SpringBootApplication + starter-web) then:
(cd /tmp/demo && mvn spring-boot:run &) && sleep 20 && curl -sf localhost:8080/actuator/health || true
```
Definition of done: acceptance green; all POMs commented per coding-standards §3 (every non-obvious
block has a one-line WHY comment); CHANGELOG updated; conventional commits.
