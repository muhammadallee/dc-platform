| Item | Assessment | Reason |
|---|---|---|
| 3 (rest): application scalars | **resolved** | `jdkClass()` checks the defining loader, so application classes in JDK-looking packages no longer qualify. `jdkValue()` uses the same check for subclasses. |
| New 1 (rest): throwing entry accessors | **resolved** | Both accessors run through `fetch()`, which contains nonfatal failures—including `AssertionError`—locally and follows `guarded()`’s fatal-error policy. |
| New (budget): failing entries | **resolved** | Every entry consumes budget before accessor invocation, including failed entries. The map loop therefore terminates; the former non-plain-key charge was removed. |

**New issues introduced by this diff: none.**

Read-only source review of `769e513..b362fe3`; I did not independently rerun the reported 191 tests or reactor verification.
