# Execution state — Spring-native realignment

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Plan | [`plan.md`](plan.md) **v2** (2026-11-19): target 1.0.0, two iterations, seven capabilities, basic auth only |
| Baseline | `bdf2845` (`main`), tag `pre-spring-native`; compatibility baseline `~/.m2-native-baseline` (manifest in `phase-00-baseline/evidence/`) |
| Branch | `develop` (long-running; never merged into `main` by this plan, NC1). Phase branch: `native/phase-00-baseline` |
| Current phase | 0 — Baseline, framework spikes, decisions |
| Current gate | Decisions answered 2026-11-19 ([`decisions.md`](decisions.md)); plan rewritten as v2. Closing: removal map to new scope, Codex round, merge PR #7 |
| Last commit | see `git log -1 native/phase-00-baseline` |
| Open blockers | HI-0109: `develop` push builds hit Maven Central 429s; fixed in Phase 1 |
| Pending human decisions | ND5 (after the Phase 5 trial), ND11b (Phase 9 Gate A). ND1–ND4, ND6–ND15 answered 2026-11-19 |
| Known `BLOCKED` (inherited) | Oracle RAC (HD4); OpenShift (HD7 details) |
| Next action | Phase 0 Codex round (plan §9), merge PR #7 on green, tag `native/phase-00-closed`, start Phase 1 |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `6666618` (`develop`) | in progress | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Full builds `-T1`, in the foreground, with `-l <log>`. Write scripts and commit messages to files; no heredocs (plan §4.2).
- Never install or deploy into `~/.m2-baseline` or `~/.m2-native-baseline`.
- Spikes: `docs/spring-native/phase-00-baseline/spikes/` (`mvn -B -ntp test`, online; outside the reactor).
