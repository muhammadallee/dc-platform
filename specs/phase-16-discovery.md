# Phase 16 — Layered Discovery & Agent Enablement (P1 after M2; size M–L, no Docker)

**Goal:** make the platform self-advertising at every moment of need (code-, build-, run-, on-demand-,
org-time). Everything below is generated/tested from code — no hand-maintained mirrors that can rot.
**Prereqs:** phases 13–15. Execute sections independently; each is separately shippable.

## A. Layer 0 — richer in-jar signals (edits to existing modules)
1. **API status annotations:** add `org.apiguardian:apiguardian-api` (pin in platform-dependencies)
   and annotate all public API/SPI types `@API(status = STABLE|EXPERIMENTAL, since = "x.y")`;
   internals `@API(status = INTERNAL)`. ArchUnit rule (build-tools): every public type in a
   non-internal package carries `@API`. Replaces the ad-hoc `@PlatformApi` marker? NO — keep both;
   `@PlatformApi` stays for enforcer targeting; document the pairing (decision log).
2. **FailureAnalyzers** (this becomes step 6 of reference/autoconfigure-pattern.md — append it there):
   each capability's autoconfigure ships `spring.factories`-registered `FailureAnalyzer`s for its
   top misconfigurations. Mandatory analyzers: messaging-no-transport ("add platform-starter-messaging-<p>"),
   security-no-issuer, data-jpa-missing-flyway, storage-fs-root-unwritable, secrets-unresolvable-ref.
   Format: standard Boot "Description / Action" block; Action ALWAYS names the starter/property/doc anchor.
   Test each with `FailureAnalyzers` unit pattern.

## B. Layer 2 — build-time teaching in services (edits: platform-service-parent, platform-test-api)
1. **Dependency bans in `platform-service-parent`:** enforcer `bannedDependencies` for libraries the
   platform wraps — direct `org.springframework.kafka:spring-kafka`, `spring-rabbit`,
   `software.amazon.awssdk:s3`, `spring-cloud-vault-*`, `resilience4j-*` (raw), `springdoc-*` (raw).
   Each ban's `<message>` names the starter + doc anchor. Escape hatch: property
   `platform.bans.skip=true` per module (visible in review; documented as discouraged).
2. **`PlatformUsageRules` finalization** (started phase 13): move rules to `platform-test-api`
   `…testing.arch`; add rules: no custom `ResponseEntityExceptionHandler` subclass, no
   `System.getenv` in main, no `new ObjectMapper()` in main (use injected), no `Thread.sleep` in main.
   Every violation message: "banned here → use <platform alternative> (<doc anchor>)".

## C. Layer 3 — platform MCP server (`tooling/platform-mcp-server`, new module, Implementation category)
Purpose: agents query authoritative platform facts instead of guessing from training data.
- **Index, not live code:** docs build (phase 14) additionally emits `platform-index.json`:
  capabilities (name, starter coords, one-line, doc URL), all config keys (+type/default/description/
  deprecation) from aggregated metadata, error codes (+meaning), API/SPI type inventory (+@API status),
  usage snippets extracted from docs pages' fenced blocks tagged `snippet:<id>`.
  A completeness test diffs index vs BOM artifact list.
- **Server:** small Spring Boot app using the official MCP Java SDK (pin), stdio + streamable-HTTP.
  Tools (names/params are contracts):
  `find_capability(query)` → ranked capabilities with starter coords;
  `property_lookup(keyOrPrefix)` → keys with defaults/deprecations;
  `error_code_lookup(code)`;
  `usage_example(capability, task)` → snippet;
  `list_starters()`; `platform_version()`.
  Read-only, no auth for v1 (internal network), 1 jar, runs locally: `java -jar platform-mcp-server.jar --stdio`.
- Tests: tool contract tests against a fixture index; index-freshness test in release pipeline.
- **Wire-in:** archetype (see E) emits `.mcp.json` pointing at org endpoint with stdio fallback comment.

## D. Layer 3 — Skill, adopt-recipes, llms.txt
1. **Claude Skill (`tooling/platform-skill`):** build generates `SKILL.md` (description tuned to trigger
   on "ACME platform", capability names, starter ids) + `resources/` (condensed per-capability guides,
   property cheat sheet) FROM docs sources at build time; packaged zip artifact `platform-skill.zip`
   published with the train. A staleness test: SKILL.md version == train version.
2. **Adopt/upgrade recipes (`tooling/platform-migrations`, OpenRewrite):** recipe `com.acme.platform.AdoptPlatform`:
   swap banned deps for starters; add parent if absent; add CLAUDE.md/AGENTS.md/.mcp.json/conformance test
   from templates; mechanical rewrites where safe (e.g. `@ControllerAdvice` removal flagged with TODO
   comment rather than deleted — comment the safety rationale); plus per-train `UpgradeTo_X_Y` recipe
   skeleton generated from deprecation metadata. Tested with rewrite-test fixtures (before/after java files).
3. **`llms.txt`** (phase-14 addendum — apply there): docs site root gets `llms.txt` + `llms-full.txt`
   generated from nav; all pages remain clean markdown; add Diátaxis mapping: quickstart=tutorial,
   modules/*=how-to+reference split (add "Tasks" section per module page), concepts/*=explanation.

## E. Layer 1/4 — archetype & catalog additions (edits: platform-service-archetype)
Generate additionally: `.mcp.json` (platform MCP endpoint placeholder + stdio fallback),
`catalog-info.yaml` (Backstage Component: name, owner param, `acme.platform/train` annotation),
`AGENTS.md` (copy of CLAUDE.md). Golden-path script asserts all four generated files.

## F. Layer 4 — adoption telemetry
1. Observability autoconfigure already tags `platform.version`; ADD per-capability gauge
   `platform.capability.active{capability=...}` (0/1) emitted from CapabilityDescriptors.
2. `docs/operations/adoption.md` + `tooling/dashboards/platform-adoption.grafana.json`:
   trains in prod, capability adoption %, deprecated-property warnings count (log-derived), services on N-2+.
3. Release pipeline posts train adoption snapshot link into release notes (TODO comment if no Grafana yet).

## Acceptance
```bash
mvn -T1C verify                                        # incl. new modules + @API archrule
# bans: scratch service adds spring-kafka directly -> build fails, message names starter
java -jar tooling/platform-mcp-server/target/platform-mcp-server.jar --stdio <<< '<initialize+find_capability probe>'   # returns messaging for "events"
mvn -pl tooling/platform-migrations test               # recipe before/after fixtures green
./tooling/scripts/golden-path.sh                       # asserts CLAUDE.md, AGENTS.md, .mcp.json, catalog-info.yaml
```
DoD: index/skill/llms.txt all generated (zero hand-maintained copies) with freshness tests; docs page
`concepts/discovery.md` explaining the 5 layers; BOM (mcp-server, migrations, skill artifacts); CHANGELOG.
