# Execution state — Spring-native realignment

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Plan | [`plan.md`](plan.md) **v2** (2026-11-19): target 1.0.0, two iterations, seven capabilities, basic auth only |
| Baseline | `bdf2845` (`main`), tag `pre-spring-native`; compatibility baseline `~/.m2-native-baseline` (manifest in `phase-00-baseline/evidence/`) |
| Branch | `develop` (long-running; never merged into `main` by this plan, NC1). Phase branch: `native/phase-02-foundation`, PR #9 |
| Current phase | 2 — Foundation: core + errors |
| Current gate | Gate C done (`review-round-1.md`, fixes `b3bcc7c`). Gate D (Codex round 2) waits for the Codex reset at 18:37; Gate F consumer run in progress meanwhile |
| Last commit | see `git log -1 native/phase-00-baseline` |
| Open blockers | Codex usage limit (2026-11-19 16:19): round 2 waits for the reset at 18:37 (plan §5.3: wait, never buy credits) |
| Pending human decisions | ND5 (after the Phase 5 trial), ND11b (Phase 9 Gate A). ND1–ND4, ND6–ND15 answered 2026-11-19 |
| Known `BLOCKED` (inherited) | Oracle RAC (HD4); OpenShift (HD7 details) |
| Next action | After 18:37: run `phase-02-foundation/codex/r2-review.prompt.md` against the latest commit (update its reviewed commit first). Meanwhile: consumer run (`native-repo.sh native-02`, floor 77), Gate E entries, lab and sample |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `6666618` (`develop`) | PASS | `native/phase-00-closed` | 2026-11-19 |
| 1 | `085d752` (`develop`) | PASS | `native/phase-01-closed` | 2026-11-19 |
| 2 | `516f54c` (`develop`) | in progress | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Full builds `-T1`, in the foreground, with `-l <log>`. Write scripts and commit messages to files; no heredocs (plan §4.2).
- Never install or deploy into `~/.m2-baseline` or `~/.m2-native-baseline`.
- Spikes: `docs/spring-native/phase-00-baseline/spikes/` (`mvn -B -ntp test`, online; outside the reactor).
