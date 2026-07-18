# ACME Platform

Internal Spring Boot chassis: build services with one parent POM, one `application.yml`,
one `@SpringBootApplication`, a controller and a service — conventions handle the rest.

## Build

```bash
mvn -T1C verify            # full build, no Docker required
mvn -Pdocker verify        # additionally runs @Tag("docker") infra tests (needs Docker)
mvn -T1C install           # install locally to build apps against ${revision}
```

## Use (application teams)

```xml
<parent>
  <groupId>com.acme.platform</groupId>
  <artifactId>platform-service-parent</artifactId>
  <version><!-- current train --></version>
</parent>
<dependencies>
  <dependency>
    <groupId>com.acme.platform</groupId>
    <artifactId>platform-starter-core</artifactId> <!-- available from phase 3 -->
  </dependency>
  <!-- add capability starters a la carte -->
</dependencies>
```

## Repository map

- `build/` — parents, BOMs, build tooling (gates land in phase 2)
- `specs/` — implementation specs (master plan, phases, references, runbooks) — start at `specs/00-MASTER-PLAN.md`
- `CLAUDE.md` — execution rules for Claude Code
- capability directories (`core/`, `errors/`, `logging/`, ...) appear phase by phase

## Docs

Until the docs site ships (phase 14): `specs/` is the source of truth.
Architecture & rationale: `specs/platform-architecture.md`.
