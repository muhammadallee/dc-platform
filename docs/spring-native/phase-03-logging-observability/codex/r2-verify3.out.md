Reviewed `7b4aec8..1f69255` read-only.

| Item | Verdict | Assessment |
|---|---|---|
| New 1 (High) | **resolved** | Plain-digit matching now takes precedence and masks the entire numeric value. Written-form matching still runs when digits do not match. The added test covers the reported `E14$` bypass. |
| New 2 (Medium) | **resolved** | Removing and reinserting raw keys preserves latest-occurrence order before written-name collisions are resolved. The added three-entry test covers the reported failure. |

New issues introduced by this diff: **none**.

I did not rerun the reported 178 passing tests.
