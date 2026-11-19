# Codex review, round 2 — Spring-native Phase 2 (foundation: core + errors)

Review read-only. **Base commit:** `516f54c` (start of Phase 2). **Reviewed commit:** `c209736`
(branch `native/phase-02-foundation`).

Round 1 reviewed `c7d0bcf`. Your findings and the answers are in `docs/spring-native/phase-02-foundation/codex/`
(`r1-review.out.md`, `r1-answer.prompt.md`, `r1-answer.out.md`) and summarised in
`docs/spring-native/phase-02-foundation/review-round-1.md`. The fixes are `c7d0bcf..b3bcc7c`. After them,
`b3bcc7c..c209736` adds the rewritten core-errors sample, the first-service lab and its runner (`tooling/labs/`), the
consumer-run evidence, the regenerated reference and the issue-register entries HI-0119 to HI-0134.

## What to do

1. **Confirm round 1.** For each of the eleven findings, is the fix at `c209736` correct and complete, and does the
   named test prove it? Answer **RESOLVED** or **NOT RESOLVED** (with file, line and what is still wrong). Finding 11
   includes the rewritten `docs/samples/core-errors/**`.
2. **Hunt for regressions** in the whole change `516f54c..c209736`, with the fixes in mind. In particular:
   - the localised copy in `FrameworkErrors.localisedCopy` against Spring 7.0.10's own `ErrorResponse` and
     `ResponseStatusException` behaviour, and every exception type `ResponseEntityExceptionHandler` maps;
   - the reserved-name removal and status normalisation in `ProblemDetailFactory.decorate` and
     `PlatformExceptionHandler.handle`: any body the platform builds itself that now loses a field it needs;
   - the committed-response and "write nothing" paths, including the constraint-violation advice;
   - `MdcContext` (isolation of directly written trace keys, out-of-order close) and
     `MdcContextThreadLocalAccessor` (capture from the MDC): bleed, leaks of `ThreadLocal` state, restore order with
     Micrometer's own observation accessor;
   - `CapabilityOverrides` and the correlation filter's name-based back-off;
   - the rewritten `ErrorCodeSourceUniquenessTest`: a way a platform code can still escape it;
   - the lab runner (`tooling/labs/run-lab.sh`, `lab_script.py`) and `docs/labs/first-service/index.md`: a check that
     cannot fail, a step that can pass on a wrong result, or cleanup that misses what the lab started.
3. **The carry-over table** in `docs/spring-native/phase-02-foundation/scope.md` §6 is still the requirement: is any row
   not true at `c209736`, or its proof not a test that exists and proves it?

Skip `docs/reference/*`, `docs/llms*.txt`, `tooling/platform-skill/skill/**` and
`docs/spring-native/phase-02-foundation/evidence/**`. You may read any file in the repository and Spring's jars in
`~/.m2`.

## Output

Part 1: one line per round-1 finding. Parts 2 and 3: each finding with severity (plan §6), file and line, a concrete
failure scenario, reasoning and a proposed fix. Say explicitly when you find nothing.
