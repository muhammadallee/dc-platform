# Phase 14 — Documentation as a Product (P1, continuous; finalize here; size M, no Docker)

## Module: `docs/platform-docs`
Static site (Antora if asciidoc preferred; otherwise MkDocs-material via a maven-exec of `mkdocs build`
is acceptable for local simplicity — choose MkDocs, comment decision). Built by `mvn -pl docs/platform-docs verify`.

## Site structure (files; many already exist from per-phase DoD — this phase completes & wires them)
```
docs/
├── index.md                      what/why, 3-min tour, version banner
├── quickstart.md                 archetype → running service (from phase 13)
├── concepts/
│   ├── architecture.md           condensed from the architecture doc (§1–2, §14 recommendation)
│   ├── constitution.md           dependency rules + how the build enforces them
│   ├── conventions.md            properties, packages, error codes, profiles
│   └── extension-model.md        customizer tier vs provider tier + TCK certification
├── modules/<cap>.md              PER-CAPABILITY TEMPLATE (mandatory sections):
│                                 What you get | Starter coordinates | Zero-config behavior |
│                                 Properties table (GENERATED — see below) | Customize | Replace/Disable |
│                                 Error codes | Testing (slice/fixture) | Local dev notes
├── reference/
│   ├── properties.md             GENERATED: aggregate all spring-configuration-metadata.json →
│   │                             one table (key, type, default, description, deprecation). Small
│   │                             generator = a test-scope main() in platform-docs, run at build.
│   ├── error-codes.md            GENERATED from phase-4 error-codes.csv aggregation
│   ├── bom.md                    GENERATED artifact list from platform-bom
│   └── compatibility.md          japicmp reports per release (linked)
├── runbooks/                     copy + maintain the four runbooks from this spec pack
├── decisions/
│   ├── adr-001..010.md           port the ten ADRs from the architecture document verbatim
│   └── decision-log.md           running log (CLAUDE.md "when the spec is silent")
└── upgrade/<version>.md          per-train notes: highlights, deprecations, ACTION REQUIRED
```
Gates: docs build in reactor; link checker (lychee or mkdocs strict) fails on broken links;
**property-completeness test**: every metadata key appears in generated reference AND every capability
page exists for every `platform-starter-*` (a unit test in platform-docs asserts both).

## Javadoc
Aggregate javadoc for `-api`/`-spi` modules only (`maven-javadoc-plugin` aggregate profile,
excludePackageNames `*.internal.*`); publish under docs site `/apidocs`. Failing javadoc = failing build (already via checkstyle; javadoc plugin `doclint=all,-missing` on internal).

Acceptance: `mvn -T1C verify` builds site; `docs/site/` renders locally (`python -m http.server`);
completeness tests green; CHANGELOG.
