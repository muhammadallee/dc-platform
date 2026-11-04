# Phase 7 review round 2: security

You are reviewing an internal Spring Boot 4.1 / Spring 7 / Spring Security 7.1 platform. Static review only: no JVM or Docker in your sandbox.
Review the code at HEAD against the phase start: `git diff c7373c6 HEAD`, reading the surrounding code. The scope is `docs/production-readiness/phase-07-security/scope.md`.
Round 1 and its dispositions are in `docs/production-readiness/phase-07-security/review-round-1.md`.

1. For each round 1 finding marked fixed, confirm the fix or show it wrong, with a trigger. Say plainly where you disagree with a "partly" or "residual" disposition.
2. Hunt regressions in the fixes: `AnonymousAccessProbe` (false positives on a normal configuration, false negatives, a matcher that throws, method-specific or dispatcher rules, whether the proxy request is realistic, whether a thrown probe is silently a pass that matters); the virtual-thread `FutureTask` in `CountingRetriever` (cancellation, interrupt on a blocking socket, thread leak, request-thread interrupt); `requireKeys`; the filter on RSA size; `lastFailed`; `NoRedirects` (does `openHTTPConnection` get used for https; does the retriever treat a 3xx as a failure); `Binder.get` on the environment; `UnquotedReadFailures` catching `RuntimeException` (what it must not swallow) and wrapping after customizers; the removed advisor back-off and §7.1; `ProblemResponses`' writer fallback with an unset content length; `ifUnique`.
3. Tests that would pass with the defect still present, or that do not test what they are named for. Requirements in the scope with no test.
4. Anything new: fail-open paths, token leaks, a 500 reachable from a request, docs that say something the code does not do, in `docs/modules/security.md`, `authz.md`, `restclient.md`, the archetype's README and `CHANGELOG.md`.

Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Accepted, do not report: HI-0078, HI-0087, HI-0088, HI-0093, HI-0094 to HI-0098. Report concrete defects only, each with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
