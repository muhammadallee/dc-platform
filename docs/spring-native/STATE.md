# Execution state — Spring-native realignment

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Plan | [`plan.md`](plan.md) **v2** (2026-11-19): target 1.0.0, two iterations, seven capabilities, basic auth only |
| Baseline | `bdf2845` (`main`), tag `pre-spring-native`; compatibility baseline `~/.m2-native-baseline` (manifest in `phase-00-baseline/evidence/`) |
| Branch | `develop` (long-running; never merged into `main` by this plan, NC1). Phase branch: `native/phase-03-logging-observability` |
| Current phase | 3 — Logging + observability (Phase 2 closed 2026-11-20) |
| Current gate | Phase 3 Gate D: round 2 fixes at `8c3efef`; Codex reassessment pending (usage limit until 14:37). Gates A, B, C done; Gate E register HI-0142..HI-0160; Gate H docs and lab written |
| Last commit | see `git log -1 native/phase-03-logging-observability` |
| Open blockers | Codex usage limit until 2026-11-21 14:37; Docker unresponsive since the disk filled (owner to restart) |
| Pending human decisions | ND5 (after the Phase 5 trial), ND11b (Phase 9 Gate A). ND1–ND4, ND6–ND15 answered 2026-11-19 |
| Known `BLOCKED` (inherited) | Oracle RAC (HD4); OpenShift (HD7 details) |
| Next action | Round 2 reassessment of `8c3efef`; Gate F three consumer runs (floor 65; a trial run gave 69/69); re-run both labs and the Phase 2 core-errors run; closure |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `6666618` (`develop`) | PASS | `native/phase-00-closed` | 2026-11-19 |
| 1 | `085d752` (`develop`) | PASS | `native/phase-01-closed` | 2026-11-19 |
| 2 | `516f54c` (`develop`) | PASS | `native/phase-02-closed` | 2026-11-20 |
| 3 | `a0f58d5` (`develop`) | in progress | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Full builds `-T1`, in the foreground, with `-l <log>`. Write scripts and commit messages to files; no heredocs (plan §4.2).
- Never install or deploy into `~/.m2-baseline` or `~/.m2-native-baseline`.
- Spikes: `docs/spring-native/phase-00-baseline/spikes/` (`mvn -B -ntp test`, online; outside the reactor).
