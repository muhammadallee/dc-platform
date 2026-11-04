# Phase 7 verification pass 1

Static review only. Round 2 findings and their dispositions are in `docs/production-readiness/phase-07-security/review-round-2.md`; your round 2 output is `codex/r2-review.out.md`. The fixes are `git diff fa863c5 HEAD`.

1. For each round 2 finding marked fixed, confirm it or show it wrong with a trigger. Say plainly where you disagree with a "partly" disposition.
2. Hunt regressions the fixes introduced: `AnonymousAccessProbe` with every method, the servlet context, and failing startup on a throwing matcher (false failures on normal Boot setups: actuator `EndpointRequest`, `PathRequest.toStaticResources()`, dispatcher-type matchers, MVC matchers, a `SecurityCustomizer` from springdoc or another library); outage tolerance as TTL plus tolerance; null claims removed from `CurrentUser.claims()` (who relies on them); escaping non-ASCII in problem bodies; the log line.
3. Anything else concrete that would make a request fail open, leak a token, or return a 500.

Parked: HI-0071, HI-0079, HI-0086. Accepted: HI-0078, HI-0087, HI-0088, HI-0093, HI-0094 to HI-0098. Report concrete defects only, each with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
