# Transactional Outbox

**Guaranteed publish alongside a database change.** The event is written to a table in the same transaction as your
business write, so both commit or neither does. A separate drainer then delivers it, at least once. A crash between
the commit and the send loses nothing.

Use it for any publish tied to a database change. `EventPublisher` sends immediately and cannot give that guarantee.

The outbox is a messaging-durability mechanism, not an audit mechanism.

## Add it to a service

1. **Add the starter**, next to a transport starter and your data starter:

   ```xml
   <dependency>
     <groupId>ae.gov.dubaicustoms.platform</groupId>
     <artifactId>platform-starter-messaging-outbox</artifactId>
   </dependency>
   <dependency>
     <groupId>ae.gov.dubaicustoms.platform</groupId>
     <artifactId>platform-starter-messaging-rabbit</artifactId>
   </dependency>
   ```

2. **Add the table to your migrations.** Copy
   `classpath:ae/gov/dubaicustoms/platform/messaging/outbox/platform_outbox.sql` (in `platform-messaging-outbox`) into
   your own sequence, for example `V7__platform_outbox.sql`. With a separate runtime user, also grant it `select, insert,
   update, delete` on the table. The platform never creates the table: a runtime user has no DDL rights, and a migration
   inside a platform jar would collide with your version numbers. If the table is missing, startup fails and names this
   file.

3. **Publish inside your transaction:**

   ```java
   @Transactional
   public void place(Order order) {
       orders.save(order);
       outbox.publish("dc.orders:order.placed", EventEnvelope.of(new OrderPlaced(order.id())).key(order.id()).build());
   }
   ```

No dependency on JPA: it needs a `DataSource`, and joins your transaction under `JpaTransactionManager` or
`DataSourceTransactionManager`.

## What it guarantees, and what it does not

| Guarantee | Detail |
|---|---|
| Atomic with the business write | A rollback leaves no row. `publish` **throws** if no transaction is active on the outbox's `DataSource`, because the row could not commit with your write |
| At-least-once delivery | A kill between a send and its commit repeats the rows of that batch: at most `batch-size` duplicates per kill |
| Stable event id | Generated once, when the envelope is built; the same on every retry, sent as header `eventId` |
| Acknowledged sends | A row is marked sent only after the transport acknowledges it. On RabbitMQ that is a publisher confirm; unroutable or nacked messages fail |

| Not offered | Consequence for you |
|---|---|
| Exactly-once | **Consumers must deduplicate by `eventId`** |
| Ordering | Retries and `SKIP LOCKED` reorder events, even for one key. Consumers must tolerate a later event arriving first |

Stored rows: headers named like a credential (`*token`, `*password`, `authorization`, `*cookie`…) are refused with an
exception. The principal is the subject id only. `last_error` holds the exception class only. **The payload is your
data and is stored as is**, so do not put tokens or personal data in an event you would not want in this table.

## How it works

The drainer runs on one thread, on **its own small connection pool** (`pool-size`, default 2), so a stuck or backlogged
drainer cannot starve request threads. The pool is copied from your Hikari pool, so it has the same URL, credentials,
Oracle driver bounds and `connection-init-sql`.

Each batch is one transaction: claim due rows with `FOR UPDATE SKIP LOCKED`, send each, record each, commit. Row locks
keep other instances off those rows, and the database releases them when a pod dies, so there is no lease and no recovery
job. A long-running send holds its row locks and one drainer connection, not a request thread.

| Failure | What happens |
|---|---|
| Sink down or slow (no confirm in time) | The row is retried with backoff and the drainer pauses (up to `max-backoff`). **It never parks**: recovery delivers the whole backlog |
| Sink rejects the message (unroutable, nacked) | Counts toward `max-rejections`, then the row is `PARKED` with a WARN and a metric. Other rows keep flowing |
| Database down | The batch rolls back and the drainer retries with the same pause |
| Custom transport | Throw `EventPublishException` with `rejected=true` when the sink refuses a message; any other exception is treated as an outage and never parks |

## Parked rows

Parked rows are never deleted automatically. Find out why (`last_error`, the WARN line), fix the cause (usually a missing
queue binding), then replay; the event keeps its id:

```sql
update platform_outbox
   set status = 'PENDING', rejections = 0, next_attempt_at = current_timestamp
 where status = 'PARKED'
   and destination = :destination;
```

## Properties

All under `dc.platform.messaging.outbox`.

| Property | Default | Description |
|---|---|---|
| `enabled` | `true` | `false` publishes after commit, **no longer guaranteed**, and logs a startup WARN saying so |
| `pool-size` | `2` | The most connections the drainer holds, on its own pool |
| `batch-size` | `50` | The most rows per transaction, and so the most duplicates a kill can cause |
| `poll-interval` | `1s` | The pause when nothing is due |
| `max-rejections` | `10` | Rejections by the sink before a row is parked; outages do not count |
| `initial-backoff` | `1s` | First per-row retry delay; doubles per attempt |
| `max-backoff` | `5m` | Longest per-row retry delay and longest pause after a failure |
| `sent-retention` | `24h` | How long a sent row is kept; deleted in bounded batches |
| `statement-timeout` | `30s` | The timeout on each drainer statement |
| `gauge-interval` | `5s` | How often the backlog metrics refresh (on the drainer's thread, never on scrape) |
| `retention-interval` | `5m` | How often sent rows are deleted |
| `stop-timeout` | `30s` | How long a shutdown waits for the batch in flight |

## Metrics

| Meter | Meaning |
|---|---|
| `platform.outbox.pending` | Rows waiting to be sent |
| `platform.outbox.oldest.pending.age` | Age in seconds of the oldest waiting row: the drain lag. **Alert on this** |
| `platform.outbox.parked` | Parked rows. **Alert on anything above 0** |
| `platform.outbox.sent`, `.failed`, `.parked.total` | Counters. No tags, so no event ids or destinations in labels |
| `hikaricp_*{pool="platform-outbox"}` | The drainer's own pool |

## Replace / disable

- Define your own `OutboxPublisher`, `OutboxContext`, `OutboxDrainer` or `outboxCapabilityDescriptor` bean to replace
  the default.
- `dc.platform.messaging.outbox.enabled=false` keeps application code working, with publishes after commit. A crash
  between the commit and the send then loses the event.
