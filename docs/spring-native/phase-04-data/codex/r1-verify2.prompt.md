# Codex verification 2 — Spring-native Phase 4, round 1

Your verification (`codex/r1-verify.out.md`) of `769e513` left 3 (rest) and new 1 partly resolved, and found a new
Medium issue. They are addressed at **`b362fe3`**: `git diff 769e513..b362fe3 -- logging/`. Review read-only.
Logging runs 191 tests; the full reactor `verify` passes.

| Item | Change | Test (`JpaValueMaskingTest`) |
|---|---|---|
| 3 (rest) | `jdkClass()` holds only for a class the bootstrap or platform class loader defined; `jdkValue()` uses it too | `textLikeProxiesAndApplicationScalarsNeverRenderAProxy` with `javax.example.LookalikeAmount` (test sources) |
| New 1 (rest) | Entry accessors run under the getters' guard (`fetch`, the same failure policy as `read` and `guarded`) | `aThrowingEntryCostsOnlyItselfAndFloatingKeysArePlain`, an `AssertionError` from `getKey()` |
| New (budget) | Each entry is charged before it is read; the extra charge for a non-plain key is gone | same test, an endless map of failing entries |

Answer **resolved**, **partly resolved** or **not resolved** for each. List **every** new issue this diff introduces, in
one answer, with severity, file and line, a failure scenario and a fix. Say "none" if none.
