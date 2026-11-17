# Execution state — Spring-native realignment

Read this first on any restart (plan §4.5). Updated at every gate transition.

| Field | Value |
|---|---|
| Plan | [`plan.md`](plan.md) (amended by PR #6: `develop` stream, guided labs, ND11) |
| Baseline | `bdf2845` (`main`), tag `pre-spring-native`; compatibility baseline `~/.m2-native-baseline` (manifest in `phase-00-baseline/evidence/`) |
| Branch | `develop` (long-running; never merged into `main` by this plan, NC1). Phase branch: `native/phase-00-baseline` |
| Current phase | 0 — Baseline, framework spikes, decisions |
| Current gate | Steps 1–7 done. Step 8: **stopped for the owner's answers** to ND1–ND11 ([`decisions.md`](decisions.md)) |
| Last commit | see `git log -1 native/phase-00-baseline` |
| Open blockers | Owner decisions ND1–ND11 |
| Pending human decisions | ND1–ND11 (ND5 rule now, choice after Phase 6; ND9 at Phase 9) |
| Known `BLOCKED` (inherited) | Oracle RAC (HD4); OpenShift (HD7 details) |
| Next action | After the answers: record them in `decisions.md`, run the Phase 0 Codex round over `spikes.md` and `removal-map.md`, merge the Phase 0 PR into `develop` on green, tag `native/phase-00-closed`, start Phase 1 |

## Phase log

| Phase | Start commit | Verdict | Tag | Closed |
|---|---|---|---|---|
| 0 | `6666618` (`develop`) | in progress | — | — |

## Environment notes for resumption

- Every Maven call: `export JAVA_HOME=/c/Users/Ali/.jdks/amazon-corretto-25.0.3.9.1/jdk25.0.3_9; export PATH="$JAVA_HOME/bin:$PATH"`.
- Full builds `-T1`, in the foreground, with `-l <log>`. Write scripts and commit messages to files; no heredocs (plan §4.2).
- Never install or deploy into `~/.m2-baseline` or `~/.m2-native-baseline`.
- Spikes: `docs/spring-native/phase-00-baseline/spikes/` (`mvn -B -ntp test`, online; outside the reactor).
