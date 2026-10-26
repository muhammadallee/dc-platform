# Execution state

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Baseline commit | `7b248e919a7ffed9b2ae36379c8e9a84105f0b75` (main) |
| Branch | `hardening/v1` |
| Current phase | 0 — Baseline, tooling, decisions |
| Current gate | closing (steps 1–8 recorded) |
| Last commit | see `git log -1 hardening/v1` |
| Open blockers | none |
| Pending human decisions | HD7 chart-tool details (asked at Phase 8 step 7); HD11 outbox approach (asked at Phase 4 Gate A) |
| Known `BLOCKED` | Oracle RAC failover (HD4, no environment) |
| Next action | Phase 1 Gate A: record phase start commit, write `phase-01-core-errors/scope.md`, run current targeted tests for core + errors and record baseline counts |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `7b248e9` | complete (tracking docs, tooling, harness) | — | 2026-10-26 |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Targeted builds only (HC3); never a root test run before Phase 8.
- Consumer runs: `bash tooling/hardening/scenarios/<scenario>.sh` with `HH_MAVEN_REPO` pointing at the
  repository the phase installed into; runs land in `~/dc-hardening-runs/<phase>/`.
- Compatibility baseline repository: `~/.m2-baseline` (baseline `7b248e9` installed by the golden path).
