# Codex verification 3 — Spring-native Phase 1, finding 4 only

Your first verification (`docs/spring-native/phase-01-scope-cut/codex/r1-verify2.out.md`) accepted everything
except finding 4. The fixes are in `e82edcf..a468d31` on branch `native/phase-01-scope-cut`
(skip `docs/llms-full.txt`). Review read-only.

Your last answer named `01-core.md:688`, `08-data.md:456`, `extension-model.md:23` and `observability.md:62`. Answer
**ACCEPTED** or **NOT RESOLVED** for finding 4 as a whole: does any live page still give an instruction for a cut capability
without a "not yet updated" banner? Out of scope: `docs/production-readiness/**`, `docs/spring-native/**`,
`docs/decisions/**`, `docs/reference/*`, dated records (`docs/chassis-audit.md`, `docs/reviews/**`, `docs/upgrade/**`), and
pages of parked capabilities that carry the "Coming in iteration 2" banner. A page that only *mentions* a cut capability is
not an instruction. List every remaining one in one answer, with file and line.

Then: did these fixes introduce a new defect? Say "none" if none.
