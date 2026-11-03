# Phase 7 verification pass 3

Static review only. Your pass 2 output is `codex/v2-verify.out.md`. The fixes are `git diff abe70ec HEAD`: `RequiresPermissionPointcut` and `RequiresPermissionInterceptor` resolve the most specific method (`AopUtils.getMostSpecificMethod`) before the interface method, then the target class, then the declaring class; `AnonymousAccessProbe` counts a null (abstaining) decision as open. Tests: `RequiresPermissionAdvisorFactoryTest` (JDK proxy cases), `FilterChainFailClosedTest.anAbstainingRuleForEverythingFailsStartup`.

1. Confirm each fix or show it wrong with a trigger.
2. Hunt regressions in those three classes only: a CGLIB proxy, a bridge or generic method, a method annotated on the interface and the class annotated differently, a null target class, and whether the platform's own chain (permit paths, the authenticated fallback, the actuator rules) can now yield a null decision for a probe path and fail a correctly configured application at startup.

Residuals HI-0100 to HI-0103 stay Low as you agreed. Report concrete defects only, each with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
