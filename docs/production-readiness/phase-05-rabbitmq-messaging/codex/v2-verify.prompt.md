# Phase 5 verification pass 2

Static review only. Your pass 1 report is `docs/production-readiness/phase-05-rabbitmq-messaging/codex/v1-verify.out.md`. HEAD's commit
responds to it, by removal rather than more locking: handler routes are built at bean creation and frozen at `start()`, a bean with a
handler created after that fails, two handlers matching one message fail (two beans of one class included), and the registration
lock is never held across a call out of the class.

Review `git show HEAD`, with the code around it:

1. Findings 1, 2, 4, 5, 6: right, partial or wrong?
2. Can `frozen` wrongly refuse something normal: a bean created during start by `serializer.get()`, a handler bean created by a
   collaborator's creation inside `start()`, a context restart, a test using `ApplicationContextRunner`, or a child context? Any path
   where `routes` is read while unfrozen?
3. A verdict on whether Phase 5 can go to its closing runs.

Accepted, do not re-litigate:
- Finding 3 as HI-0093: consumers start before default-phase lifecycle beans. Needed so a drained event finds its queue, documented.
- HI-0071, HI-0079, HI-0086 (parked by the owner), HI-0087, HI-0088, amendments A1 and A2.
- The in-memory transport may drop a requeued message on a full queue, with an ERROR: it is for tests and local runs.

Report concrete defects only, with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
