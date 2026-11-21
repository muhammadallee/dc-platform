# Codex verification 3 — Spring-native Phase 3, round 2

Your verification 2 (`codex/r2-verify2.out.md`) found new 4 and 6 (rest) partly resolved, with two new issues. Both are
addressed at **`1f69255`**: `git diff 7b4aec8..1f69255`. Review read-only. Logging runs 178 tests, all passing.

| Item | Change | Test (`StructuredLogMaskingTest`) |
|---|---|---|
| New 1 (High) | An integral number is checked as plain digits first; a match masks the whole value. Only then is the written form checked | `aNumberIsCheckedAsWrittenAndAsDigits` (`E14$` over `784199012345671d`) |
| New 2 (Medium) | Context entries are removed and re-inserted on each occurrence, so of two keys written under one name the latest occurrence wins | `twoContextKeysWrittenUnderOneNameKeepTheLater` (`foo`, `_foo`, `foo` under `ctx`) |

Answer **resolved**, **partly resolved** or **not resolved** for each, and list **every** new issue this diff introduces,
in one answer, with severity, file and line, a failure scenario and a fix. Say "none" if none.
