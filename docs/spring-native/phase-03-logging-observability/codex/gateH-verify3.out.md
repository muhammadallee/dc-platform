**Resolved.** The diff `e401e02..6613528` restores resource-valued getters to property discovery and masks them before invocation. A bean with only `InputStream getSecret()` therefore remains `{"secret":"****"}` without calling the getter or falling back to `toString()`.

The regression test covers both the sole-getter case and the existing resource bean, asserting zero getter calls.

**New issues introduced: none found.**

Read-only review completed; `git diff --check` passed. I did not rerun the reported 180 tests.
