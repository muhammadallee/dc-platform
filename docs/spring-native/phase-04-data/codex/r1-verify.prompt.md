# Codex verification — Spring-native Phase 4, round 1

Your reassessment (`codex/r1-answer.out.md`) of `9ad0b15` found findings 2 and 3 partly resolved, and two new Medium
issues. They are addressed at **`769e513`**: `git diff 9ad0b15..769e513 -- logging/`. Review read-only. Logging runs
191 tests; the full reactor `verify` passes.

The rule now (D142 reassessment note, `docs/modules/logging.md`):
- **Opaque first, on every path.** In `mask()`, `sanitizePair()`, the walk and map keys, the masker checks for an
  entity, a Hibernate proxy, an unloaded Hibernate collection or a JDK proxy (`opaque()`) before any text shortcut. Such
  a value is a placeholder.
- **Scalar shortcuts trust JDK classes only** (`jdkClass()`). An application subclass of `Number` or `Path` is walked
  like any object. An application `Throwable` is `[Type]`. An enum is its `name()`.
- **Stated limit:** any other `CharSequence` is its own text, by that type's contract, as an application object with no
  bean properties is.

| Item | Change | Test (`JpaValueMaskingTest`) |
|---|---|---|
| 2 (rest) | A JDK proxy that is a `CharSequence` or a `Path` is opaque, on every path | `textLikeProxiesAndApplicationScalarsNeverRenderAProxy` |
| 3 (rest) | An application `Number` or `Throwable` holding an unloaded reference | same test: no statement, not initialised, for each of `sanitizePair`, `mask` and the nested walk |
| New 1 | A `Map.Entry` accessor that throws costs only that entry | `aThrowingEntryCostsOnlyItselfAndFloatingKeysArePlain` |
| New 2 | `Float` and `Double` keys are plain | same test |

Answer **resolved**, **partly resolved** or **not resolved** for each. List **every** new issue this diff introduces, in
one answer, with severity, file and line, a failure scenario and a fix. Say "none" if none.
