# Phase 6 verification pass 1: REST client and resilience

Static review only: no JVM or Docker in your sandbox. HEAD holds fixes for round 2. Read
`docs/production-readiness/phase-06-rest-resilience/review-round-2.md` for what was claimed, and `codex/r2-review.out.md` for what you found. Compare with
`git diff 471db62 HEAD`, reading the surrounding code.

1. For D1 to D9 and each scenario gap: right, partial or wrong, with file and line if not right.
2. Any defect these changes introduce, with a concrete trigger. In particular: reading `conf/net.properties` at construction (missing file, unreadable,
   a jlink image, a whitespace value); the XML patterns' time and what they now destroy; the `.{0,8192}?` CDATA repeat; the identity set of registries holding a
   strong reference; several listeners, each counted; `created[]` inside `computeIfAbsent`; the slow-call threshold's effect on Resilience4j's metrics;
   the new harness steps' race conditions.
3. Anything still wrong anywhere in this phase that would lose a call's outcome, send a non-idempotent call twice, call a possibly applied call `retryable`, wedge a
   client permanently, hang a caller past the deadline, or put a credential in a log, span, metric or exception. Do not re-report what `review-round-2.md` accepts
   with a reason, unless the reason is wrong.

Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Accepted, do not report: HI-0078, HI-0087, HI-0088, HI-0093. Report concrete defects only, each with
file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
