# Chapter 12 — Audit

> **Capabilities covered:** `audit`
>
> A record of who did what, routed to the sink your compliance regime demands.
>
> **Starter:** `platform-starter-audit` · **Reference:** [modules/audit.md](../../modules/audit.md)

---

## 1. Introduction and Business Value

Audit answers one question, asked after the fact and usually under pressure: **who did what, when, to
which resource, and did it work?**

In a regulated environment that is not a nice-to-have. It is the evidence an investigation runs on,
the artifact an auditor asks for, and — when something goes wrong — the difference between "we can
show you exactly what happened" and "we think so".

### Why this is not just logging

A reasonable objection: you already have [structured logs](03-logging-observability.md) with a
correlation id. Why a separate capability?

| | Logs | Audit |
|---|---|---|
| Purpose | Diagnose behaviour | Evidence of action |
| Audience | Engineers | Auditors, investigators, regulators |
| Retention | Days to weeks | Years, often mandated |
| Schema | Free-form, evolves constantly | Fixed and queryable — `who`, `what`, `when`, `outcome` |
| Volume | Everything | Only what matters |
| Destination | The log store | Wherever compliance requires |

Mixing them means either your audit trail expires with your log retention, or you pay years of storage
for `DEBUG` lines. And a free-form log line cannot be queried reliably for "every privileged action by
this user last quarter", because nothing guarantees the fields are there.

### The two design decisions that matter

**Failures are audited too.** `@Audited` records whether the method returns **or throws**.

That sounds obvious and is routinely got wrong, because the natural implementation records after a
successful return. But a *failed* privileged operation is the most security-relevant record there is:
someone attempted to approve a declaration they could not approve, and the attempt left no trace. An
audit trail of only successes is precisely blind to the attack.

**Auditing must not take the service down.** The platform's auditor hands events to a bounded queue
drained by one daemon thread, and under sustained overload it **sheds records with a warning** rather
than blocking the request thread.

That is an explicit, uncomfortable trade — completeness for availability — and §2.4 argues both sides,
because for some regimes it is the wrong default and the platform gives you the knob to reverse it.

### Impact of absence

| Without this capability | What actually happens |
|---|---|
| No audit trail | "Who cancelled this order?" has no answer |
| Only successes recorded | Failed privileged attempts leave no trace — the attack is invisible |
| Ad-hoc audit logging | Different formats per service; unqueryable in aggregate |
| Audit tied to log retention | Records expire before the retention period requires |
| Synchronous writes | Audit-store latency becomes request latency; an audit outage becomes a service outage |
| No sink fallback | A service in an environment missing its intended sink audits nothing, silently |

---

## 2. Core Concepts and Underlying Principles

### 2.1 The event

```java
record AuditEvent(
    String action,               // "order.create"
    String actor,                // the subject, or "anonymous" / "system"
    String resource,             // "order:42"  — nullable
    Outcome outcome,             // SUCCESS | FAILURE
    Instant at,
    String correlationId,        // nullable
    Map<String, Object> details)
```

Six of the seven components are the classic audit questions. The seventh — `correlationId` — is what
makes an audit record *joinable* to everything else: the [logs](03-logging-observability.md) for that
request, the [problem response](02-errors-validation.md) the caller received, the
[messages](07-messaging-events.md) it published. An investigation starts at an audit record and
follows the id outward.

`action`, `actor`, `outcome`, and `at` are validated non-null. `resource` and `correlationId` are
nullable — a login has no resource; a background job has no correlation id.

!!! note "`actor` is never null, even when there is nobody"
    It falls back to a sentinel — `anonymous` when unauthenticated, `system` for background work. That
    is deliberate: a null actor would force every consumer of the audit trail to handle absence, and
    "we do not know who" is itself information worth recording explicitly.

### 2.2 Aspect-derived, so the code stays readable

```java
@Audited(action = "order.create", resourceExpression = "#result.id")
public Order create(CreateOrderCommand command) { ... }
```

The aspect fills in everything else: actor from the current user, correlation id from the request
context, timestamp, and outcome from what the method did.

That leaves the method body untouched. Compare the alternative:

```java
public Order create(CreateOrderCommand command) {
    try {
        Order order = doCreate(command);
        auditor.record(new AuditEvent("order.create", currentUser(), "order:" + order.id(),
                SUCCESS, Instant.now(), correlationId(), Map.of()));
        return order;
    } catch (Exception e) {
        auditor.record(new AuditEvent("order.create", currentUser(), null,
                FAILURE, Instant.now(), correlationId(), Map.of()));
        throw e;
    }
}
```

Eight lines of ceremony around one line of work — and a `catch` block someone will eventually remove
because it "does nothing". The annotation makes the audit requirement **visible in review** and
impossible to accidentally delete without also deleting the declaration.

!!! warning "`#result` is only bound on success"
    The resource expression can reference `#result`, but on the failure path there is no return value.
    An expression that only reads `#result` records a `null` resource for exactly the events you most
    want attributed. If the resource matters on failure, derive it from the **arguments** instead:
    `resourceExpression = "'order:' + #command.orderId"`.

### 2.3 The degrading sink chain

```
   messaging sink   (an EventPublisher bean is present)
        |  else
        v
   jdbc sink        (a DataSource is present)
        |  else
        v
   log sink         (always available — a dedicated AUDIT logger)
```

Selection is by `@ConditionalOnBean` plus explicit auto-configuration ordering, and a **startup WARN
names the selected sink** so operators can see which one is live.

The value is that a service always has a *working* audit destination. Without a fallback, a service
deployed to an environment missing its intended sink would audit nothing — silently, which is the worst
possible failure mode for a compliance control.

!!! warning "The fallback is a safety net, not a plan"
    If your compliance regime requires audit records in a database and the service quietly fell back to
    the log sink, you are not compliant — you just do not know it yet. **Assert the selected sink at
    startup** in any environment where it matters, rather than trusting the chain. §5.2.

### 2.4 Fire-and-forget, and the trade it makes

```
   request thread                     platform-audit-worker (one daemon thread)
   --------------                     ----------------------------------------
   auditor.record(event)
        |
        +-- queue.offer(event)  --------> poll()
        |      |                             |
        |      +-- queue FULL?               v
        |            drop + WARN         sink.write(event)   (may block on I/O)
        v
   returns immediately
```

Three properties, each deliberate:

- **`record` never blocks.** A non-blocking `offer`, so audit-store latency never becomes request
  latency.
- **`record` never throws.** A sink failure cannot fail the business operation that was being audited.
- **A full queue sheds** with a WARN carrying a running dropped-count.

The argument *for*: an audit-store outage should not be a service outage. Availability of the business
function usually outranks completeness of the audit trail.

The argument *against*: for some regimes, an unrecorded privileged action is worse than a failed
request. If you cannot prove what happened, the transaction arguably should not have happened.

!!! success "Best practice — know which side you are on, and configure accordingly"
    The platform's default is availability. To move toward completeness:

    - **Raise `queue-capacity`** — more headroom before shedding, at the cost of memory and a longer
      window of unwritten events on a crash.
    - **Or supply a synchronous `Auditor` bean** — the platform's backs off, and you get
      write-before-return semantics with all the latency and coupling that implies.

    What you must not do is assume the default gives you a guarantee it explicitly does not.

!!! warning "The worker is a daemon thread, so shutdown loses the queue tail"
    A daemon thread does not keep the JVM alive. On shutdown, whatever is still queued may not be
    written. Combined with shedding, that means the platform's audit trail is **best-effort, not
    guaranteed** — stated plainly here because a compliance conversation needs the accurate version.

### 2.5 The `ActorResolver` seam

Audit needs to know who the actor is. The obvious implementation depends on the security capability —
and that would make audit unusable in a service without HTTP, and violate "a capability you don't add
costs you nothing".

So the aspect never references `security-api` directly. An `ActorResolver` seam resolves the subject
when security is present and `anonymous` otherwise.

This is the same optional-enrichment pattern as [data auditing](08-data.md) §2.3 and
[flags](13-ratelimit-flags.md): `@ConditionalOnClass` guards a bean whose constructor signature would
otherwise fail to load. Recognising the pattern is worth more than any individual instance of it.

### 2.6 Why the messaging sink is its own module

`platform-audit-messaging-autoconfigure` exists as a separate artifact, which looks like over-
engineering until you read the constitution.

An **implementation** module may depend on its own capability's api/spi and its third-party library —
it may **not** reach into another capability. So an audit *impl* cannot depend on messaging. And the
audit autoconfigure module has a fan-out ceiling that folding it in would breach.

The resolution was a separate autoconfigure module, at the cost of one extra artifact.

!!! note "This is the constitution shaping the module graph, visibly"
    The same thing produced [`StreamingDownloads`](11-storage-files.md)'s odd home. When the platform's
    structure looks slightly baroque, the alternative was usually a convenient edge that would have
    eroded a rule the build enforces. An architecture with no exceptions is one you can still reason
    about in three years.

---

## 3. Feature Reference

### 3.1 Public API

Package `ae.gov.dubaicustoms.platform.audit`. All STABLE.

| Type | Kind | Purpose |
|---|---|---|
| `AuditEvent` | record | The seven-component record in §2.1 |
| `Outcome` | enum | `SUCCESS`, `FAILURE` |
| `@Audited` | annotation | `String action()`, `String resourceExpression() default ""` |
| `Auditor` | interface | `void record(AuditEvent)` |

`@Audited` targets `METHOD` only — there is no type-level form, because an audit action name is
inherently per-operation.

`resourceExpression` is SpEL over the invocation: arguments by name and as `#a0`/`#p0`, and the return
value as `#result` (**success only** — §2.2).

### 3.2 SPI

| Type | Status | Purpose |
|---|---|---|
| `AuditSink` | **EXPERIMENTAL** | `void write(AuditEvent)` |

**Implementation requirements**, from the interface: called from the background worker rather than the
request thread, so `write` **may block on I/O**; must be thread-safe; and should **let exceptions
propagate** — the worker logs and drops a failed event rather than losing the request.

That last point is worth reading twice: a sink is not expected to handle its own failures, and a
throwing sink loses that one event rather than breaking anything.

### 3.3 The shipped sinks

| Sink | Module | Selected when | Notes |
|---|---|---|---|
| Messaging | `platform-audit-messaging-autoconfigure` | An `EventPublisher` bean is present | Highest precedence |
| JDBC | `platform-audit-jdbc` | A `DataSource` is present | Flyway migration contributed automatically |
| Log | `platform-audit-log` | Always | A dedicated `AUDIT` logger. The fallback |

The log sink writes to its own logger name, so you can route audit records to a different appender,
file, or index from ordinary application logs — which is what makes separate retention possible without
a separate store.

### 3.4 Configuration properties

Full generated list: [reference/properties.md](../../reference/properties.md).

| Key | Type | Default | Meaning | When you would change it |
|---|---|---|---|---|
| `dc.platform.audit.enabled` | Boolean | `true` | Kill switch | Essentially never — this is a compliance control |
| `dc.platform.audit.queue-capacity` | Integer | `1000` | Bound on the in-memory queue; full-queue offers are **dropped with a WARN** | Raise when shedding is unacceptable and memory allows |

Two properties. Everything else — the action name, the resource expression — lives on the annotation,
because those are per-operation decisions rather than per-service ones. The same reasoning as
[`@LockedSchedule`](10-coordination.md) §6.6.

!!! warning "`enabled: false` is a compliance decision, not an operational one"
    Disabling audit during an incident to shed load means the period you most need evidence for is the
    period with none. If audit is causing an incident, raise the queue capacity or fix the sink.

### 3.5 Auto-configuration

| Class | Activates when | Contributes |
|---|---|---|
| `PlatformAuditAutoConfiguration` | Audit API on classpath, `audit.enabled != false` | `AsyncAuditor`, the `@Audited` advisor, capability descriptor |
| `AuditSecurityAutoConfiguration` | `CurrentUserAccessor` on classpath | The security-aware `ActorResolver` |
| `MessagingAuditSinkAutoConfiguration` | An `EventPublisher` bean | The messaging sink |
| `JdbcAuditSinkAutoConfiguration` | A `DataSource` bean, JDBC sink on classpath | The JDBC sink, plus its Flyway location |
| `LogAuditSinkAutoConfiguration` | Log sink on classpath | The log sink — **ordered after the other two** |

Each sink is `@ConditionalOnMissingBean(AuditSink.class)`, and the ordering (`@AutoConfigureAfter`, by
name for the messaging one — the constitution again) is what produces the precedence chain in §2.3.

The JDBC sink's Flyway location is appended automatically, so you do not register the audit table's
migration yourself. That registration is guarded by `@ConditionalOnClass` on Flyway, so a
JDBC-without-Flyway setup does not fail.

### 3.6 Extension points

| Extension | How | Effect |
|---|---|---|
| A sink the platform does not ship | An `AuditSink` bean | All three shipped sinks back off. Certify against the audit TCK |
| Synchronous auditing | An `Auditor` bean | The async one backs off — you own the latency trade |
| Change the actor | A `CurrentUserAccessor` bean ([Chapter 5](05-security-authz.md)) | Audit follows, along with data auditing and flags |

---

## 4. How-to Guide

### 4.1 Add the capability

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-audit</artifactId>
</dependency>
```

Check the startup log for which sink was selected. That line matters — §2.3.

### 4.2 Audit a method

```java snippet:book-12-audited
record CancelOrderCommand(String orderId, String reason) {
}

@Service
class OrderCancellationService {

    // Derive the resource from the ARGUMENTS, not #result — so a failure is still attributed.
    @Audited(action = "order.cancel", resourceExpression = "'order:' + #command.orderId()")
    public void cancel(CancelOrderCommand command) {
        // Recorded whether this returns or throws.
    }
}
```

Successful call → `Outcome.SUCCESS`. Thrown exception → `Outcome.FAILURE`, **and the exception is
re-thrown unchanged**, so auditing never changes behaviour.

!!! success "Best practice — name actions `noun.verb`, hierarchically"
    `order.create`, `order.cancel`, `declaration.approve`. It sorts usefully, it filters by prefix
    (`order.*` for everything touching orders), and it reads correctly in a report. Whatever you pick,
    pick it once — action names become the query language of your audit trail, and renaming one breaks
    every saved search and report built on it.

### 4.3 Audit programmatically

```java snippet:book-12-auditor
@Service
class BatchApprovalService {

    private final Auditor auditor;

    BatchApprovalService(Auditor auditor) {
        this.auditor = auditor;
    }

    void approveAll(List<String> declarationIds, String actor) {
        for (String id : declarationIds) {
            // One event per item, so a partial batch is fully attributable.
            auditor.record(new AuditEvent(
                    "declaration.approve",
                    actor,
                    "declaration:" + id,
                    Outcome.SUCCESS,
                    Instant.now(),
                    RequestContext.correlationId().map(CorrelationId::value).orElse(null),
                    Map.of("batch", true)));
        }
    }
}
```

Use this for what the annotation cannot express: conditional auditing, per-item outcomes in a batch,
or events raised deep inside a transaction.

!!! tip "`record` never throws and never blocks"
    You do not need a `try`/`catch` around it, and it will not slow the loop. What it *may* do is
    silently drop under overload — which for a large batch is a real possibility. §5.4.

### 4.4 Capture extra context

```java
@Audited(action = "order.cancel", resourceExpression = "'order:' + #command.orderId()")
```

The annotation records no `details`. When you need structured context — a reason, an amount, a
before/after — use the programmatic `Auditor`, which gives you the whole event to populate.

!!! warning "`details` goes wherever the sink goes, and stays for years"
    Audit retention is measured in years. Anything in `details` inherits that retention and that access
    control. Record identifiers and decisions, not payloads: `Map.of("reason", reason)`, not
    `Map.of("request", theWholeRequestObject)`.

### 4.5 Write a custom sink

```java snippet:book-12-audit-sink
@Configuration
class SiemAuditing {

    @Bean
    AuditSink auditSink(SiemClient client) {
        // Runs on the background worker, so blocking I/O is fine here.
        // Let exceptions propagate — the worker logs and drops the event.
        return event -> client.send(event.action(), event.actor(), event.outcome().name());
    }
}

interface SiemClient {
    void send(String action, String actor, String outcome);
}
```

Defining any `AuditSink` bean backs off all three shipped sinks.

!!! warning "One sink, not a chain"
    `@ConditionalOnMissingBean(AuditSink.class)` means your bean replaces the selection entirely — you
    do not get the log sink as well. If you want both, write a composite sink that delegates to your
    destination *and* to a `LogAuditSink`.

### 4.6 Route audit records separately

The log sink uses a dedicated logger, so you can give audit records their own appender and retention:

```xml
<logger name="AUDIT" level="INFO" additivity="false">
  <appender-ref ref="AUDIT_FILE"/>
</logger>
```

`additivity="false"` keeps audit records out of the ordinary application log, which is what makes a
different retention policy possible.

---

## 5. Operations and Management Guide

### 5.1 What to configure per environment

| Environment | Setting | Why |
|---|---|---|
| Local and test | Defaults (log sink) | No infrastructure |
| Production | The sink your regime requires — **and assert it** | The fallback is silent |
| Production | `queue-capacity` sized to your burst rate | §5.4 |
| Production | Retention on the sink's destination | The platform writes; it does not retain |
| All | `enabled: true` | It is a compliance control |

!!! warning "Retention is not the platform's job"
    The platform writes events. How long they live is a property of the destination: a log retention
    policy, a database archival job, a topic retention setting. If nobody has configured it, your audit
    trail expires on whatever default the store happens to have — which for a log index is often days.

### 5.2 What to monitor

| Signal | Where | Alert when |
|---|---|---|
| **Dropped events** | The `DC-AUDIT-0500` WARN, with its running total | **Ever.** Every drop is an unrecorded action |
| Selected sink | Startup WARN naming it | It is not the sink you expect — see below |
| Sink write failures | Worker log lines | Any sustained rate — the destination is unreachable |
| Audit volume | Events per minute at the sink | A sudden drop may mean auditing stopped working |
| Queue depth | Not exposed directly — infer from drops | Drops are the leading indicator |

!!! tip "Assert the selected sink at startup, do not just monitor it"
    ```java
    @Bean
    ApplicationRunner assertAuditSink(AuditSink sink) {
        return args -> {
            if (!(sink instanceof JdbcAuditSink)) {
                throw new IllegalStateException("audit must use the JDBC sink in this environment, got "
                        + sink.getClass().getSimpleName());
            }
        };
    }
    ```
    Fail startup rather than discovering during an audit that six months of records went to a log index
    that rotated. This converts a silent compliance gap into a deployment failure, which is where you
    want it.

!!! warning "A drop is not a metric, it is an incident"
    `DC-AUDIT-0500` means an action happened and was not recorded. For an availability signal a few
    drops would be noise; for a compliance control every one is a gap in the evidence. Alert on the
    first occurrence, not on a rate.

### 5.3 Troubleshooting

**No audit records anywhere.**

| Cause | Check |
|---|---|
| Starter missing | `mvn dependency:tree \| grep platform-starter-audit` |
| Capability disabled | `dc.platform.audit.enabled` |
| Self-invocation | `this.method()` bypasses the proxy — as everywhere |
| Non-public method | Cannot be advised |
| Records going to the log sink | Check the startup WARN. They may be in the log, not the table |

**The wrong sink was selected.** The chain is messaging → jdbc → log. A service with both a
`DataSource` and an `EventPublisher` gets **messaging**, which may not be what you intended. Assert it
(§5.2) or define your own `AuditSink` to be explicit.

**`resource` is null on failures.** The expression references `#result`, which does not exist on the
failure path. Derive from arguments (§2.2).

**`actor` is always `anonymous`.** Either the security capability is absent, or the work is happening
off the request thread where there is no authentication. Both are correct behaviour — the value is a
sentinel, not an error.

**Events are being dropped.** The queue is full: either the sink is slower than the event rate, or a
burst exceeded `queue-capacity`. Fix the sink first; raising capacity buys headroom, not throughput.

**Records stop at shutdown.** The worker is a daemon thread and the queue tail may be lost (§2.4). For
a graceful drain you need a synchronous `Auditor` or a sink that flushes on close.

### 5.4 Scaling and performance

- **`record` is a queue offer** — nanoseconds, non-blocking. It is not a latency concern.
- **Throughput is bounded by the sink**, drained by **one** worker thread. A sink taking 10 ms per
  event caps you at ~100 events/second regardless of queue size.
- **Queue capacity is burst headroom, not throughput.** With a 1,000-event queue and a 100/s sink, a
  burst of 5,000 sheds ~4,000 no matter how briefly it lasts.
- **Batch operations are the classic overflow.** §4.3's loop over 10,000 declarations produces 10,000
  events as fast as the loop runs. Either batch the audit records, raise the capacity for that
  operation, or accept the shedding knowingly.
- **The JDBC sink writes one row per event**, on the worker thread, using a pooled connection. Under
  load it competes with request traffic for the same pool.

!!! success "Best practice — audit business actions, not every method"
    Audit is not tracing. Every `@Audited` is a row retained for years and a slot in a bounded queue.
    Annotate the operations an investigator would ask about — approvals, cancellations, permission
    changes, data exports — not every service call. A trail that records everything is one nobody can
    search.

### 5.5 Security considerations

| Consideration | Detail |
|---|---|
| **Audit records are evidence** | Their integrity matters more than their availability. Consider append-only storage, or a write-only role |
| `actor` is only as trustworthy as authentication | It comes from `CurrentUser` ([Chapter 5](05-security-authz.md)). Weak auth means weak attribution |
| `details` retention | Years, with access controls that may be broader than your database's |
| Audit records may contain personal data | An actor identifier usually is. That interacts with erasure rights |
| Who can read the trail | Frequently broader than who can read production data. Scope accordingly |
| Tampering | The platform writes; it does not protect. A JDBC sink table an application can `UPDATE` is not tamper-evident |

!!! warning "The platform does not make your audit trail tamper-evident"
    It records events. Making them *evidence* — append-only, immutable, verifiable — is a property of
    the destination: a write-only database role, S3 Object Lock, a WORM store, or a signed log. If your
    regime requires tamper-evidence and your sink is a table the service can update, you have a
    trail, not evidence.

---

## 6. Deep Dive

### 6.1 The worker loop, and what its shape guarantees

```java
if (!queue.offer(event)) {                    // non-blocking: shed rather than block
    long total = dropped.incrementAndGet();
    log.warn("[DC-AUDIT-0500] audit queue full; dropped event action={} (total dropped={})", ...);
}
```

and, on the worker:

```java
while (running || !queue.isEmpty()) {
    AuditEvent event = queue.poll(POLL_INTERVAL, MILLISECONDS);
    ...
}
```

Three details worth extracting:

- **`offer`, not `put`.** `put` would block the request thread when full — precisely the coupling the
  design exists to avoid. `offer` returns false and sheds.
- **`running || !queue.isEmpty()`** means the worker drains what is queued when shutdown begins. It is
  a *best-effort* drain, not a guaranteed one, because the thread is a daemon and the JVM may exit
  first.
- **A running dropped-count in the WARN.** Not just "an event was dropped" but "this is the 4,318th" —
  so a single log line tells an operator the scale of the gap without aggregating.

### 6.2 Why exceptions propagate out of a sink

The SPI says: *let exceptions propagate — the worker logs and drops a failed event.*

That inverts the usual advice ("handle your own errors"), and it is right here. If sinks caught their
own exceptions, each would decide independently what to do — retry? swallow? log? — and the behaviour
would vary by sink. Letting them propagate puts the policy in **one** place: the worker logs and drops.

The consequence is worth stating: a sink failure loses **that event**, not the queue, and definitely not
the request. A sink that wants retry semantics must implement them internally and decide when to give
up.

### 6.3 Why the annotation records no `details`

`@Audited` has exactly two attributes. It could have taken a map of SpEL expressions for `details`, and
deliberately does not.

Two reasons. **Annotation values must be compile-time constants**, so a details map would be a string
array of expressions — verbose, unreadable, and unverified until runtime. And **details are where PII
accumulates**: making them easy to add at the annotation makes it easy to add them thoughtlessly, and
they persist for years.

Forcing programmatic `Auditor` use for `details` means someone wrote code, in a method body, that a
reviewer can see. That friction is the feature.

### 6.4 Sink precedence, and why messaging wins

Messaging → JDBC → log. Messaging first is not arbitrary:

- **Messaging decouples** audit from the service's own database, so audit volume does not compete with
  request traffic for the connection pool, and an audit-store outage does not touch the service.
- **It centralises.** One consumer writes every service's audit events to one store, so the schema and
  retention are decided once rather than per service.
- **It survives the service.** Events already published are durable in the broker even if the instance
  dies.

JDBC is second because it is *available* — a service with a database needs no extra infrastructure —
and log is last because it always works.

!!! warning "The messaging sink inherits messaging's delivery semantics"
    At-least-once ([Chapter 7](07-messaging-events.md) §2.2), which means **duplicate audit records**.
    Your audit consumer must deduplicate, or your trail double-counts. And if the publish fails, the
    worker drops the event — the DLQ path applies to *handling*, not to a failed publish.

### 6.5 What `enabled=false` actually does

The same shape as every platform kill switch: no `Auditor`, no advisor, no sinks. `@Audited` becomes
inert — the method still runs, and nothing is recorded.

Unlike [caching](09-cache-redis.md) §6.4 or [resilience](06-restclient-resilience.md) §6.4, there is no
underlying library default to fall back to. Disabling audit disables auditing, completely and silently.

That makes it the most dangerous kill switch in the platform, because nothing fails and the gap is
invisible until someone asks for records that do not exist. §3.4.

### 6.6 Common pitfalls

| Pitfall | Why it happens | What to do |
|---|---|---|
| `resourceExpression = "#result.id"` | It reads naturally | Null resource on every failure — the events you most need |
| Trusting the sink fallback | It always works | You may be silently non-compliant. Assert the sink |
| Auditing every method | It seems thorough | Years of storage, a queue that sheds, a trail nobody can search |
| Putting payloads in `details` | It is convenient context | Years of retention for data you did not mean to keep |
| Self-invocation | Nothing warns you | No audit record, silently |
| Assuming records are guaranteed | "It's a compliance control" | Best-effort: sheds under load, may lose the tail at shutdown |
| Treating drops as a metric | It is a WARN like any other | Every drop is an unrecorded action |
| Custom sink expecting to compose | It looks additive | It replaces all three. Write a composite |
| Assuming the trail is tamper-evident | Audit implies it | The platform records; the destination protects |
| Disabling audit during an incident | It sheds load | The period you most need evidence for has none |
| Not deduplicating messaging-sink records | Messaging looks reliable | At-least-once means duplicates |

---

## 7. Exercises and Hands-on Labs

Starting point: [`examples/example-golden-path`](../../examples.md), which uses `@Audited` on its
order service.

### Lab 1 — Basic: audit a success and a failure

**Goal.** Confirm both outcomes are recorded, and see which sink is live.

**Steps.**

1. Find the startup line naming the selected sink. Which is it, and why that one?
2. Add `@Audited(action = "order.cancel", resourceExpression = "'order:' + #id")` to a method.
3. Call it successfully. Find the record. What are all seven fields?
4. Make it throw. Find that record. What is the `outcome`, and did the exception still reach the caller?
5. Call it **unauthenticated**. What is the `actor`?
6. Take the `correlationId` from a record and find the matching application log lines.

**Expected outcome.** Step 4 gives `FAILURE` with the exception propagating unchanged — auditing does
not alter behaviour. Step 5 gives `anonymous`. Step 6 is the point of the correlation id: an audit
record is an entry point into everything else that happened.

**Hints.**

- The log sink writes under the `AUDIT` logger, not your class's.
- If the JDBC sink is active, the table's migration was contributed automatically — look for it.

**How to verify.** Two tests asserting a `SUCCESS` record on the happy path and a `FAILURE` record when
the method throws.

### Lab 2 — Intermediate: get the resource expression wrong, then right

**Goal.** Reproduce the `#result` trap and fix it.

**Steps.**

1. Use `resourceExpression = "#result.id"`. Call successfully — is the resource recorded?
2. Make the method throw. What is the resource now? Why?
3. Change to an argument-derived expression. Repeat both. Compare.
4. Write down which of the four records an investigator could actually use.
5. Now add structured `details` — try the annotation first. Can you? Then use `Auditor` (§4.3).
6. Route audit records to a separate appender (§4.6) and confirm they no longer appear in the
   application log.

**Expected outcome.** Step 2 records a **null resource on the failure** — an audit record saying
"someone failed to cancel *something*". Step 3 fixes it. Step 5 shows the annotation cannot carry
details at all, which §6.3 explains as deliberate friction.

**Hints.**

- SpEL failures are usually silent here — log the recorded event to see what was actually derived.
- `additivity="false"` is what stops double-logging in step 6.

**How to verify.** A test asserting the resource is non-null on **both** paths.

### Lab 3 — Advanced: overflow the queue, then decide what you actually want

**Goal.** Reproduce shedding, understand the trade, and implement the alternative.

**Steps.**

1. Set `queue-capacity: 10`. Write a sink that sleeps 100 ms per event.
2. Fire 200 audited operations quickly. How many records arrive? How many `DC-AUDIT-0500` warnings?
3. Confirm every business operation **succeeded** despite the dropped audits. That is the trade.
4. Compute the sustainable throughput of your sink. Does raising `queue-capacity` change it?
5. Replace the async auditor with a **synchronous** `Auditor` bean. Repeat step 2. How many records
   now? What happened to request latency?
6. Kill the process mid-burst with the async auditor. How many queued events were lost?
7. Write the paragraph you would give a compliance officer describing the guarantee, accurately.
8. Now write a composite sink that writes to your destination **and** the log sink, so a destination
   failure still leaves a local trace.

**Expected outcome.** Step 2 drops most events while every request succeeds. Step 4's answer is
**no** — capacity is burst headroom, not throughput. Step 5 records everything and makes request
latency the sink's latency. Step 6 loses the queue tail. **Step 7 is the deliverable**: an honest
description, not a reassuring one.

**Hints.**

- The WARN carries a running dropped-count — read it rather than counting log lines.
- Step 7 should contain the words "best-effort" and should describe both the shedding and the shutdown
  behaviour.

**How to verify.** A test asserting the business operation succeeds even when the sink throws on every
write.

---

## 8. Checklist / Quick Reference

**Add it**

```xml
<dependency>
  <groupId>ae.gov.dubaicustoms.platform</groupId>
  <artifactId>platform-starter-audit</artifactId>
</dependency>
```

**Use it**

```java
@Audited(action = "order.cancel", resourceExpression = "'order:' + #command.orderId()")
public void cancel(CancelOrderCommand command) { }     // SUCCESS or FAILURE — both recorded

auditor.record(new AuditEvent(action, actor, resource, Outcome.SUCCESS,
                              Instant.now(), correlationId, Map.of("reason", reason)));

AuditSink sink = event -> siem.send(event);            // replaces ALL shipped sinks
```

**The event**

`action` · `actor` · `resource`* · `outcome` · `at` · `correlationId`* · `details`
&nbsp;&nbsp;(* nullable)

**Sink precedence**

`messaging` (an `EventPublisher` exists) → `jdbc` (a `DataSource` exists) → `log` (always)

**Properties**

| Key | Default |
|---|---|
| `dc.platform.audit.enabled` | `true` — a compliance control, not an ops knob |
| `dc.platform.audit.queue-capacity` | `1000` — burst headroom, **not** throughput |

**The guarantee, accurately**

> Best-effort. `record` never blocks and never throws. A full queue **sheds** with a
> `DC-AUDIT-0500` WARN. The worker is a **daemon** thread, so the queue tail may be lost at shutdown.
> Raise `queue-capacity`, or supply a synchronous `Auditor`, if that is unacceptable.

**Diagnose it**

```bash
grep -i "audit sink" startup.log                 # which sink is live?
grep "DC-AUDIT-0500" app.log                     # dropped events, with a running total
curl -s localhost:8080/actuator/platform | jq    # audit[ACTIVE]?
```

**Rules of thumb**

- Derive `resource` from **arguments**, not `#result` — or failures record nothing useful.
- Assert the selected sink at startup. The fallback is silent, and silence looks like compliance.
- Every dropped event is an unrecorded action. Alert on the first one.
- Audit business actions, not every method.
- `details` is retained for years. Identifiers and decisions, not payloads.
- The platform records; the **destination** makes it tamper-evident.
- A custom sink replaces all three. Compose deliberately.
- The messaging sink inherits at-least-once — deduplicate downstream.
- Never disable audit to shed load during an incident.
- Self-invocation bypasses the proxy. Again.

---

**Next:** [Chapter 13 — Runtime Controls: Rate Limiting and Feature Flags](13-ratelimit-flags.md), the
two ways to change a running system without a deploy.

**Reference:** [modules/audit.md](../../modules/audit.md) ·
[configuration properties](../../reference/properties.md) ·
[feature catalog](../../reference/feature-catalog.md)

[Back to the book](../index.md)
