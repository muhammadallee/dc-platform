# Codex verification 2 — Spring-native Phase 1

Your first verification (`docs/spring-native/phase-01-scope-cut/codex/r1-verify.out.md`) accepted findings 1, 3, 5 and 6,
left 2 and 4 open, and found two new defects. The fixes are in `61b23b1..e82edcf` on branch `native/phase-01-scope-cut`
(skip `docs/llms-full.txt`). Review read-only.

Answer **ACCEPTED** or **NOT RESOLVED** (with file, line and what is still wrong) for each:

1. **Finding 2** — `phase-00-smoke.sh` and the other usage lines; every live command page uses `native-repo.sh`.
2. **Finding 4** — `docs/samples/outbox/commands.md`, `docs/runbooks/local-dev.md`,
   `docs/book/crosscutting/local-vs-production.md`, `docs/concepts/conventions.md`, and any other live page that still
   gives an instruction for a cut capability without a "not yet updated" banner. Out of scope: `docs/production-readiness/**`,
   `docs/spring-native/**`, `docs/decisions/**`, `docs/reference/*`, and dated records (`docs/chassis-audit.md`,
   `docs/reviews/**`, `docs/upgrade/**`). A page that only *mentions* a cut capability (for example "Kafka was removed")
   is not an instruction.
3. **New defect 1** — `native-repo.sh` path traversal.
4. **New defect 2** — a failed repository build no longer lets a scenario run against `~/.m2/repository`.

Then: did these fixes introduce a new defect? Say "none" if none.
