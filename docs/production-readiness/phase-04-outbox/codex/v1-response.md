# Phase 4 — implementer response to `v1-verify.out.md`

Verified commit `922ab13`.

| Item | Response |
|---|---|
| Unpooled source loses schema and catalog (High) | **Agree. Fixed.** Copied, with a test |
| Registrar cycle, collaborators resolved on a delivery thread (Medium) | **Agree. Fixed.** Subscriptions wait for `afterSingletonsInstantiated`, which resolves the collaborators first. A startup failure stays a startup failure |
| R2-4 `close()` does not reach blocked senders | **Agree. Fixed:** interrupted on close; the cap stands |
| R2-8 sub-millisecond read timeout | **Agree. Fixed** |
| `REQUIRED` in an `afterCommit` callback loses a dispatch; disabled publish inside `REQUIRES_NEW` there is premature (High) | **Partly agree, Medium, registered as HI-0086.** Spring documents the first as misuse. Both are on paths with no guarantee, and the outbox itself refuses loudly. A frame cannot tell the two apart; a complete fix instruments the transaction manager, which is the HI-0071 decision, so they go to the owner together |
| R2-7 maintenance overlap across a restart | **No change.** The loop checks its own identity, so a late thread exits; an overlap of one iteration is harmless |
| R2-3, R2-5 | Accepted by Codex as Medium residuals (HI-0079, HI-0082) |
