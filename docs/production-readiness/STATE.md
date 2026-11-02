# Execution state

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Baseline commit | `7b248e919a7ffed9b2ae36379c8e9a84105f0b75` (main) |
| Branch | `hardening/v1` |
| Current phase | 7 — Security |
| Current gate | Phase 7 Gate A |
| Last commit | see `git log -1 hardening/v1` |
| Open blockers | none (owner decided A1, A3, HI-0010) |
| Pending human decisions | Review HI-0088 again at the end of all phases, before the Phase 8 release decision (D125); HD7 chart-tool details (asked at Phase 8 step 7) |
| Known `BLOCKED` | Oracle RAC failover (HD4, no environment) |
| Next action | Phase 7 Gate A, with composition checkpoint C2 and the fix for HI-0099 (D131). Phase 6 consumer repository: `bash tooling/hardening/phase-06-repo.sh`; Codex has a usage limit, so a failed run may just need waiting. Parked for a later fix, before production use: HI-0071, HI-0079, HI-0086. Consumer runs go in the foreground. `nice_galileo` is stopped. Disk: D: under 1 GB free, not from this work; golden path dependencies copied to `~/gp-p5-m2`, use `GP_MAVEN_REPO`, `GP_WORKDIR`, `GP_EVIDENCE` on C:. |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `7b248e9` | complete (tracking docs, tooling, harness) | `hardening/phase-00-closed` | 2026-10-26 |
| 1 | `f231689` | **PASS** (final `76cfcc7`; consumer 3× 92/92, golden path 8/8, CI green) | `hardening/phase-01-closed` | 2026-10-26 |
| 2 | `a33168d` | **PASS** (final code `5b8e820`; consumer 3× 75/75, Phase 1 regression 92/92, golden path 8/8, CI green; residuals accepted) | `hardening/phase-02-closed` | 2026-10-28 |
| 3 | `60afe86` | **PASS** (final code `29c6ed2`; Oracle consumer 3× 92/92, Phase 1 92/92, Phase 2 75/75, golden path 9/9, CI green; residuals accepted, HI-0071 → Phase 4) | `hardening/phase-03-closed` | 2026-10-31 |
| 4 | `39e354d` | **PASS with parked issues** (final code `03dee13`; consumer 3× 106/106, Phases 1-3 92/75/92, golden path 9/9; HI-0078 accepted; HI-0071, HI-0079, HI-0086 parked) | `hardening/phase-04-closed` | 2026-11-01 |
| 5 | `dfb3524` | **PASS** (final code `5cafcf3`; consumer 3× 63/63, Phases 1-4 92/75/92/106, C1, golden path 9/9, CI green; HI-0087, HI-0093 accepted; HI-0088 accepted, review at the end of all phases) | `hardening/phase-05-closed` | 2026-11-02 |
| 6 | `1162d99` | **PASS** (final code `78636c0`; consumer 3× 182/182, Phases 1, 2 92/75, golden path 9/9, CI green; HI-0094 to HI-0098 accepted, HI-0099 to Phase 7) | `hardening/phase-06-closed` | 2026-11-02 |

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
