# Phase 7 verification pass 2

Static review only. Your pass 1 output is `codex/v1-verify.out.md`. The fixes are `git diff 84a66ae HEAD`. Dispositions: pass 1 F1 is documented as a bound of one extra cache TTL (scope R2.3, `security.md`); F2 fixed; F3 fixed with a fuller stand-in request plus an explicit, logged `skip-open-path-check`; F4 fixed by requiring two depths both open; F5, F6, F8 and the test gaps are residuals HI-0102, HI-0101, HI-0100, HI-0103 in `docs/production-readiness/issue-register.md`; F7 fixed.

1. Confirm each fix or show it wrong with a trigger. Say plainly if you think a residual is in fact Medium or worse, and why.
2. Hunt regressions in `AnonymousAccessProbe` (the two-depth rule letting a real everything-open rule through; the new request methods), `RequiresPermissionInterceptor` (target class lookup, a JDK proxy, `getThis()` null, the new deny), and the docs.
3. Anything else concrete that would make a request fail open, leak a token, or return a 500.

Parked: HI-0071, HI-0079, HI-0086. Accepted: HI-0078, HI-0087, HI-0088, HI-0093, HI-0094 to HI-0098. Report concrete defects only, each with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
