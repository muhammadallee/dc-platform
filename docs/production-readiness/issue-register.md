# Issue register

Every finding from any phase (Claude, Codex, harness). Severity per plan §5.1, status per §5.2.
Owner of every entry: **repository owner** (HD10). Gaps found by reading the baseline are tracked in
[`phase-00-baseline/gap-inventory.md`](phase-00-baseline/gap-inventory.md) and become entries here when
a phase confirms them.

| ID | Phase | Severity | Location | Reproduction | Codex claim | Claude reply | Evidence | Status | Mitigation / target | Fix commit | Test |
|---|---|---|---|---|---|---|---|---|---|---|---|
| HI-0001 | 0 | High | `build/platform-parent/pom.xml` | `install -DskipTests` with unpinned failsafe resolving 3.6.0 forks a test JVM; JaCoCo check fails at 0% (CI run 40232447219, generator-gate) | — (found pre-review) | Pinned compiler/surefire/failsafe/resources to Boot's managed versions | CI run 40233736578 generator-gate pass | FIXED | — | `852ac15` | `mvn validate` (0 missing-version warnings); generator-gate |
| HI-0002 | 0 | Medium | `examples/example-golden-path` `StartupBudgetTest` | Wall-clock budget failed under `-T1C` (19–25 s vs 17.25 s) due to runner contention | — | Isolated serial CI step (D88); baseline left at 15 s | CI run 40235234820: isolated step 10.72 s | FIXED | Budget is tight even serially (local 9–19 s): re-check in Phase 8 | `ba8790f` | CI `Startup budget (isolated, serial)` |
