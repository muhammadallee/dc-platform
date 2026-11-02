# Phase 6 review round 2: REST client and resilience

You are reviewing an internal Spring Boot 4.1 / Spring 7 platform. Static review only: no JVM or Docker in your sandbox.
Review the whole change from `1162d99` to HEAD (`git diff 1162d99 HEAD`), with the surrounding code. Read first:
`docs/production-readiness/phase-06-rest-resilience/scope.md` (requirements and acceptance criteria) and `review-round-1.md`, which holds
each round 1 finding and what was done. Round 1's own text is `codex/r1-review.out.md`.

Do three things.

**A. Each round 1 finding:** right, partial or wrong, with file and line if not right. Do not take the disposition's word for it.

**B. Regressions and defects the round 1 fixes introduced.** In particular:

1. `closeWithoutDraining`: closing the body stream before `response.close()`. Connection reuse on the normal path; a response whose body was never
   asked for (`toBodilessEntity`, a 204, HEAD); a `getBody()` that throws; what `IntrospectingClientHttpResponse` and its `PushbackInputStream` do on
   an empty body; `getBody()` called again after close; a redirect response.
2. Two transports per client name: any path where a method reaches the wrong one; the cost; a customizer's view. A POST that now returns a 3xx to its
   caller as success: is that acceptable to `retrieve()`, or does it surprise?
3. The deferred breaker verdict in `GuardedResponse`: permissions held until close; a response never closed; half-open under concurrency; a body read
   in another thread; a 4xx whose verdict arrives through the status handler's read; `recordStatus(started, 200)` for a non-2xx; `verdictPending` on a retry.
4. The clock in `readFailure` with `TIMER_MARGIN_NANOS`: wrong kinds near expiry; a lost connection reported as `DEADLINE` and so `retryable`; for a POST.
5. `sanitized`: losing a cause's type for `classify` (it classifies first?); a `null` message; what the regexes miss; a message with a URL fragment.
6. Redaction patterns: `HEADER_LINE`, `SCHEME`, `XML_VALUE`, `SECRET_VALUE`, their order and interactions, a `replaceAll` where a value holds `$` or `\`, things
   it destroys that a developer needs (for example ordinary prose containing "basic"), `StackOverflowError` paths, and time on 8 KiB.
7. The deadline starting at `createRequest`: an interceptor that runs long before the call; the request being created once but executed by retrying
   interceptors; `RestClient` reusing it.
8. `destroy` and `closed`: a call in flight at shutdown; the shared wait; `HttpClient.shutdownNow` on an idle client; ordering against the web server's drain
   and messaging consumers; a builder obtained before close and used after.
9. `ignore-exceptions` holding `RestClientException`: a raw `RestClient` user's `@Retry` changing behaviour, the `@Retry` on an instance with its own config,
   the property index if the class is absent; `metrics once` and a Boot composite registry; the thread-pool guard's relaxed names.
10. The observation convention: `requestUri`/`uri` override: `none` or a template; template variables in the path; what else a query can reach.

**C. Scope gaps.** Each requirement R1 to R11 and contract §7.1 to §7.11: anything not met, or not tested at the level the scope states. Read the consumer scenario
`tooling/hardening/scenarios/phase-06-rest.sh` and `phase-06/probe/RestProbe.java`, and say which acceptance criterion it does not actually prove.

Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Accepted, do not report: HI-0078, HI-0087, HI-0088, HI-0093. Report concrete defects only, each with
file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
