# Execution state — Spring-native realignment

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Plan | [`plan.md`](plan.md) **v2** (2026-11-19): target 1.0.0, two iterations, seven capabilities, basic auth only |
| Baseline | `bdf2845` (`main`), tag `pre-spring-native`; compatibility baseline `~/.m2-native-baseline` (manifest in `phase-00-baseline/evidence/`) |
| Branch | `develop` (long-running; never merged into `main` by this plan, NC1). Phase branch: `native/phase-04-data` |
| Current phase | 4 — Data (Phase 3 closed 2026-11-21) |
| Current gate | Phase 4 Gate B: HI-0167 fixed at `6c3b238` (review and consumer proof pending); HI-0168 next |
| Last commit | see `git log -1 native/phase-04-data` |
| Open blockers | none; HI-0168 (Medium) is open, fixed in Phase 4 Gate B |
| Pending human decisions | ND5 (after the Phase 5 trial), ND11b (Phase 9 Gate A). ND1–ND4, ND6–ND15 answered 2026-11-19 |
| Known `BLOCKED` (inherited) | Oracle RAC (HD4); OpenShift (HD7 details) |
| Next action | HI-0168, failing test first (auditor backs off by type); then the zero-config test (AC20) and the harness import check (AC17) |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `6666618` (`develop`) | PASS | `native/phase-00-closed` | 2026-11-19 |
| 1 | `085d752` (`develop`) | PASS | `native/phase-01-closed` | 2026-11-19 |
| 2 | `516f54c` (`develop`) | PASS | `native/phase-02-closed` | 2026-11-20 |
| 3 | `a0f58d5` (`develop`) | PASS | `native/phase-03-closed` | 2026-11-21 |
| 4 | `e9f9b80` (`develop`) | in progress | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Full builds `-T1`, in the foreground, with `-l <log>`. Write scripts and commit messages to files; no heredocs (plan §4.2).
- Never install or deploy into `~/.m2-baseline` or `~/.m2-native-baseline`.
- Spikes: `docs/spring-native/phase-00-baseline/spikes/` (`mvn -B -ntp test`, online; outside the reactor).
- Run folders cleaned 2026-11-22 (owner approved deleting old runs). Ten superseded folders in `~/dc-hardening-runs` were
  deleted. One was cited: `phase-01/core-errors-20261104-114003` (v3 Phase 8 regression). It was deleted after a faulty
  citation check; its committed `assertions.tsv` and `run-identity.txt` under
  `docs/production-readiness/phase-08-final/evidence/d133-regressions/phase-01/` remain, its raw logs are lost. Before
  deleting a run, look for its timestamp in file names and contents, dashed and compact.
