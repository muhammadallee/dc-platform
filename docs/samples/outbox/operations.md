# Operations

## Posture

| Situation | Behaviour | Verified |
|---|---|---|
| RabbitMQ down | Requests unaffected. Rows accumulate, lag rises, the drainer pauses (up to `max-backoff`). Nothing parks. Recovery delivers all | CONSUMER |
| RabbitMQ slow (past the 5 s confirm timeout) | Requests unaffected. Rows retry; a timed-out send that arrives anyway repeats under the same id | CONSUMER |
| Message rejected (unroutable, no such exchange) | Parked after `max-rejections`, with a WARN and a metric. Other rows keep flowing | CONSUMER |
| Database down | Requests get `503 DC-DATA-0503` (Phase 3). The batch rolls back; the drainer keeps running and retries. Backlog metrics go stale, and `gauges.age` says so. Recovery needs no restart; at most one batch (50) repeats | CONSUMER |
| Database slow | Requests take longer, within their bounds. The drainer stays on its own connections | CONSUMER |
| Pod killed (`SIGKILL`) | The database releases its locks. Other instances or a restart deliver everything; at most one batch (50) repeats | CONSUMER |
| `SIGTERM` | The batch ends after the send in flight and commits; the rest stay due; exit 143 | CONSUMER |
| Several instances | `SKIP LOCKED` keeps them off each other's rows; work is shared | CONSUMER |

## What to watch

| Signal | Alert when | Means |
|---|---|---|
| `platform_outbox_oldest_pending_age_seconds` | above a few minutes | events are not going out: broker down or slow, or the drainer stopped |
| `platform_outbox_parked` | above 0 | the broker refused events: usually a missing queue binding. Replay below |
| `platform_outbox_drainer_running` | 0 | the drainer's thread is gone; restart the pod |
| `platform_outbox_gauges_age_seconds` | above a minute | the figures above are stale: usually the database |
| `platform_outbox_pending` | growing | backlog; compare with the sent rate |
| `platform_outbox_sent_total`, `_failed_total`, `_parkings_total` | | throughput and failures |
| `hikaricp_connections_active{pool="platform-outbox"}` | | the drainer's own pool, capped at `pool-size` |

The meters carry no event ids or destinations. Find a parked event in the WARN line or `platform_outbox.last_error`.

## Replaying parked rows

Fix the cause first (usually the queue binding). The event keeps its id:

```sql
update platform_outbox
   set status = 'PENDING', rejections = 0, next_attempt_at = current_timestamp
 where status = 'PARKED'
   and destination = :destination;
commit;
```

Parked rows are never deleted automatically. To discard one, delete it deliberately and record why.

## Sizing

| Setting | Default | Consider |
|---|---|---|
| `batch-size` | 50 | Lower it to bound duplicates after a kill; raise it for throughput |
| `pool-size` | 2 | Rarely needs changing: one for batches, one for metrics and retention |
| `sent-retention` | 24 h | Rows kept for inquiry; shorter means a smaller table |
| Pod grace period | | Above one send's time (5 s); `stop-timeout` is 30 s |

Throughput is one batch at a time per instance, each send waiting for a broker confirm. Instances add throughput.

## Kill switch and replacement

- **Tier `DEFAULT_ON`.** `dc.platform.messaging.outbox.enabled=false` logs a startup WARN that publishes are no longer
  guaranteed, publishes after commit, and runs no drainer. Rows already in the table stay until it is on again. Break-glass only.
- **Replace** by defining a bean: `OutboxPublisher`, `OutboxContext`, `OutboxDrainer`, `OutboxConnections`, or
  `outboxCapabilityDescriptor` by name.

## Health

The outbox and the broker are not in `readiness` or `liveness`: a broker outage must not take the service out of rotation,
since requests still work and events wait in the table.

## Graceful shutdown

Web server first, then the drainer: it finishes the send in flight and commits. Then the transport and pools close. Verified
with `SIGTERM` during a batch: exit 143, nothing lost, nothing repeated.
