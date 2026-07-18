---
description: Verify baseline and produce an implementation plan for a platform phase (no code)
---
Phase number: $ARGUMENTS

You are executing one phase of the platform build. Follow CLAUDE.md at all times.

1. Read specs/00-MASTER-PLAN.md, specs/runbooks/claude-code-handoff.md, and the matching
   spec file specs/phase-$ARGUMENTS-*.md (glob it). If the phase spec does not exist, STOP and say so.
2. Verify the baseline: run `mvn -T1C verify`. If it is not green, STOP, show the failure,
   and do NOT plan — the previous phase must be fixed first.
3. Confirm `git status` is clean. If not, STOP and list what is uncommitted.
4. Produce a detailed plan and NOTHING else — no code, no file edits:
   - modules to create, in dependency order (matching the spec's module list)
   - files per module (main, test, resources)
   - which spec signatures/POM fragments are used verbatim vs. what you must design
   - deviations you anticipate from the spec, if any, with justification
   - risks and how the phase's Acceptance block will prove completion
5. End with: "Awaiting approval. Reply 'approved' to implement, or correct the plan."
Do not begin implementation in this session turn under any circumstances.
