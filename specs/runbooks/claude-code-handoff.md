# Runbook — Handing Off to Claude Code, Phase by Phase

Claude Code reads `CLAUDE.md` at the repo root automatically at session start; every phase spec in
`specs/` ends with an executable Acceptance block. This runbook is the human-side procedure that
wraps those two mechanisms.

## One-time setup

1. Replace placeholders: `acme` / `com.acme.platform` across the tree; set the real Spring Boot 3.x
   version in BOTH places (aggregator `<spring-boot.version>` and `platform-service-parent`'s `<parent>`).
2. `git init && git add -A && git commit -m "chore: phase 1 foundation"`.
3. Run `mvn -T1C verify` YOURSELF once — never let an agent start from an unverified baseline
   (you don't want to debug "was it broken before, or did the agent break it").
4. Open the repo in Claude Code (`cd acme-platform && claude`). Grant `mvn`, `git`, and file-edit
   permissions when prompted.

## Per-phase handoff

- **One session per phase; `/clear` between phases.** `CLAUDE.md` + the specs carry all continuity;
  a fresh context per phase outperforms one long session.
- **Prompt template:**

  ```
  Read specs/00-MASTER-PLAN.md and specs/phase-0N-<name>.md.
  First verify the previous phase: run `mvn -T1C verify` and confirm green.
  Then implement Phase N exactly per the spec, module by module, following
  the working loop in CLAUDE.md. Signatures in the spec are contracts.
  When done, run the phase's Acceptance block and show me the output.
  Commit as you go with conventional commits.
  ```

- **Plan first for size M+:** start in plan mode (Shift+Tab, or "make a plan first, don't write
  code yet"), review/correct the plan, then let it execute. Five minutes on a plan saves an hour
  unwinding a wrong turn.
- **Slice the big phases (7–11):** hand off one capability — or one provider — at a time:
  "implement the messaging slice from phase-07 up to and including the inmemory provider; stop
  before kafka."
- **Correct by citation, not argument:** quote the spec — "phase-03 defines RequestContext.open as
  @PlatformInternal — revert your public version." The specs are in-repo so the agent can re-read
  the authority.

## Testing — three layers

1. **Acceptance blocks (per phase).** After the agent reports done, RE-RUN the phase's Acceptance
   commands in your own terminal. Never accept "acceptance passed" without reproducing it — agents
   occasionally report intent rather than result.
2. **Gates-as-code (continuous).** From phase 2 onward, a green `mvn -T1C verify` already proves the
   dependency constitution, ArchUnit rules, coverage, checkstyle, and (after the first tag) japicmp
   binary compatibility. Also verify the gates FIRE: on a scratch branch, seed a forbidden
   starter→starter dependency and confirm the build fails with the right message — a gate that
   never fails might be a gate that never runs.
3. **Human review where it's irreversible.** Read every `-api` / `-spi` file by hand BEFORE its
   autoconfigure module is built (the spec signatures make this a diff-against-spec exercise). Skim
   autoconfigure header comment blocks. Check the module-checklist items agents most often skip:
   platform-bom entry, docs/modules page, CHANGELOG.

## Git as the safety net

- One branch per phase; merge to main only when the Acceptance block passes on YOUR machine.
- Tag milestones per the master plan: `0.1.0` after phase 4, `0.2.0` after phase 13, `1.0.0-RC1`
  after phase 15.
- Session went sideways? `git checkout . && git clean -fd`, restart the phase with a sharper
  prompt — cheaper than repair.

## Docker-tagged tests

The default loop never needs Docker. On reaching kafka/rabbit/redis/postgres/vault/s3 work
(phases 7–10), start Docker and add one instruction: "also run
`mvn -Pdocker -pl <module> -am verify` and fix failures." Run these deliberately, not always-on —
keep the fast untagged suite as the inner loop.

## The one-command health check

- After phase 13: `./tooling/scripts/golden-path.sh` — generates a service from the archetype,
  builds, boots, and probes it; exercises the whole stack end to end.
- Before phase 13: root `mvn -T1C verify` + the current phase's Acceptance block.

## The repeatable per-phase loop (slash commands)

The prompts above are baked into the repo as Claude Code slash commands (`.claude/commands/`),
so every phase is the same five steps:

```
/clear                                  # fresh context per phase
/plan-phase 03                          # baseline check + plan, no code
   (review the plan; correct it; reply: approved)
/implement-phase 03                     # executes, runs Acceptance, pastes raw output, commits
/audit-phase 03                         # optional self-audit vs module checklist; PASS/FAIL verdict
```
Then in YOUR terminal (not the agent's):
```
./tooling/scripts/verify-phase.sh 03    # reproduces objective checks + prints the manual
                                        # Acceptance steps for you to run yourself
git merge / tag per the milestone plan  # 0.1.0 after 04, 0.2.0 after 13, 1.0.0-RC1 after 15
```
Big phases (07–11): same loop, but scope the argument in prose after the command, e.g.
`/plan-phase 07` then "limit this plan to the messaging slice through the inmemory provider".
If verify-phase fails: do not merge; `/clear`, re-open with `/plan-phase N` and paste the failure.
