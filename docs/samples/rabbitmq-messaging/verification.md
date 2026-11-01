# Verification

Levels: UNIT, SLICE, CONTAINER (real RabbitMQ), CONSUMER (archetype-generated service in `prod`, real Oracle and RabbitMQ). Final code
`5cafcf3`: consumer three runs **63/63** each; Phases 1–4 consumers 92, 75, 92, 106 of the same totals; golden path 9/9.

| Claim | Expected | Actual | Level |
|---|---|---|---|
| Retries | exactly `max-attempts`, then the DLQ | 3 attempts (9 before the fix), main queue empty | CONTAINER + CONSUMER |
| Trace | one trace from request to handler | same trace id through the outbox and directly | CONSUMER |
| Unreadable payload | no attempt, DLQ, others flow, not logged | as expected | SLICE + CONSUMER |
| Newer version | no attempt, DLQ | as expected | SLICE + CONSUMER |
| Unknown field | tolerated | as expected | SLICE + CONSUMER |
| Replay | DLQ inspected in place, replayed under the same id | as expected | CONSUMER |
| Replayed message failing fatally | back to the DLQ, not lost | as expected | CONTAINER |
| Duplicates | naive applies twice, the pattern once | 2 and 1 | CONSUMER |
| Broker down | direct publish fails; outbox absorbs; consumer reconnects | as expected | CONSUMER |
| Broker down at startup | ready; consumes when it arrives | as expected | CONSUMER |
| Broker lost its definitions | redeclared on reconnect | as expected | CONTAINER |
| `SIGTERM` | in-flight finishes, exit 143, nothing dead-lettered, rest delivered after restart | 2 finished, 6 of 6 in the end, no duplicates | CONSUMER |
| Quorum default | main and DLQ | as expected; classic through the property | CONTAINER + CONSUMER |
| Delivery limit | dead-letters at the limit | by requeue, standing in for a killed consumer | CONTAINER |
| Credential header | refused, nothing sent | as expected | SLICE + CONSUMER |
| Optional jars | starts without Micrometer, tracing, Jackson | as expected | SLICE |

Not verified: quorum failover across nodes (one broker node); a real consumer-killing message; replay after an upgrade end to end; a sustained load test.
