# Chapter 10 — Coordination: Locking, Scheduling and Idempotency

> **Capabilities covered:** `locking`, `scheduling`, `idempotency`
>
> The three capabilities that separate a service that works from a service that works when you run
> three of it.
>
> **Starters:** `platform-starter-locking-{jdbc,redis}`, `platform-starter-scheduling`,
> `platform-starter-idempotency` ·
> **Reference:** [modules/locking.md](../../modules/locking.md) ·
> [modules/scheduling.md](../../modules/scheduling.md) ·
> [modules/idempotency.md](../../modules/idempotency.md)

---

## 1. Introduction and Business Value

Three capabilities, one theme: **your code was written as though one copy of it runs, and N copies
run.**

Horizontal scaling is the point of the architecture. It also silently invalidates assumptions that
were true and invisible when there was one instance:

| The assumption | What actually happens with N instances |
|---|---|
| "My scheduled job runs once a night" | It runs N times a night, simultaneously |
| "This request happens once" | Client retries, broker redelivery, and impatient users make it happen more |
| "I can guard this with a `synchronized` block" | `synchronized` guards one JVM. There are N |

Each capability answers one of those:

- **Locking** — a cluster-wide mutex. `LockManager.withLock(name, atMost, action)`.
- **Scheduling** — `@LockedSchedule` on top of `@Scheduled`, plus a virtual-thread scheduler.
- **Idempotency** — `@Idempotent` and an `Idempotency-Key` filter, so a repeat has no second effect.

### The problem, made concrete

**Scheduled work.** A nightly reconciliation runs at 02:00 on three replicas. Three copies read the
same pending records, three copies post the same ledger entries, three copies email the same
customers. The business finds out at month-end close.

**Duplicate effects.** A user double-clicks Submit. Or the response is lost and the client retries. Or
a broker redelivers ([Chapter 7](07-messaging-events.md) §2.2 — at-least-once is a promise, not a
caveat). The order is placed twice, the card is charged twice, the parcel ships twice.

Neither of these produces an error. Both produce *plausible wrong state* that surfaces days later
through a business process rather than an alert.

### What the platform's implementations get right

Distributed locking in particular is a topic where a naive implementation is worse than none, because
it creates confidence without correctness. Three details separate the platform's from the one you
would write in an afternoon:

| Detail | Why it matters |
|---|---|
| **Non-blocking acquisition** | Waiting piles threads up behind a contended lock until the pool is exhausted. Skipping is an outcome you must handle; blocking is an outage you did not plan |
| **Auto-expiry in the store** | A crashed holder must not wedge the cluster forever. Expiry is data-driven, not an in-JVM timer that dies with the process |
| **Token fencing** | A slow holder whose lease already expired must not release a lock someone else now owns |

That third one is the subtle one, and §2.4 walks through the exact sequence it prevents.

### Impact of absence

| Without these capabilities | What actually happens |
|---|---|
| No distributed lock | Scheduled work multiplies by replica count. Duplicate emails, double postings |
| A *blocking* lock | Threads queue behind a held lock until the pool is exhausted and the instance stops serving |
| No auto-expiry | One crashed pod halts a critical job cluster-wide until a human deletes a row |
| No token fencing | Two nodes believe they hold the lock — mutual exclusion violated exactly when it matters |
| No idempotency | Retried requests double-charge, double-ship, double-post |
| Single-threaded scheduler | One slow job delays every other scheduled task |

---

## 2. Core Concepts and Underlying Principles

### 2.1 Why `synchronized` is not the answer

```java
synchronized void reconcile() { ... }        // guards ONE JVM
```

A JVM monitor coordinates threads within one process. With three replicas you have three monitors and
three concurrent executions — each perfectly synchronised with itself.

A distributed lock moves the mutual exclusion into **shared state** that all instances can see: a
database row, or a Redis key. That relocation brings three problems a JVM monitor does not have:

1. **The holder can die** while holding it. A JVM monitor is released when the thread ends; a database
   row is not.
2. **The network can partition.** An instance may believe it holds a lock it no longer does.
3. **Acquisition costs a round trip**, so it is not free and must not be on a hot path.

Every design decision below follows from those three.

### 2.2 Non-blocking acquisition, and why "skipped" is a typed outcome

```java
Optional<T> withLock(String name, Duration atMost, Callable<T> action)
```

If the lock is held, this returns `Optional.empty()` **immediately**. It does not wait.

That is deliberate, and the alternative is a known outage pattern. A blocking lock under contention
means every instance's request threads queue behind the holder; the pool exhausts; the instance stops
serving traffic *including endpoints that have nothing to do with the locked work*. It is
[Chapter 6](06-restclient-resilience.md)'s cascading failure, sourced internally.

The `Optional` return does something else valuable: it makes **"I did not run" an outcome you have to
handle**. A `void` lock API that silently skipped would let a nightly job "run" on every node and do
nothing on all of them, with nobody noticing for months.

!!! success "Best practice — decide explicitly what a skip means"
    ```java
    Optional<Result> result = lockManager.withLock("reconcile", Duration.ofMinutes(5), this::run);
    if (result.isEmpty()) {
        log.info("reconcile skipped: another instance holds the lock");   // expected, fine
    }
    ```
    For a scheduled job, skipping is normal and correct — another instance is doing it. For
    on-demand work triggered by a user, skipping may need to become a 409. The API forces you to have
    an opinion.

### 2.3 Auto-expiry, and the lease you have to size

Locks expire. That is not a convenience — it is the only thing that makes a distributed lock safe in a
world where processes die.

```
   instance A acquires "reconcile" with atMost = 5m
   instance A is SIGKILLed at minute 2
        |
        |  no release ever happens
        v
   minute 5: the lease expires in the store
   instance B acquires it and proceeds
```

Without expiry, one `kill -9` halts that job cluster-wide until a human notices and deletes a row.

The cost is that `atMost` is a real decision:

| `atMost` too short | `atMost` too long |
|---|---|
| The lock expires **while the holder is still working**. A second instance starts. You now have the concurrency you were preventing | A crashed holder blocks the work for that long |

!!! warning "Sizing `atMost` is the one genuinely hard parameter in this chapter"
    Set it comfortably above the method's **worst-case** runtime — not its average. A job that usually
    takes 30 seconds but takes 8 minutes at month-end with `atMost = 5m` will be running on two
    instances at month-end, which is precisely when it matters most. Measure the worst case, then add
    headroom.

### 2.4 Token fencing — the sequence it prevents

Given auto-expiry, a new hazard appears. Follow it carefully:

```
  t=0    A acquires "reconcile", lease 5m, token = a1
  t=0    A begins work
  ...    A stalls (GC pause, a slow query, a network hiccup)
  t=5m   the lease EXPIRES in the store. A does not know.
  t=5m   B acquires "reconcile", token = b2       <-- B is now the legitimate holder
  t=6m   A finally finishes and calls release()
                |
                +-- WITHOUT fencing: A deletes the row. B's lock is gone while B still works.
                |                    C can now acquire. B and C run concurrently.
                |
                +-- WITH fencing:    A's release is "DELETE WHERE token = a1".
                                     The stored token is b2. Nothing is deleted. B keeps its lock.
```

Both providers fence:

- **JDBC** — the release is token-checked in the `WHERE` clause.
- **Redis** — release runs a compare-and-delete Lua script, so the read-and-delete is atomic.

!!! note "Fencing makes release safe; it does not make the work safe"
    In the sequence above, A and B *both executed the action* between t=5m and t=6m. Fencing stopped A
    from stealing B's lock; it did not stop the overlap that the expired lease caused. That is the
    residual risk of any lease-based lock, and it is why `atMost` sizing matters (§2.3), and why the
    locked work should ideally also be idempotent.

### 2.5 The two providers, and the honest TCK relaxation

| | JDBC | Redis |
|---|---|---|
| Store | A `platform_lock` table | A Redis key |
| Acquire | Take over an expired row, else insert; a duplicate-key violation means someone won the race | `SET key token NX PX atMost` — one atomic command |
| Release | `DELETE WHERE token = ?` | Compare-and-delete Lua script |
| Expiry | An `expires_at` column, checked on acquire | Redis `PX` lease |
| Needs | The database you already have | A Redis |

JDBC is the default because it works with infrastructure you already have, and the Flyway migration
for `platform_lock` is contributed automatically — a location teams routinely forget to register when
hand-rolling this.

The platform is unusually candid about one limitation here, and it is worth reading as an example of
how to state a guarantee honestly:

!!! note "The locking TCK relaxes strict concurrency on H2 — deliberately, and visibly"
    `LockProviderTck` relaxes strict *concurrent* exclusion when running on H2, because H2's engine
    cannot reliably serialise reclaim-then-insert across connections. Sequential-exclusion and fencing
    invariants still prove exclusion Docker-free; strict concurrency is certified against **PostgreSQL**
    under `@Tag("docker")`.

    The alternative would have been either a green build that certifies nothing, or a Docker
    requirement on every developer's machine. The platform chose a documented boolean hook and said so
    — which is the right shape for a guarantee you cannot fully verify in the default build.

### 2.6 `@LockedSchedule`, and its deliberate degradation

```java
@Scheduled(cron = "0 0 2 * * *")
@LockedSchedule(name = "nightly-reconcile", atMost = "5m")
void reconcile() { ... }
```

Two annotations, two concerns: *when* it fires, and *that it fires once*.

The behaviour when there is **no** `LockManager` is the interesting design decision: the method runs
**unlocked**, with a one-time warning at startup.

That is a real trade-off, argued both ways:

- *For running unlocked:* a single-instance deployment does not need a lock, and refusing to start
  would make the annotation unusable in development and in small services.
- *Against:* a service that scales from one replica to three without adding a locking starter silently
  loses its guarantee.

The mitigation is that the degradation is **visible** — a startup WARN, not silence. §5.2 makes it
monitorable.

!!! warning "The startup warning is the only thing standing between you and duplicate nightly work"
    If you use `@LockedSchedule` and see that warning in a deployed environment, you do not have the
    guarantee you think you have. Treat it as an error condition in anything running more than one
    replica.

### 2.7 Virtual threads for the scheduler

`@EnableScheduling` is on by default, with a **virtual-thread** `TaskScheduler`.

Spring's default scheduler is a single-threaded pool. One slow job therefore delays every other
scheduled task — a job that hangs for an hour means nothing else fires for an hour. It is a classic
and confusing outage, because the symptom ("the metrics export stopped") is unrelated to the cause
("the reconciliation job is stuck on a database lock").

Each firing on its own virtual thread removes the coupling, and virtual threads make that affordable —
a blocked virtual thread costs a few hundred bytes rather than a megabyte of stack.

### 2.8 Idempotency, and being precise about the guarantee

An operation is **idempotent** when performing it *n* times has the same effect as performing it once.
HTTP defines `GET`, `PUT`, and `DELETE` as idempotent. `POST` is not — which is why the industry
convention is a client-supplied `Idempotency-Key` header.

The platform gives you two mechanisms:

- `@Idempotent(keyExpression, ttl)` — derives a key from SpEL over the method arguments.
- An opt-in HTTP filter for the `Idempotency-Key` header on POST.

Both record the key in an `IdempotencyStore` and reject a repeat while the record is live.

!!! warning "The scope is REJECT-DUPLICATE, not response replay. This matters to your clients."
    A duplicate gets **409 Conflict**. It does **not** get the original response replayed, the way
    Stripe's API does.

    ```
    Request 1:  POST /orders  Idempotency-Key: abc   ->  201 Created  {"orderId": "8812"}
    Request 2:  POST /orders  Idempotency-Key: abc   ->  409 Conflict
    ```

    A client that treats 409 as a hard failure will show the user an error **for an operation that
    succeeded** — and the user will retry into a loop. Your client contract must say: *409 on a repeat
    means the first one worked; go and fetch the result.*

    The platform documents this limitation plainly rather than letting you assume Stripe semantics. If
    your consumers genuinely need replay, you must store the response yourself.

---

## 3. Feature Reference

### 3.1 Locking — public API and SPI

| Type | Module | Status | Purpose |
|---|---|---|---|
| `LockManager` | locking-api | STABLE | `<T> Optional<T> withLock(String, Duration, Callable<T>)` |
| `LockException` | locking-api | STABLE | The backend failed, or the action threw a checked exception |
| `LockProvider` | locking-**spi** | **EXPERIMENTAL** | `Optional<LockHandle> tryAcquire(String, Duration)` |
| `LockHandle` | locking-spi | EXPERIMENTAL | Releases the acquired lock |

**`LockManager` semantics**, from the interface: locks are **non-reentrant** (a thread already holding
the name will not re-acquire it), **auto-expiring**, and acquisition is **non-blocking**.

`Optional.empty()` is returned in two different cases, which is a real sharp edge:

- The lock could not be acquired.
- The lock was acquired and **the action returned `null`**.

!!! warning "`Optional.empty()` is ambiguous between 'skipped' and 'returned null'"
    If you need to distinguish them, do not return `null` from the action. Return a sentinel — a
    `Boolean`, a result record, or `Optional` of your own. This trips people writing
    `withLock(..., () -> { doWork(); return null; })` and then testing `result.isPresent()` to mean
    "it ran".

**`LockProvider` implementation requirements**, from the SPI's own javadoc: non-blocking, auto-expiry
enforced **in the store** (not an in-JVM timer), safe release fenced by a per-acquisition token,
non-reentrant, and thread-safe.

### 3.2 Scheduling — public API

| Type | Status | Purpose |
|---|---|---|
| `@LockedSchedule` | STABLE | `String name()`, `String atMost() default "1h"` |

`atMost` is a **duration string** — `"5m"`, `"PT30S"` — because an annotation value must be a
compile-time constant and `Duration` is not.

Two methods sharing a `name` are mutually exclusive with each other. That is occasionally what you
want; more often a shared name is a copy-paste bug.

### 3.3 Idempotency — public API

| Type | Status | Purpose |
|---|---|---|
| `@Idempotent` | STABLE | `String keyExpression()`, `String ttl() default "24h"` |
| `IdempotencyStore` | STABLE | `boolean putIfAbsent(String key, Duration ttl)` |

`putIfAbsent` returns `true` when the key was **newly recorded** (first occurrence — proceed) and
`false` when it was already present and live (a duplicate — reject).

**Implementation requirements:** `putIfAbsent` must be **atomic** across concurrent callers — only one
may receive `true` for a given key — and keys must expire on their own after `ttl`.

`keyExpression` is SpEL over the method arguments, addressable by name and as `#a0` / `#p0`.

### 3.4 Providers

| Capability | Default provider | Alternative | Selected by |
|---|---|---|---|
| Locking | JDBC (`platform_lock`, migration shipped) | Redis | Which starter you add |
| Idempotency | JDBC (`platform_idempotency`, migration shipped) | Redis (`SET NX PX`) | A `StringRedisTemplate` being present |

### 3.5 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.locking.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.scheduling.enabled` | Boolean | `true` | Kill switch — also disables `@EnableScheduling` | To stop all scheduled work in an instance |
| `dc.platform.idempotency.enabled` | Boolean | `true` | Kill switch | Essentially never |
| `dc.platform.idempotency.http.enabled` | Boolean | **`false`** | The `Idempotency-Key` filter | When clients send the header and you want it enforced |
| `dc.platform.idempotency.http.header-name` | String | `Idempotency-Key` | The header read | To match an existing client convention |
| `dc.platform.idempotency.http.ttl` | Duration | `24h` | How long an HTTP key is remembered | Match your clients' retry window |

Note how few knobs there are. `atMost` and `keyExpression` live on the annotations, at the call site,
because they are per-operation decisions rather than per-service ones.

### 3.6 Extension points

| Extension | How | Effect |
|---|---|---|
| A lock backend the platform does not ship | A `LockProvider` bean | `LockManager` binds to it. Certify against the locking TCK |
| A different idempotency store | An `IdempotencyStore` bean | Platform's default backs off |
| Replace lock orchestration | A `LockManager` bean | Platform's backs off |
| Change the scheduler | A `TaskScheduler` bean | Platform's virtual-thread scheduler backs off |

---

## 4. How-to Guide

### 4.1 Add the capabilities

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-locking-jdbc</artifactId>   <!-- or -locking-redis -->
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-scheduling</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-idempotency</artifactId>
</dependency>
```

The JDBC providers append their Flyway migration locations automatically — you do not register
`platform_lock` or `platform_idempotency` yourself.

### 4.2 Make a scheduled job cluster-safe

```java snippet:book-10-locked-schedule
@Component
class ReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

    @Scheduled(cron = "0 0 2 * * *")
    @LockedSchedule(name = "nightly-reconcile", atMost = "30m")
    public void reconcile() {
        // Runs on exactly one instance per firing. atMost is sized above the WORST case,
        // not the average — see the sizing warning in this chapter.
        log.info("reconciling");
    }
}
```

!!! warning "`@LockedSchedule` must be on a `public` method of a Spring bean"
    It is proxy-based, like every other annotation in this book. A package-private or `final` method
    cannot be advised, and — as everywhere else — a `this.` call bypasses it.

### 4.3 Lock programmatically

```java snippet:book-10-lock-manager
@Service
class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final LockManager lockManager;

    ImportService(LockManager lockManager) {
        this.lockManager = lockManager;
    }

    boolean importBatch(String batchId) {
        // Return a sentinel, not null — empty would otherwise be ambiguous. See §3.1.
        Optional<Boolean> outcome = lockManager.withLock(
                "import-" + batchId,
                Duration.ofMinutes(10),
                () -> {
                    doImport(batchId);
                    return Boolean.TRUE;
                });

        if (outcome.isEmpty()) {
            log.info("import of {} skipped: already running elsewhere", batchId);
            return false;
        }
        return true;
    }

    private void doImport(String batchId) {
        // ...
    }
}
```

!!! success "Best practice — name the lock after the resource, not the job"
    `"import-" + batchId` locks *that batch*, so two different batches import concurrently. A single
    `"import"` lock serialises everything and turns a parallel workload into a queue. Lock the
    narrowest thing that gives you correctness.

### 4.4 Make an operation idempotent

```java snippet:book-10-idempotent
record PlaceOrderCommand(String orderId, String customerId) {
}

@Service
class OrderPlacementService {

    @Idempotent(keyExpression = "#command.orderId", ttl = "24h")
    public void placeOrder(PlaceOrderCommand command) {
        // A second call with the same orderId inside 24h is rejected with 409.
    }
}
```

The key expression can reach anything in the arguments:

```java
@Idempotent(keyExpression = "#a0")                                  // first argument
@Idempotent(keyExpression = "#command.customerId + ':' + #command.reference")   // composite
```

!!! success "Best practice — key on a business identifier the client controls"
    The key must be **the same** across the original and the retry. A client-supplied order reference
    works. A generated UUID created inside the method does not. Neither does a
    [correlation id](01-core.md) — a retry brings a new one, which is the single most common mistake
    here.

### 4.5 Enforce the `Idempotency-Key` header

```yaml
dc:
  platform:
    idempotency:
      http:
        enabled: true
        header-name: Idempotency-Key
        ttl: 24h
```

Now a POST carrying a repeated `Idempotency-Key` is rejected with 409 before it reaches your
controller.

!!! warning "Enabling this changes your API contract"
    Clients that send the header and retry will start seeing 409s where they previously saw a second
    success. That is the point, and it is a breaking change for a client that treats 409 as failure.
    Coordinate it, and document the semantics from §2.8.

### 4.6 Supply your own store

```java snippet:book-10-idempotency-store
@Configuration
class CustomIdempotency {

    @Bean
    IdempotencyStore idempotencyStore(ConcurrentHashMap<String, Instant> seen) {
        // Illustrative only — putIfAbsent must be atomic ACROSS INSTANCES, and this map is not.
        return (key, ttl) -> seen.putIfAbsent(key, Instant.now().plus(ttl)) == null;
    }
}
```

!!! warning "An in-memory store is not an idempotency store"
    With N instances, each has its own map, so a retry routed to a different instance sails through.
    The snippet above compiles and is wrong in production — it is here to make the requirement
    concrete. A real store must be **shared and atomic**: the shipped JDBC or Redis one, or something
    with the same properties.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local, single instance | Locking optional | `@LockedSchedule` degrades to unlocked with a warning |
| **Any environment with >1 replica** | A locking provider, definitely | Otherwise scheduled work multiplies |
| All | `atMost` above the **worst-case** runtime | §2.3 |
| All | Idempotency TTL ≥ your clients' retry window | A key that expires before retries stop is not protecting anything |
| Redis-backed | `maxmemory-policy: volatile-lru` | `allkeys-lru` can evict a live lock. See [Chapter 9](09-cache-redis.md) §5.1 |

!!! warning "A Redis eviction policy can delete a held lock"
    The Redis lock is a key with a `PX` lease. Under `allkeys-lru`, Redis may evict it under memory
    pressure — and an evicted lock is an *available* lock, so a second instance acquires it while the
    first is still working. Mutual exclusion silently gone. This is a server-side setting, and it is
    worth confirming who owns it.

### 5.2 What to monitor

| Signal | Where | Alert when |
|---|---|---|
| **The `@LockedSchedule` unlocked warning** | Startup logs | **Ever, in a multi-replica environment.** §2.6 |
| Skipped executions | Your own log line on `Optional.empty()` | *All* instances skip — which means nobody ran it |
| Job duration versus `atMost` | Timing around the locked block | Duration approaches `atMost` — you are near a double-execution |
| `platform_lock` row count and age | The table | A row far past `expires_at` means nothing has tried to acquire it |
| 409 rate | HTTP metrics, or the error `code` | A spike means clients are retrying hard — or a key is being reused wrongly |
| `platform_idempotency` growth | The table | Rows not expiring means the TTL sweep is not working |

!!! tip "The alert nobody sets up, and should: 'nobody ran the job'"
    Every instance logging "skipped" looks healthy on each instance. Aggregate across the fleet and it
    means the work did not happen. Emit a metric or a log line **on successful completion** and alert
    on its *absence* — a dead-man's switch. That is the only signal that distinguishes "another
    instance did it" from "no instance did it".

### 5.3 Troubleshooting

**The job runs on every instance.**

| Cause | Check |
|---|---|
| No `LockManager` bean | The startup WARN. No locking starter |
| `@LockedSchedule` missing | Only `@Scheduled` is present |
| Different lock `name` per instance | An interpolated value that differs — e.g. hostname |
| Self-invocation | The proxy is bypassed |
| Non-public method | Cannot be advised |

**The job never runs anywhere.** Every instance is skipping, which means a lock row exists and is not
expiring. Usually a previous holder crashed with a very long `atMost`, or clock skew is making
`expires_at` look far in the future. Inspect the row; it carries the token and the expiry.

**Two instances ran the job anyway.** The lease expired mid-execution (§2.3). Compare the run duration
with `atMost`. Fencing prevented lock *theft*; it does not prevent overlap caused by a short lease.

**`Optional.empty()` but I know it ran.** The action returned `null`. §3.1 — return a sentinel.

**Everything gets a 409.**

| Cause | Check |
|---|---|
| The key is not stable across retries | A UUID or correlation id in `keyExpression` |
| TTL too long for a legitimately repeated business key | An order reference reused next month |
| The HTTP filter is on and clients reuse a key across different requests | `http.enabled` |

**Duplicates still get through.**

| Cause | Check |
|---|---|
| An in-memory store | §4.6 |
| The key differs between original and retry | Log the derived key |
| TTL shorter than the retry window | A client retrying after 25 h with a 24 h TTL |
| Self-invocation | The proxy again |

**Clock skew.** Both providers stamp expiry from a clock. JDBC uses the application's clock, so **two
instances with skewed clocks disagree about expiry** — one may see a lock as expired while the holder
believes it is live. Run NTP. This is a real operational prerequisite, not a theoretical one.

### 5.4 Scaling and performance

- **Lock acquisition is a round trip.** JDBC costs a database round trip and a connection from the
  pool; Redis costs one command. Neither belongs on a per-request hot path.
- **A lock held for the duration of the action holds nothing else** — but if the action holds a
  database connection *and* the lock, you have serialised two scarce resources.
- **Lock granularity is your throughput dial.** `"import-" + batchId` scales with batches; `"import"`
  serialises everything.
- **Idempotency adds one store round trip per call.** With the JDBC store that is a connection plus an
  insert, on every invocation — including the ones that are not duplicates.
- **Both tables grow.** Expiry is checked on access for locks; the idempotency table needs its rows to
  actually go away. §6.5.
- **Virtual threads mean a slow job no longer blocks other jobs**, but a slow job still holds its lock
  for its whole duration.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| Lock names are shared state | Two services on the same database or Redis share the namespace. A name collision is accidental mutual exclusion between unrelated services |
| Idempotency keys may be user-controlled | The HTTP filter reads a client header. A malicious client can send another user's key and get a 409 — which **leaks that the key was used** |
| Key content in logs | An idempotency key derived from a business identifier may be personal data |
| Denial by key exhaustion | A client spraying random keys fills the store. [Rate limiting](13-ratelimit-flags.md) is the control |
| A held lock is a denial vector | Code that can acquire a long-lived lock can stall a job cluster-wide |

!!! warning "Idempotency keys should be namespaced per caller"
    The HTTP filter keys on the header value alone. If two clients pick the same key — plausible with
    short human-chosen values — one gets a spurious 409 and learns something about the other's traffic.
    If your clients are mutually untrusted, incorporate the authenticated subject
    ([Chapter 5](05-security-authz.md)) into the key rather than relying on the raw header.

---

## 6. Deep Dive

### 6.1 How the JDBC provider acquires atomically

Three steps, and the ordering is the whole design:

```sql
-- 1. Take over a lock whose lease has expired.
UPDATE platform_lock SET token = ?, expires_at = ?
 WHERE lock_name = ? AND expires_at <= ?;        -- 1 row updated -> acquired

-- 2. If that touched no row, the lock either does not exist or is live. Try to create it.
INSERT INTO platform_lock (lock_name, token, expires_at) VALUES (?, ?, ?);

-- 3. A duplicate-key violation on the insert means another node won the race.
--    That is a NORMAL outcome, not an error: return empty.
```

Two properties fall out:

- **Expiry is data-driven.** `expires_at <= now` is evaluated by the database on every acquire
  attempt. No sweeper job, no in-JVM timer that dies with the process.
- **The race is resolved by the database's unique constraint.** Two nodes inserting simultaneously: one
  succeeds, one gets a constraint violation and returns empty. No application-level coordination.

That third step is why the H2 caveat in §2.5 exists — H2's engine cannot reliably serialise the
reclaim-then-insert sequence across connections, so strict concurrent exclusion is certified against
PostgreSQL instead.

### 6.2 How the Redis provider acquires atomically

```
   SET <name> <token> NX PX <atMost>
```

One command. `NX` means set-only-if-absent, `PX` sets the lease. Atomic by construction — Redis is
single-threaded per key, so there is no race to resolve.

Release is where care is needed, because "check the token, then delete" is two operations and
therefore a race. A Lua script makes it one:

```lua
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
```

Redis executes a script atomically, so no other client can acquire between the `get` and the `del`.
This is the standard pattern from the Redis documentation, and the platform uses it verbatim rather
than inventing something.

### 6.3 Why the SPI is EXPERIMENTAL while `LockManager` is STABLE

The same three-audience split as [messaging](07-messaging-events.md) §6.6. `LockManager` — what
applications call — is frozen. `LockProvider` and `LockHandle` — what providers implement — are marked
`EXPERIMENTAL`, so the platform can evolve the contract as more backends appear.

Practically: if you only call `withLock`, none of this affects you. If you write a provider, expect to
revisit it, and certify against the TCK so a contract change surfaces as a test failure rather than a
production incident.

### 6.4 Why `@LockedSchedule` is not just `@Scheduled` plus a lock in the method body

You could write:

```java
@Scheduled(cron = "0 0 2 * * *")
void reconcile() {
    lockManager.withLock("nightly-reconcile", Duration.ofMinutes(30), () -> { doWork(); return null; });
}
```

Functionally equivalent, and the annotation exists anyway for three reasons:

1. **The intent is visible in the signature**, so a reviewer sees "this is cluster-exclusive" without
   reading the body.
2. **The degradation is handled once.** The no-`LockManager` case with its startup warning is in the
   interceptor, not copy-pasted into every job.
3. **The lock name is declarative**, so it can be inventoried — you can find every cluster-wide lock in
   a codebase by grepping the annotation.

The advisor uses a plain Spring AOP auto-proxy creator, so there is **no `aspectjweaver` requirement**
— no load-time weaving, no agent flags, no class-loading failure modes. That is the same choice
[authorization](05-security-authz.md) §2.8 makes, for the same reasons.

### 6.5 The two tables, and what cleans them up

| Table | Growth | Cleanup |
|---|---|---|
| `platform_lock` | One row **per lock name**, reused | Bounded by name count. Expired rows are taken over, not deleted |
| `platform_idempotency` | One row **per key** | Rows expire by an expiry column — and expired rows are only skipped, not necessarily removed |

The asymmetry matters operationally. Locks are bounded by how many distinct names you use, which is
small. The idempotency table grows with **traffic**, and a high-volume endpoint with a 24-hour TTL
accumulates a day's worth of keys.

!!! warning "Check that expired idempotency rows are actually being removed"
    An expiry column makes a row *ineffective*; it does not make it *disappear*. If nothing deletes
    expired rows, the table grows without bound — slowly enough that nobody notices for months, then
    suddenly enough that an index rebuild becomes an incident. Confirm the retention story for your
    provider, and if in doubt schedule a delete job — with `@LockedSchedule` on it, naturally.

### 6.6 Why there is no `atMost` property

`atMost` lives on the annotation and in the method call, never in `application.yml`.

That is deliberate: the correct lease is a property of **the work**, not of the environment. A
reconciliation that takes 20 minutes needs the same lease in test and production; a health-check job
needs seconds. A single service-wide property would have to be the maximum of all of them, which
defeats the purpose — a crashed holder of the fastest job would block it for as long as the slowest job
needs.

The same reasoning puts `keyExpression` and `ttl` on `@Idempotent`. Compare the HTTP filter's TTL,
which *is* a property — because that one is a property of the client contract, which is service-wide.

### 6.7 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| `atMost` sized to the average runtime | The average is what you measured | A month-end run overruns and two instances execute |
| Correlation id as an idempotency key | Both are "a unique request id" | A retry brings a *new* correlation id. Use a business key |
| In-memory `IdempotencyStore` | It compiles and passes single-instance tests | Retries to another instance sail through |
| Returning `null` from the locked action | It reads naturally for `void` work | `Optional.empty()` becomes ambiguous |
| One coarse lock name | Simpler | Serialises a workload that could run in parallel |
| Ignoring the unlocked warning | It is only a WARN | You do not have the guarantee you think you have |
| `synchronized` for cluster exclusion | It looks like mutual exclusion | Guards one JVM of N |
| Assuming 409 means failure | It is an error status | It means the first attempt succeeded |
| Idempotency TTL shorter than the retry window | 24h looks generous | A client retrying at 25h creates a duplicate |
| `allkeys-lru` with Redis locks | It is a common Redis default | An evicted lock is an available lock |
| Skewed clocks | Nobody checks NTP | Instances disagree about expiry |
| Lock acquired inside a database transaction | It seems tidy | Two scarce resources held together |

---

## 7. Exercises and Hands-on Labs

Starting point: a service from the [archetype](../../quickstart.md) with `locking-jdbc`, `scheduling`,
and `idempotency`, run as **two** processes on different ports against one database.

### Lab 1 — Basic: prove cluster-wide single execution

**Goal.** Watch the lock work, and watch it not work.

**Steps.**

1. Add a `@Scheduled(fixedRate = 10000)` method that logs its instance port. Run two instances. How
   many log lines per 10 seconds?
2. Add `@LockedSchedule(name = "demo", atMost = "1m")`. Run both again. Now how many?
3. Inspect `platform_lock`. What columns, what values? Watch `expires_at` across firings.
4. Kill the holding instance mid-execution. Does the other take over? After how long?
5. Remove the locking starter, keeping `@LockedSchedule`. What appears at startup, and what happens to
   the execution count?

**Expected outcome.** Step 1: two lines. Step 2: one. Step 4: takeover after the lease expires, not
immediately. **Step 5 is the important one** — a startup WARN and back to two executions. That warning
is the entire safety net.

**Hints.**

- Log `ManagementFactory.getRuntimeMXBean().getName()` or the port so lines are attributable.
- Step 4 is more instructive with `atMost = "30s"` — you can watch the takeover.

**How to verify.** The two-context H2 test the platform itself uses: two application contexts sharing
one lock table, asserting the method body executed exactly once.

### Lab 2 — Intermediate: idempotency, and the key that is not stable

**Goal.** Get 409 semantics right, and reproduce the most common keying mistake.

**Steps.**

1. Add `@Idempotent(keyExpression = "#command.orderId", ttl = "1m")` to a POST-backed method.
2. Post the same order id twice. What status, and what body?
3. Wait 61 seconds. Post again. Explain.
4. Now change the key expression to use a value generated *inside* the request — a `UUID.randomUUID()`
   passed in, or the [correlation id](01-core.md). Post twice. What happens, and why?
5. Enable the HTTP filter. Send two POSTs with the same `Idempotency-Key` and **different bodies**.
   What happens? Is that right?
6. Write the paragraph you would put in your API documentation explaining 409 to a client.

**Expected outcome.** Step 2 gives 201 then 409 with an RFC-9457 body
([Chapter 2](02-errors-validation.md)). Step 4 gives 201 twice — no protection at all, because the key
differed. **Step 5 is worth thinking about**: the filter keys on the header alone, so a different body
with the same key is still rejected. Whether that is correct depends on your contract.

**Hints.**

- Log the derived key to see exactly what the SpEL produced.
- Step 6 is the real deliverable. A client that does not understand 409 will retry into a loop.

**How to verify.** Three tests: first call succeeds, immediate repeat gives 409, repeat after TTL
succeeds again.

### Lab 3 — Advanced: overrun the lease, then fence it

**Goal.** Reproduce the double-execution `atMost` sizing prevents, and see fencing do its job.

**Steps.**

1. Set `@LockedSchedule(name = "slow", atMost = "5s")` on a method that sleeps 15 seconds. Run two
   instances.
2. Count executions. Do they overlap? Confirm from the timestamps.
3. Inspect `platform_lock` during the overlap. Whose token is stored?
4. When the first instance finishes and releases, is the row deleted? Explain, using the token.
5. Fix it by sizing `atMost` correctly. Confirm no overlap.
6. Now make the locked work **idempotent** as well and re-run step 1's broken configuration. What is
   the observable damage now?
7. Switch to `locking-redis`, repeat steps 1–4, and note what differs.
8. Simulate clock skew: shift one instance's clock forward by a minute. What breaks, and which
   provider is affected?

**Expected outcome.** Steps 1–3 show two instances executing concurrently — the failure mode §2.3
warns about, reproduced. **Step 4 shows fencing**: the first instance's release deletes nothing,
because the stored token is now the second instance's. Step 6 is the lesson — belt and braces means the
overlap becomes harmless.

**Hints.**

- Step 4 is the clearest demonstration of fencing you will get; watch the row rather than reasoning
  about it.
- Step 8: the JDBC provider stamps expiry from the *application's* clock, so skew directly affects it.

**How to verify.** A test with a deliberately short lease asserting overlap occurs, and a second at a
correct lease asserting it does not — both with a fixed injected `Clock`, which is why the providers
take one.

---

## 8. Checklist / Quick Reference

**Add them**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-locking-jdbc</artifactId>   <!-- or -locking-redis -->
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-scheduling</artifactId>
</dependency>
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-idempotency</artifactId>
</dependency>
```

**Use them**

```java
@Scheduled(cron = "0 0 2 * * *")
@LockedSchedule(name = "nightly-reconcile", atMost = "30m")     // public method, worst-case lease
public void reconcile() { }

lockManager.withLock("import-" + batchId, Duration.ofMinutes(10), () -> { work(); return TRUE; });
//        ^ Optional.empty() = skipped OR the action returned null. Return a sentinel.

@Idempotent(keyExpression = "#command.orderId", ttl = "24h")    // a key stable across retries
public void placeOrder(PlaceOrderCommand command) { }
```

**Properties**

| Key | Default |
|---|---|
| `dc.platform.locking.enabled` | `true` |
| `dc.platform.scheduling.enabled` | `true` |
| `dc.platform.idempotency.enabled` | `true` |
| `dc.platform.idempotency.http.enabled` | **`false`** — opt-in |
| `dc.platform.idempotency.http.header-name` | `Idempotency-Key` |
| `dc.platform.idempotency.http.ttl` | `24h` |

**Semantics to remember**

| Behaviour | Value |
|---|---|
| Lock acquisition | **Non-blocking** — skipped, never queued |
| Locks | Non-reentrant, auto-expiring, token-fenced |
| No `LockManager` + `@LockedSchedule` | Runs **unlocked**, with a startup WARN |
| Duplicate `@Idempotent` | **409 Conflict — not response replay** |
| Scheduler | Virtual thread per firing |

**Diagnose it**

```bash
# is the lock table doing anything?
psql -c "SELECT lock_name, token, expires_at FROM platform_lock;"
# is idempotency growing without bound?
psql -c "SELECT count(*) FROM platform_idempotency;"
curl -s localhost:8080/actuator/platform | jq          # locking / scheduling / idempotency active?
grep -i "unlocked\|LockedSchedule" startup.log         # the warning that matters
```

**Rules of thumb**

- Size `atMost` above the **worst case**, not the average.
- Name the lock after the resource, not the job — `"import-" + batchId`, not `"import"`.
- Never return `null` from a locked action; `Optional.empty()` becomes ambiguous.
- An idempotency key must be stable across retries. Never a correlation id, never a fresh UUID.
- 409 means the first attempt **succeeded**. Document that for your clients.
- An in-memory `IdempotencyStore` protects nothing across instances.
- The `@LockedSchedule` unlocked warning is an error condition in a multi-replica environment.
- Alert on the **absence** of a successful run, not on skips.
- `volatile-lru`, not `allkeys-lru` — an evicted lock is an available lock.
- Run NTP. Skewed clocks mean instances disagree about expiry.
- Do not acquire a lock inside a database transaction.
- Belt and braces: make locked work idempotent too, so a lease overrun is harmless.

---

**Next:** [Chapter 11 — Storage and Files](11-storage-files.md), where the uploads your idempotent
endpoints accept actually go.

**Reference:** [modules/locking.md](../../modules/locking.md) ·
[modules/scheduling.md](../../modules/scheduling.md) ·
[modules/idempotency.md](../../modules/idempotency.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
