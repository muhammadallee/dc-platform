# Execution state

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Baseline commit | `7b248e919a7ffed9b2ae36379c8e9a84105f0b75` (main) |
| Branch | `hardening/v1` |
| Current phase | 2 — Logging + Observability |
| Current gate | D in progress: round 2 + verification pass 1 done, fixes in `f5ce14c`/`8334836`; verification pass 2 pending (prompt `codex/v3-verify.prompt.md`, worktree `$TEMP/hd/codex-p2v2` at `f5ce14c`). Gate E register entries HI-0041..46 written. Gate F scenario drafted (uncommitted), trial 2: 73/75 |
| Last commit | see `git log -1 hardening/v1` |
| Open blockers | none (owner decided A1, A3, HI-0010) |
| Pending human decisions | HD7 chart-tool details (asked at Phase 8 step 7); HD11 outbox approach (asked at Phase 4 Gate A) |
| Known `BLOCKED` | Oracle RAC failover (HD4, no environment) |
| Next action | Re-run Codex verification pass 2 (stopped by memory pressure 2026-10-27 06:55), then Gate F trial 3 (`HH_MAVEN_REPO=~/.m2-hardening/phase-02`, repo refreshed at `f5ce14c`); confirm CI on `8334836`; write `review-round-2.md`. |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `7b248e9` | complete (tracking docs, tooling, harness) | `hardening/phase-00-closed` | 2026-10-26 |
| 1 | `f231689` | **PASS** (final `76cfcc7`; consumer 3× 92/92, golden path 8/8, CI green) | `hardening/phase-01-closed` | 2026-10-26 |
| 2 | `a33168d` | in progress (Gate D) | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Targeted builds only (HC3); never a root test run before Phase 8.
- Consumer runs: `bash tooling/hardening/scenarios/<scenario>.sh` with `HH_MAVEN_REPO` pointing at the
  repository the phase installed into; runs land in `~/dc-hardening-runs/<phase>/`.
- Compatibility baseline repository: `~/.m2-baseline` (baseline `7b248e9` installed by the golden path).
  **Never install into it.** Its platform artifacts are pinned by
  `phase-00-baseline/evidence/m2-baseline-manifest.sha256` (230 files). Phase consumer runs use
  `bash tooling/hardening/phase-repo.sh <phase> <modules>`, which verifies the manifest, copies the
  baseline to `~/.m2-hardening/<phase>` and installs the phase's modules on top.
- CI runs on every push via draft PR #2 (not mergeable until Phase 8; HC6 does not apply).
- Gate F composition conflict (recorded 2026-10-26): a feature-less archetype app already includes
  core, errors, logging, observability, openapi, security and validation starters (archetype baseline).
  Resolution: these are documented **fixtures** in Phases 1–7 (not certified by the phase unless it is
  their phase); the generated POM is not edited. Kill switches are used only where a fixture would
  interfere with the scenario, and each use is recorded in the phase evidence.
