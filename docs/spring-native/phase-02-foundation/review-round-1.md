# Phase 2 — Codex round 1

Gate C (plan §8). Base `516f54c`, reviewed `c7d0bcf`.
- Review prompt: [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md); Codex's answer, verbatim: [`codex/r1-review.out.md`](codex/r1-review.out.md).
- Claude's answers and Codex's reassessment: [`codex/r1-answer.prompt.md`](codex/r1-answer.prompt.md),
  [`codex/r1-answer.out.md`](codex/r1-answer.out.md).

Codex: "I would not approve `c7d0bcf` yet." It found no defect in C1–C5, C10–C11, C15, C17–C18, C20–C22, C27 or C29,
none in the optional-dependency wiring (tracing, MVC, validation absent), and no platform import in the probe or the
archetype's production code.

Before the round, Claude had already found and fixed two defects (`c7d0bcf`): Boot's problem-details advice taking over
when a service turns it on (N4), and the advice decorating a thrown exception's own body (N5).

| # | Severity | Finding | Claude | Reassessed | Fix |
|---|---|---|---|---|---|
| 1 | Critical → **High** (agreed) | A reused exception's body is localised in place before the copy, and nested values were shared, so concurrent responses could mix locales or an interceptor's per-request writes | Partly agree: the race is real; Critical needs a service to reuse an exception with a mutable value | Accept with change: keep `ResponseStatusException`'s reason localisation; test nested values and locales concurrently | The delegate localises a copy the way Spring does (type, detail, title codes; a `ResponseStatusException`'s reason as a code with itself as fallback); `copyOf` copies map and list values deeply; the page says other values must be immutable |
| 2 | High | The body's status overrode the exception's: a 503 with a 400 body was treated as a 400, keeping an internal detail | Agree | Accept | The response's status is set on the copy before anything else |
| 3 | High | Extensions named `detail`, `status`, `stacktrace`, `traceId` survived, so the JSON could hold a second `detail` with an internal message | Agree | Accept; the code stays the selected service code | Reserved names are removed before the platform writes its fields; tests count keys in the raw JSON |
| 4 | Medium | `AsyncRequestNotUsableException` ("write nothing") became a 500, and the committed-response check was lost | Agree | Accept with change: cover the validation and unreadable-body paths too | `map` tells "not Spring's" from "write nothing"; the response is passed in; every path goes through Spring's committed check; a committed response is never rendered |
| 5 | Medium | A service code with a space or `%` broke `URI.create` | Agree | Accept; add a non-ASCII case | The code is percent-encoded in `type`, raw in `code` and `title` |
| 6 | Medium | The outbox's restored trace was written straight to the MDC, so the context accessor no longer captured it | Agree | Accept with change: applying a snapshot must also hide trace keys written outside platform scopes | The accessor reads the MDC's own `traceId`/`spanId`; an isolating scope hides and restores them too |
| 7 | Medium | C7's out-of-order close was not implemented (copied from the old code) | Agree | Accept; prove a stale handle cannot disturb newer work | Closing an outer scope unwinds inner scopes newest first; a closed or discarded handle does nothing |
| 8 | Medium | The code gate saw only `DC-` literals: a dynamic or malformed code passed | Partly agree: a general data-flow check is out of proportion | **Reject:** cover the finite code-bearing entry points and validate every resolved value | The gate checks every `DC`-like literal and every code-bearing entry point (`setProperty` with a `code` key resolved by value, `factory.create`/`decorate`, `respond`, `DataAccessFailure`), resolving literals, conditionals, constants and locals; four audited forwarding paths are listed with their reason; negative fixtures for each case |
| 9 | Medium | The error page caught only `RuntimeException` from interceptors | Agree | Accept | Catches `Throwable` as Spring does, logs WARN; tested with an `AssertionError` on both paths |
| 10 | Medium | Replacing the correlation filter or a capability descriptor needed a platform type | Partly agree: descriptors are not a service path | **Reject** for descriptors: a real replacement must exist | The filter backs off to any bean named `platformCorrelationFilterRegistration`; `dc.platform.core.capabilities.<name>.status`/`.detail` replace or add what the banner, `/actuator/platform` and the gauge report. Both tested with no platform type |
| 11 | Low | The core-errors sample and `extending.md` still teach removed types | Agree | Accept; open until the sample is rewritten | `extending.md` fixed now (its snippet compiled); the sample is rewritten at Gate H |

**Verification after the fixes:** `mvn -o -T1 -pl core/platform-core-autoconfigure,errors/platform-errors-autoconfigure,observability/platform-observability-autoconfigure,messaging/platform-messaging-outbox-autoconfigure clean install`
→ core 73, errors 103, observability 37, outbox 26 tests, no failures, no skips
(`evidence-raw/gateC-r1-fixes-2.log`, `-3`, `-4`). Round 2 reviews the result from the phase start commit.
