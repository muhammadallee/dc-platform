# Execution state — Spring-native realignment

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Plan | [`plan.md`](plan.md) **v2** (2026-11-19): target 1.0.0, two iterations, seven capabilities, basic auth only |
| Baseline | `bdf2845` (`main`), tag `pre-spring-native`; compatibility baseline `~/.m2-native-baseline` (manifest in `phase-00-baseline/evidence/`) |
| Branch | `develop` (long-running; never merged into `main` by this plan, NC1). Phase branch: `native/phase-01-scope-cut`, PR #8 |
| Current phase | 1 — Scope cut |
| Current gate | Gate H: Codex round 1 settled (verification 4 accepted finding 4, no new defects); five findings fixed, one rejected with Codex's agreement. `closure.md` PASS |
| Last commit | see `git log -1 native/phase-00-baseline` |
| Open blockers | None |
| Pending human decisions | ND5 (after the Phase 5 trial), ND11b (Phase 9 Gate A). ND1–ND4, ND6–ND15 answered 2026-11-19 |
| Known `BLOCKED` (inherited) | Oracle RAC (HD4); OpenShift (HD7 details) |
| Next action | Merge PR #8 on green CI, tag `native/phase-01-closed`, post the end-of-phase report, start Phase 2 (plan §10, foundation: core + errors) |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `6666618` (`develop`) | PASS | `native/phase-00-closed` | 2026-11-19 |
| 1 | `085d752` (`develop`) | in progress | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Full builds `-T1`, in the foreground, with `-l <log>`. Write scripts and commit messages to files; no heredocs (plan §4.2).
- Never install or deploy into `~/.m2-baseline` or `~/.m2-native-baseline`.
- Spikes: `docs/spring-native/phase-00-baseline/spikes/` (`mvn -B -ntp test`, online; outside the reactor).
