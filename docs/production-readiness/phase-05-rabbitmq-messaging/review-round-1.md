# Phase 5 — Review round 1 (Gate C)

| Item | Value |
|---|---|
| Reviewed commit | `4aa0ee4` (from `dfb3524`; isolated worktree) |
| Verbatim I/O | [`codex/r1-review.prompt.md`](codex/r1-review.prompt.md) → [`codex/r1-review.out.md`](codex/r1-review.out.md) |
| Codex scope note | Static review, reading Spring and RabbitMQ sources. Fixes verified locally on a real broker |
| Verdict | REJECT, 18 findings |

The consumer run had passed 63 of 63 on this code. Every finding below is a path that run did not exercise.

| # | Finding | Severity | Claude | Disposition |
|---|---|---|---|---|
| 1 | A handler's own requeue or immediate-ack exception decided the outcome after retries | High | Agree | FIXED: `DeadLetterException` carries no cause, and the transport turns it into a reject |
| 2 | Spring's default error handler acknowledges a fatal failure on a message with `x-death`: a replayed message is gone for good | High | Agree | FIXED: `setDiscardFatalsWithXDeath(false)`; real-broker test. A key header of the wrong type no longer fails delivery |
| 3 | Quorum dead-lettering defaults to at-most-once | High | Agree | FIXED: `x-dead-letter-strategy=at-least-once` with `x-overflow=reject-publish`. Classic queues stay at-most-once, documented |
| 4 | Two filtered handlers on one destination compete on one queue, and the wrong one acks (pre-existing) | High | Agree: real loss | FIXED: one subscription per destination, routed by type. Two handlers that could receive the same message fail startup |
| 5 | Rolling restarts can use up a healthy message's delivery limit | High | Agree on the mechanism, Medium in effect | MITIGATED: limit raised from 5 to 20, documented. Residual HI-0087 |
| 6 | Sequential close: requeue storm, and `N × shutdown-timeout` | High | Agree | FIXED: subscriptions close together |
| 7 | Connection-cache mode recurses through the topology callback | High | Agree | FIXED: re-entrancy guard; real-broker test |
| 8 | One failed redeclaration stops the others | High | Agree | FIXED: each on its own, logged |
| 9 | Only connection refusal counted as an outage at startup | High | Agree | FIXED: any broker failure except its refusal of the declaration (406) |
| 10 | Consumers started before refresh ended; a restart restored nothing | High | Agree | FIXED: subscribing moved to `start()`; restart resubscribes |
| 11 | `backoff` above `max-backoff` ignored the cap; the doc's "under 4 s" ignored handler time | Medium | Agree | FIXED, doc corrected |
| 12 | Retries reused a payload the handler may have changed | Medium | Agree: a regression from my change | FIXED: deserialized per attempt |
| 13 | An unparsable version counted as 1; generic payloads skipped the check | Medium | Agree | FIXED: dead-lettered; the raw class is checked |
| 14 | `@ConditionalOnBean(EventSerializer)` lost the publisher against a later configuration | Medium | Agree | FIXED: resolved at the first publish |
| 15 | A thread-reading mandatory expression could still pass the caller's check | Medium | Agree | FIXED: checked on the send thread, closing HI-0082's remaining path |
| 16 | In-memory treated a requeue as a failure | Medium | Agree | FIXED: no attempt used |
| 17 | The replay script did not encode queue names or credentials | Medium | Agree | FIXED: rewritten in Python with proper encoding, credentials in a header |
| 18 | Replay ignored the vhost; a queue deleted mid-replay loses messages | High | Vhost agreed; deletion accepted as an operator action | FIXED: vhost in the URIs, both queues checked first. Deletion documented, residual HI-0088 |
