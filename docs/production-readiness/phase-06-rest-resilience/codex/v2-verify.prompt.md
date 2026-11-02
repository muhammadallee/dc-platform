# Phase 6 verification pass 2: REST client and resilience

Static review only: no JVM or Docker in your sandbox. HEAD holds fixes for verification pass 1. Read the last section of
`docs/production-readiness/phase-06-rest-resilience/review-round-2.md` for what was claimed, and `codex/v1-verify.out.md` for what you found. Compare with
`git diff 6741388 HEAD`, reading the surrounding code.

1. For each fix and test gap in that section: right, partial or wrong, with file and line if not right.
2. Any defect these changes introduce, with a concrete trigger. In particular: `report` swallowing; the `RuntimeException` branch of the send, including whether something
   already sent could reach it, and what it does to the breaker's half-open permission; `XML_ELEMENT`'s greed and time, and what it destroys in a body with several
   elements; dropping any message that holds `://`, `?` or `#`; `synchronized (transports)` around `computeIfAbsent` and against `destroy`; `closed` no longer volatile.
3. Anything left in this phase that would lose a call's outcome, send a non-idempotent call twice, call a possibly applied call `retryable`, wedge a client permanently,
   hang a caller past the deadline, or put a credential in a log, span, metric or exception.

Do not re-report what `review-round-2.md` accepts with a reason, unless the reason is wrong. Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Accepted, do
not report: HI-0078, HI-0087, HI-0088, HI-0093. Report concrete defects only, each with file, line, trigger, consequence and severity. A theoretical concern with no reachable
trigger is not a defect. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
