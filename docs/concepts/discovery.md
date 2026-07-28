# Layered discovery

The platform is designed to advertise itself at every moment a developer or agent needs it, so nobody
has to guess. Each layer is generated or enforced from code — there are no hand-maintained mirrors that
can rot. The five layers map to the five times you reach for the platform.

## Layer 0 — code time (signals inside the jars)

What you learn while reading or importing a platform artifact.

- **apiguardian `@API(status, since)`** on every public API/SPI type, so stability (STABLE /
  EXPERIMENTAL / DEPRECATED / INTERNAL) is readable straight from the jar. Enforced by an ArchUnit
  rule; `DEPRECATED` must co-occur with `@Deprecated`. See [conventions](conventions.md).
- **FailureAnalyzers** turn the top misconfigurations into a Boot *Description / Action* block whose
  Action names the exact fix (the starter, property, or doc anchor) — a startup failure teaches.

## Layer 1 — scaffold time (the archetype)

A generated service starts correct: `platform-service-parent`, a conformance test, and the agent /
catalog files `CLAUDE.md`, `AGENTS.md`, `.mcp.json`, and `catalog-info.yaml`. See
[developer experience](../modules/dx.md).

## Layer 2 — build time (teaching in the service build)

The service's own build refuses the wrong thing and says why.

- **Dependency bans** in `platform-service-parent`: declaring a wrapped library directly (spring-kafka,
  spring-rabbit, awssdk:s3, resilience4j, springdoc) fails the build with the starter to use instead.
- **`PlatformUsageRules`** (a conformance test the archetype wires in): no hand-rolled
  `ResponseEntityExceptionHandler`, no `new ObjectMapper()`, no `System.getenv`, no `Thread.sleep` in
  production — each violation names the platform alternative.

## Layer 3 — on demand (agents ask, tools rewrite)

- **MCP server** (`platform-mcp-server --stdio`): six read-only tools answer authoritative questions —
  `find_capability`, `property_lookup`, `error_code_lookup`, `usage_example`, `list_starters`,
  `platform_version` — from a build-time `platform-index.json`.
- **Skill** (`platform-skill.zip`): a Claude Skill generated from the same index, tuned to trigger on
  platform vocabulary.
- **Adopt/upgrade recipes** (`platform-migrations`, OpenRewrite): `AdoptPlatform` swaps banned deps for
  starters and flags hand-rolled advice for review; per-train `UpgradeTo_X_Y` skeletons carry
  deprecation-driven rewrites.
- **`llms.txt` / `llms-full.txt`**: the docs, curated from the nav for LLMs.

## Layer 4 — org time (adoption is visible)

- **`platform.capability.active{capability}`** gauge and the `platform.version` common tag feed the
  [adoption dashboard](../operations/adoption.md): trains in production, capability adoption, and who is
  behind.
- **`catalog-info.yaml`** registers each service (with its `dc.platform/train`) in the org catalog.

## Why generated, not written

Every artifact above is produced at build time from the one source that is already authoritative — the
BOM, the config metadata, the error-code registry, the docs pages, the deprecation metadata. Freshness
tests fail the build if a generated surface drifts from the code, so the discovery layers stay true.
