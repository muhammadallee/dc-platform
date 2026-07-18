---
description: Implement an approved platform phase per its spec, then run its Acceptance block
---
Phase number: $ARGUMENTS

Implement phase $ARGUMENTS per specs/phase-$ARGUMENTS-*.md, following the approved plan from
this session (if no plan was approved in this session, STOP and tell the user to run
/plan-phase $ARGUMENTS first).

Rules of execution:
1. Follow CLAUDE.md and the working loop exactly: one module at a time, in dependency order.
2. Signatures, POM fragments, package names, and property keys in the spec are CONTRACTS —
   implement them exactly. Internals are yours.
3. After EACH module: `mvn -T1C -pl <module> -am verify`, then root `mvn -T1C verify`.
   Never proceed with a red reactor.
4. Per-module Definition of Done: specs/reference/module-checklist.md — including platform-bom
   entry, docs/modules page, CHANGELOG entry. Do not defer these.
5. Commit per module with conventional commits (feat(cap): ...). Do not squash.
6. If the spec is silent, do what spring-boot-autoconfigure does; note "Decision: ..." in the
   commit body and append one line to docs/decisions/decision-log.md.
7. When all modules are done: run the phase's "## Acceptance" block from the spec, top to bottom,
   and paste the RAW command output (not a summary). If any step fails, fix and re-run.
8. Finish with: files changed summary, commits made, acceptance output, and any decisions logged.
   Then STOP. Do NOT begin the next phase.
