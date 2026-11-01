# Transactional outbox — sample

## What it solves

A service saves an order and must tell other services about it. If it publishes after the commit, a crash in between loses
the event with no record it existed. If it publishes before, a rollback announces something that never happened.

The outbox writes the event **in the same transaction as the business write**. A separate drainer then delivers it, at least
once. Measured on real Oracle and RabbitMQ: killing a pod mid-drain loses nothing.

## When to use / not use

- **Use** it for any publish tied to a database change.
- **Do not** use it for a publish with no database write: `EventPublisher` is simpler.
- **Do not** assume exactly-once or ordering. **Consumers must deduplicate by `eventId`** and tolerate a later event arriving
  first.
- **Do not** use it as an audit trail: sent rows are deleted after a day.

## Minimal real setup

1. Add the starters (the archetype's `messaging` feature uses the in-memory transport; replace it for production):

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

2. Copy `platform_outbox.sql` from the `platform-messaging-outbox` jar into your migrations, and grant the runtime user access:

   ```sql
   -- V7__platform_outbox.sql: the shipped DDL, then
   grant select, insert, update, delete on platform_outbox to app_rw;
   ```

3. Publish inside your transaction:

   ```java
   @Transactional
   public void place(Order order) {
       orders.save(order);
       outbox.publish("dc.orders:order.placed", EventEnvelope.of(new OrderPlaced(order.id())).key(order.id()).build());
   }
   ```

4. Make sure a queue is bound before you publish: an unroutable message is rejected, and the row parks.

5. Deduplicate in the consumer:

   ```java
   @EventHandler(destination = "dc.orders:order.placed")
   @Transactional
   public void on(EventEnvelope<OrderPlaced> event) {
       if (processed.insertIfAbsent(event.eventId())) {   // your own table, in this transaction
           fulfil(event.payload());
       }
   }
   ```

## Walkthrough

1. **Commit.** The row and your business data commit together. A rollback leaves neither. `publish` throws if no transaction
   owns a connection of the outbox's database.
2. **Drain.** Within a second, one instance claims up to 50 due rows with `FOR UPDATE SKIP LOCKED`, sends each, waits for the
   broker's confirm, records it, and commits. Other instances skip those rows and take the next ones.
3. **Failure.** RabbitMQ down or slow: rows wait, requests are unaffected, and recovery delivers the backlog. RabbitMQ refuses
   a message (unroutable): after `max-rejections` it parks, with a metric, and the others keep flowing.
4. **Crash.** The database releases a dead pod's locks, so another instance picks those rows up. Whatever was sent but not yet
   committed goes again under the same `eventId`: at most one batch.

See [`verification.md`](verification.md) for what was proven, [`operations.md`](operations.md) for running it,
[`troubleshooting.md`](troubleshooting.md) when something looks off, [`open-issues.md`](open-issues.md) for the limits, and
[`../../modules/outbox.md`](../../modules/outbox.md) for the reference.
