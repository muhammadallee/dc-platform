# Phase 5 review round 2: RabbitMQ messaging

You are reviewing an internal Spring Boot 4.1 / Spring 7 platform. Static review only: no JVM or Docker in your sandbox.
Scope: `docs/production-readiness/phase-05-rabbitmq-messaging/scope.md`. Round 1 and its dispositions:
`docs/production-readiness/phase-05-rabbitmq-messaging/review-round-1.md`, with your own report in `codex/r1-review.out.md`.

Review the whole of `git diff dfb3524 HEAD`, as it now stands, with the surrounding code.

1. For each of the 18 findings, say whether the fix is right, partial or wrong. Be specific.
2. Attack what the fixes introduced:
   - `EventHandlerRegistrar`: routing shared subscriptions; a late-registered handler while running; `start`/`stop` races with
     registration; `stopping` swap on restart; parallel close on virtual threads; whether a handler declared after `start` on an
     existing destination is seen; a `@Scope` or lazily-created bean with a handler; the ignored outcome.
   - Two handlers previously allowed on one destination now fail startup. Which existing platform paths declare that?
     (Search the repo, including `events`, `audit`, the archetype and the examples.)
   - `SmartLifecycle` start order: is anything in the platform relying on subscriptions existing before start, such as tests or
     the in-memory transport's publish?
   - `x-overflow=reject-publish` on a quorum queue, and what publishers see; at-least-once dead-lettering with a missing DLQ.
   - `RabbitEventTransport`: the mandatory check inside the carried send; precondition detection; the error handler with
     `AmqpRejectAndDontRequeueException` and a fatal exception; a redeclare guard across threads.
   - `tooling/scripts/rabbit-dlq-replay.py`.
3. Anything still open from the scope's requirements R1–R9 and AC1–AC17.

Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Report concrete defects only, each with file, line, trigger,
consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`. Say so plainly if you find nothing in an area.
