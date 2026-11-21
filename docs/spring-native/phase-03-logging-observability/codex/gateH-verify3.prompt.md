# Codex verification 3 — Spring-native Phase 3, Gate H fix (HI-0166)

Your verification 2 (`codex/gateH-verify2.out.md`) resolved HI-0166 and found one new High issue. A bean whose only
getter returns a resource lost that getter and fell back to its `toString()`. The fix is at **`6613528`**:
`git diff e401e02..6613528 -- logging/`. Review read-only. Logging runs 180 tests.

- **Discovery.** Resource-valued getters are discovered as properties again.
- **Invocation.** `object()` writes such a property as `****` without invoking it, so the bean stays a map.
- **Test.** `aGetterReturningAResourceIsNeverCalled`: a `SecretHolder` with only `InputStream getSecret()` and a
  `toString()` of `"hunter2"` is written as `{"secret":"****"}`, and no getter is called.

Answer **resolved**, **partly resolved** or **not resolved**, and list **every** new issue this diff introduces, in one
answer, with severity, file and line, a failure scenario and a fix. Say "none" if none.
