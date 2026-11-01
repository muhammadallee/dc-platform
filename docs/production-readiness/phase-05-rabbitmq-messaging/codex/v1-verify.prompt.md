# Phase 5 verification pass 1

You are reviewing an internal Spring Boot 4.1 / Spring 7 platform. Static review only: no JVM or Docker in your sandbox.
Dispositions of your round 2 report: `docs/production-readiness/phase-05-rabbitmq-messaging/review-round-2.md`. Review HEAD's
commit (`git show HEAD`), with the code around it.

1. For D1–D8 and the golden-path example: right, partial or wrong? Be specific.
2. Attack what they introduced:
   - The registrar's lock: `transport.subscribe` now runs under it. Can that deadlock with bean creation, a connection listener or a
     handler registering a bean? `getPhase` = `DEFAULT_PHASE - 8192`: consumers start before the web server and default-phase beans.
     What breaks? What is the shutdown budget in total?
   - `setForceStop(true)`: can it lose a message, or redeliver one a handler already finished? Interaction with `shutdown-timeout`.
   - The example: `V2__platform_outbox.sql` on H2, the in-memory transport and the drainer in its tests, flakiness.
3. A verdict on whether Phase 5 can go to its closing runs.

Parked by the owner, do not report: HI-0071, HI-0079, HI-0086. Accepted as residuals, do not re-litigate: HI-0087 (delivery limit
counts a stopping consumer's returns), HI-0088 (main queue deleted during a replay), amendments A1 and A2.

Report concrete defects only, with file, line, trigger, consequence and severity. End with `VERDICT: ACCEPT` or `VERDICT: REJECT`.
