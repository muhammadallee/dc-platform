# Phase 2 — Closure

**Verdict: PASS** (2026-11-20). Branch `native/phase-02-foundation`
(PR #9), base `516f54c`. Core and errors run on Spring types: a service throws `ErrorResponseException` with its own
`code`, adds to every error body with an `ErrorResponse.Interceptor`, and reads the trace from `Tracer`. No platform type
remains in service code.

## The gates

| Gate | Result |
|---|---|
| A | `scope.md`: baseline at `516f54c` (core-api 37, core-autoconfigure 58, errors-api 7, errors-autoconfigure 78, no skips); decisions D138 (interceptor design proven by a spike, service code as a `ProblemDetail` property, descriptor move, `Tracer`, exception subclasses, floor 77) |
| B | Three modules removed, every call site moved; D139 (per-build japicmp off, ArchUnit whitelist, a task decorator that clears missing values). Before review Claude found and fixed four defects: a context-free task seeing another caller's span (N1), Boot's problem-details advice taking over (N4), a thrown body decorated in place (N5), a missing `RestClient.Builder` in generated services |
| C | Codex round 1: 1 Critical (agreed High), 2 High, 8 Medium/Low; all fixed ([`review-round-1.md`](review-round-1.md)) |
| D | Codex round 2: no Critical or High; seven Medium/Low findings fixed ([`review-round-2.md`](review-round-2.md)). The error-code gate took 15 verification passes. It now lets javac resolve every name, accepts a code only where it traces to source literals, and pins `DataAccessFailure` to its audited shape (D140). Verification 15 accepted it at `2737a57`: no escape, no new defect |
| E | Issue register HI-0119 to HI-0141. The gate commits after the consumer runs change only `ErrorCodeSourceUniquenessTest`, so the runs below still hold; the errors module passes 106 tests |
| F | Consumer run, three in a row: **89 assertions each, 0 failed**, floor 77, fresh repository with no removed module ([evidence](evidence/)) |
| G | Not this phase (Phases 6 and 9) |
| H | Module pages core and errors, book chapters 1 and 2 rewritten; sample `docs/samples/core-errors` rewritten; lab `first-service` passes `tooling/labs/run-lab.sh` (new); clean full `install`, one run, exit 0; `mkdocs --strict` and `DocsLinkCheckTest` pass |

## Carry-over

Every row of [`scope.md`](scope.md) §6 holds at the final commit with its named test: C1–C29 carried, R1–R2 retired to
Micrometer's `TraceContext` and the code gate, N1–N5 mitigated. Removed types and their Spring replacements are listed
in `CHANGELOG.md` and [`../expected-removals.md`](../expected-removals.md).

## Carried forward

| Item | Where |
|---|---|
| Other capabilities' book and sample pages still describing removed types (bannered) | HI-0110: each capability phase |
| A reused exception holding a mutable value other than a map or list shares it between responses | Documented limit, errors page |
| Messaging carries the trace in its own header | Phase 6 (Spring AMQP observation) |
| Per-build japicmp off | D139: the iteration release checks (Phases 7, 10) compare against `pre-spring-native` |
| HI-0109 (`develop` push builds) | Closes after three green `develop` push builds in a row |
