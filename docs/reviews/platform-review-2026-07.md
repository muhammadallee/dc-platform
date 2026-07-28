# DC Platform — Architecture & Health Review

**Reviewed:** 2026-07-28
**Version:** `1.0.0-SNAPSHOT` (post `1.0.0-RC1`)
**Stack:** Spring Boot 4.1.0, Java 25, Maven multi-module
**Scale:** 118 modules, 711 Java files, 259 test classes, 23 capabilities, 16 phases delivered.

> This is a point-in-time review. Findings reference the state of `main` at commit `9d3dbf0`.
> Where it recommends changes, follow-up is tracked in §4 (roadmap). Track 1 items were
> actioned in the same change set that added this document.

## 1. Verdict

A genuinely well-engineered platform. The Hybrid Starter + SPI architecture is the right choice
and is applied consistently: every capability is sliced `api / spi / impl / autoconfigure / starter`,
the dependency constitution is *enforced by the build* (ArchUnit + enforcer + checkstyle), every
autoconfigure module ships the 5-case ContextRunner matrix, kill switches are uniform, and the whole
reactor builds with **no Docker/network/credentials**. Phase 16 (agent discovery: apiguardian markers,
FailureAnalyzers, MCP server, Skill, OpenRewrite recipes, `llms.txt`) is ahead of what most internal
platforms ever attempt.

The improvements below are **not rescue work** — they are the difference between "RC1 that works" and
"1.0 that hundreds of teams can adopt and the platform team can sustain." Ranked by leverage, not effort.

## 2. What's strong (keep doing)

- **Enforced boundaries.** The constitution isn't documentation, it's gates. This is the single most
  valuable property and it decays the moment enforcement lapses — protect it.
- **Local-first testing.** Real infra behind `@Tag("docker")` + `assumeTrue`. Excellent for onboarding
  and CI cost.
- **Coordinated release train + BOM** with `${revision}`, japicmp SemVer gating, N-1 compat job.
- **Discovery layer (Phase 16).** Machine-readable stability (`@API`), FailureAnalyzers that name the
  exact fix, an MCP server serving an authoritative index. A real moat for agent-assisted adoption.
- **Decision discipline.** 79 logged decisions with rationale — rare and valuable.

## 3. Findings & gaps

### A. Capability gaps vs. the spec (functional completeness) — **High**

| Capability | Spec | Status | Impact |
|---|---|---|---|
| **secrets** | Phase 10, P2 | **Intentionally absent** (D50/D80) | Services use Spring Cloud Vault + `${...}` placeholders with restart-based rotation, so no platform module is warranted (see revised finding below and `specs/phase-17-secrets.md`). The real debt is *inconsistency*: four references (`noSystemGetenv` rule, a deferred FailureAnalyzer, a deferred Vault ban, the omitted TCK) point at a platform secrets source that doesn't exist — they must be re-pointed, not backfilled with a module. |
| **tenancy** | Phase 11, P3 | **Absent** | Acceptable (P3/optional), but should be explicitly declared out-of-scope for 1.0, not silently missing. |
| **Kafka messaging** | Phase 7 | Module exists; examples/infra deferred to Rabbit | Untested end-to-end. Ships a starter with no example and no smoke coverage → adopter risk. |

The secrets item is the notable one — but the resolution is *reconciliation, not construction*. After
clarifying the consuming architecture (Spring Cloud Vault + `${...}` placeholders, static-KV values
rotated by rolling restart), building a `secrets-api/spi/impl` capability would wrap what Spring and
Kubernetes already do. The debt is that the reactor references a platform secrets source that was never
built; the fix is to re-point those references at the sanctioned pattern (Track 2, `specs/phase-17-secrets.md`).

### B. Documentation drift & fragmentation — **High (low effort)**

- **`specs/platform-architecture.md` header is stale:** said *"Spring Boot 3.x, Java 21 LTS"*; reality is
  Boot 4.1 / Java 25 (decision D6). The foundational design doc contradicted the code on line 4.
- **Two parallel decision systems:** `docs/decisions/adr-001..010.md` *and* `docs/decisions/decision-log.md`
  (D1–D79). Readers can't tell which is canonical.
- **`README` said "until the docs site ships (phase 14): specs are the source of truth"** — but the site
  shipped. The pointer was wrong.

### C. Build & toolchain stability — **High**

- Root contained `hs_err_pid*.log` (68 KB) and `replay_pid*.log` (1.4 MB) — **JVM crash dumps**. Gitignored
  (good), but their presence signals real instability on the Java 25 toolchain.
- The build has already needed two Windows/JDK-25-specific workarounds (cross-drive manifest-JAR
  `NoClassDefFoundError`; native-thread limit forcing `-T1` over `-T1C`; per-module `forkCount=0`). These
  are papercuts every developer and CI run hits.
- **Risk:** Java 25 + Spring Boot 4.1 is a bleeding-edge combo. If this must run on developer laptops
  org-wide, toolchain friction will dominate adoption complaints. A committed toolchain baseline
  (`.mvn/jvm.config`, documented JDK build) reduces the surface.

### D. Testing depth — **Medium**

- Coverage gate is a flat **0.80 line/branch minimum** — solid, but uniform. Consider raising the floor
  for *api/spi contract* modules (small, critical) and documenting intentional exclusions for wiring-only
  autoconfigure.
- **Docker-tagged tests never run in the default CI push job** — only nightly. A provider regression
  (Kafka, S3, Vault-when-it-exists) is invisible on PRs. Kafka has *no* passing end-to-end path today.
- TCKs exist for messaging/storage/locking/flags/ratelimit — but not for cache, audit, or idempotency
  (which have multiple sinks/providers and would benefit).

### E. Release maturity — **Medium**

- japicmp runs with `ignoreMissingOldVersion=true` (D78) — **no baseline exists yet**, so SemVer breakage
  gating is *theoretically* wired but has never actually diffed anything. The first real train (1.0.0)
  must establish the baseline; until then the gate is a no-op.
- Grafana adoption dashboard ships as importable JSON but the release-notes deep-link is a `TODO` pending
  an instance.

### F. Discovery layer follow-through (Phase 16) — **Medium (high strategic value)**

- The MCP server is stdio-only. For org-wide agent use you'll want a **hosted/HTTP transport** (the
  archetype already writes `.mcp.json` pointing at an endpoint + stdio fallback — close that loop).
- `platform-index.json` completeness is gated against starters, but there's no gate that **usage snippets
  actually compile** against the current API. Snippet rot is the classic failure mode of this pattern.
- Adoption telemetry emits `platform.capability.active` but there's no closing loop (no alert/report on
  *un*-adopted capabilities or on services pinned to N-2 trains).

### G. Minor hygiene — **Low**

- Untracked local clutter: `tmpviol/platform-starter-beta`, `scratch/platform-scratch-api` (enforcer/
  negative-test experiments). Harmless but confusing — fold into a fixtures area or delete.
- CI has a stretch `TODO`: PR path-filtered incremental builds. At 118 modules, full `-T1` verify per PR
  will become the CI bottleneck.

## 4. Improvement roadmap

Four themes, sequenced so each is independently shippable and the reactor stays green throughout.

### Track 1 — Close the truth gaps (Week 1, low effort, high trust) — **DONE with this review**
1. Fix `platform-architecture.md` header (Boot 4.1 / Java 25) + "superseded by D6" note. ✅
2. Reconcile the two decision systems; declare one canonical, cross-link (`docs/decisions/README.md`). ✅
3. Update `README` docs pointer to the live site. ✅
4. Commit a toolchain baseline (`.mvn/jvm.config`) + delete crash logs. ✅
5. Declare **tenancy out-of-scope for 1.0** in the master plan + decision log (D80). ✅

*Outcome: docs match reality; new joiners and agents stop tripping.*

### Track 2 — Finish the golden path (Weeks 2–4, medium)
1. **Reconcile the `secrets` references — do NOT build a capability** (revised 2026-07 after clarifying
   the consuming architecture; see `specs/phase-17-secrets.md`, decision D80). Services consume secrets
   from HashiCorp Vault via **Spring Cloud Vault** as ordinary `${...}` property placeholders, and rotate
   static-KV values by **rolling pod restart** — so property injection is native Spring and there is
   nothing for a platform module to wrap. Instead, re-point the four dangling references at that
   sanctioned pattern: reword the `noSystemGetenv` usage rule (+ its test/fixture/docs), drop the
   deferred `secrets-unresolvable-ref` FailureAnalyzer and the deferred `spring-cloud-vault` ban (Spring
   Cloud Vault is *allowed*, not banned), and confirm the `platform-tck-secrets` omission (D57) stands.
   A POM-only convention starter (`platform-starter-secrets-vault`) is noted for *later, only if*
   per-service Vault config drift becomes a real cost.
2. **Give Kafka a real path:** an `example-event-driven` `-Pkafka` profile + a `@Tag("docker")` Kafka TCK
   run, or explicitly mark the Kafka starter *experimental* in `@API` status and docs until infra supports
   it. Don't ship an untested starter as STABLE.
3. Add TCKs for cache, audit, idempotency.

*Outcome: the "deferred secrets" debt is retired by making the references correct (not by building an
unneeded module); no starter ships without an example and a test.*

### Track 3 — Harden release & CI (Weeks 3–5, medium)
1. Cut **1.0.0** to establish the japicmp baseline, then flip `ignoreMissingOldVersion=false` so the
   SemVer gate becomes real.
2. Add a **PR-time smoke on docker tests for changed capabilities** (path-filtered) so provider
   regressions surface before merge.
3. Implement CI **path-filtered incremental builds** (the phase-02 stretch TODO).

### Track 4 — Mature the discovery/adoption moat (Weeks 4–8, strategic)
1. **Compile-check usage snippets** — DONE (initial). `UsageSnippetCompileTest` (platform-docs) compiles
   every `snippet:<id>`-tagged Java block in `docs/modules/*.md` against the live platform API on the
   test classpath; a renamed API fails the build. Seeded on 4 capabilities (messaging, events, validation,
   redis); the remaining pages' examples are illustrative fragments and adopt the tag incrementally. *Next:
   convert more capability examples into compilable, tagged snippets to raise coverage.*
2. Ship a **hosted MCP transport** and wire the archetype's `.mcp.json` endpoint end-to-end.
3. Close the **adoption loop**: a scheduled report/alert on un-adopted capabilities and services lagging
   on N-2 trains, feeding the Grafana dashboard (and fill the release-notes deep-link).

## 5. Recommended immediate next steps

Highest ratio of trust-per-hour: **Track 1** (a day of doc/toolchain fixes, done here) and the **secrets
reconciliation** (a small `build:`/`docs:` change re-pointing the four dangling references — *not* a new
module; see `specs/phase-17-secrets.md`). With secrets settled as intentionally-out (D50/D80), 1.0.0 can
be cut once the reconciliation lands, so the japicmp baseline locks in the true intended surface.
