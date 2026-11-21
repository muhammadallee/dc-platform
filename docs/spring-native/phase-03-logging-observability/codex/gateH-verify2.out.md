| Item | Verdict |
|---|---|
| Inherited Hibernate interface | **resolved** — `implementsAny` follows parent interfaces and superclasses before getter/container traversal. |
| `UrlResource.getInputStream()` | **resolved** — its `InputStream` return type implements `AutoCloseable`, so the getter is excluded. Other properties remain eligible. |
| HI-0166 overall | **resolved** for the resource/proxy traversal defect. One separate masking regression is introduced below. |

**New issue: High — filtering every getter can expose credentials through the bean’s `toString()`.**

File: [PlatformValueMasker.java:647](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p3gh2-1718/logging/platform-logging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/logging/autoconfigure/internal/PlatformValueMasker.java:647), interacting with the empty-getter fallback at lines 479–481.

**Failure scenario:** An application bean has only `InputStream getSecret()` and a `toString()` returning an unlabelled credential, such as `"hunter2"`. Logged under a noncredential key:

- Before: the bean becomes `{"secret":"****"}`.
- After: filtering removes its only getter; `object()` falls back to `toString()`, producing `"hunter2"`. Pattern masking cannot identify an arbitrary unlabelled password.

This also makes previously unused bean `toString()` implementations execute.

**Fix:** Preserve property discovery separately from invocation eligibility. Represent excluded resource-valued properties as masked placeholders without invoking them, or omit them while retaining the bean’s map representation—even when empty. Add a regression with a sole resource-valued credential getter and an unlabelled secret in `toString()`.

No other new issues found. Read-only review of `c4ddb44..e401e02`; diff whitespace checks passed. I did not rerun the reported 180 tests.
