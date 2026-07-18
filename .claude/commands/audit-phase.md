---
description: Self-audit a completed phase against the module checklist and constitution
---
Phase number: $ARGUMENTS

Audit the just-completed phase $ARGUMENTS. Make NO code changes unless a checklist violation is found.
1. Re-read specs/phase-$ARGUMENTS-*.md and specs/reference/module-checklist.md.
2. For every module the phase created, verify each checklist item and print a table:
   module | in root <modules> | in platform-bom | docs page | CHANGELOG | matrix tests | javadoc policy | PASS/FAIL.
3. Run `mvn -T1C verify` and the phase Acceptance block once more; paste raw output.
4. Diff implemented public API/SPI signatures against the spec's; list ANY deviation.
5. If violations exist: fix the smallest possible way, commit as `fix(phase-$ARGUMENTS): audit findings`, re-run.
6. Output a final verdict line: "PHASE $ARGUMENTS AUDIT: PASS" or "...: FAIL (reasons)".
