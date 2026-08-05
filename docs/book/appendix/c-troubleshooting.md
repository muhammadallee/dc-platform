# Appendix C — Troubleshooting Cookbook

> Symptom, cause, fix. Written for the person who is being paged.

Organised by **what you observed**, not by capability. If you know which capability is involved, the
chapter's own *Operations* section goes deeper.

---

## The first four commands

Before any hypothesis. These take under a minute and routinely eliminate half of them.

```bash
# 1. What platform behaviour is actually live here, with which providers?
curl -s localhost:8080/actuator/platform | jq

# 2. Which failures, by stable code — not by message text
#    (log query)  service:"orders-service" AND code:* | count by code

# 3. Is a dependency's circuit breaker open?
curl -s localhost:8080/actuator/metrics/resilience4j.circuitbreaker.state | jq

# 4. Why is this property's value what it is?
curl -s localhost:8080/actuator/env/<the.property> | jq
```

!!! success "Command 1 is the one people skip"
    `/actuator/platform` is runtime truth — what is live in *this instance*, with *which provider* —
    as opposed to build-time inference from a POM that may not match what was deployed. Two minutes
    here saves twenty when it turns out production is running the in-memory transport.

---

## 1. Nothing platform-ish is happening

### The startup banner is missing

1. Starter on the classpath? `mvn dependency:tree | grep platform-starter-core`
2. Disabled? `curl -s localhost:8080/actuator/env/dc.platform.core.enabled`
3. Only the banner off? Check `banner-enabled` — `/actuator/platform` is unaffected
4. Still nothing? `--debug` and read the **negative matches** section

### A capability is not active

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--debug
```

The **negative matches** section names the exact condition that failed — usually
`@ConditionalOnClass did not find required class …`, which means a starter is missing.

→ [Primer 1](../primer/01-spring-boot.md) §2

### `/actuator/platform` says `messaging[INACTIVE]`

No single `EventTransport` bean: either **no** transport starter, or **more than one**.

---

## 2. Correlation and logs

| Symptom | Likely cause | Fix |
|---|---|---|
| No `correlationId` on log lines | Not a servlet web app | Expected — open a scope yourself → [1](../chapters/01-core.md) §6.2 |
| | Work moved to another thread | `@Async`, an executor, a `CompletableFuture` → [1](../chapters/01-core.md) §6.1 |
| | A filter registered ahead of the platform's | Give yours a lower precedence than `HIGHEST_PRECEDENCE` |
| | `generate-if-missing: false` and no header sent | Set it back to `true`, or fix the caller |
| | Logging capability absent | MDC is populated, but nothing lifts it into fields |
| Logs are plain text, not JSON | `local` profile active | `jq .activeProfiles` on `/actuator/env` |
| | Your own `logback-spring.xml` | Boot prefers it. Delete it |
| | `logging.config` overridden | `curl -s …/actuator/env/logging.config` names the winning source |
| The id changes mid-request | A `RequestContext.open` not in try-with-resources | Fix the scope |
| No `X-Correlation-Id` on the response | Something called `response.reset()` | Check CORS/header filters |

!!! tip "The fleet-level version of this"
    A log query for `NOT _exists_: correlationId` on your service's index tells you immediately whether
    something is escaping the context — a new async path, a new thread pool, a newly registered filter.

---

## 3. Errors and responses

| Symptom | Likely cause | Fix |
|---|---|---|
| Errors come back as HTML or plain JSON | An application `@RestControllerAdvice` catching first | Yours wins by design. Delete it |
| | Errors starter missing or disabled | `dependency:tree`, then `…env/dc.platform.errors.enabled` |
| | It is a **401 or 403** | Expected — those come from the security chain. Add `platform-starter-security` |
| `detail` is `"An unexpected error occurred."` | The exception is not a `PlatformException` subtype | Working as designed. The real message is in the log |
| Validation not firing | No `@Valid` on the parameter | The most common cause by a wide margin |
| | Class not `@Validated`, for method validation | The annotation goes on the class |
| | Self-invocation | → §9 |
| `@Ulid` accepting `null` | Bean Validation convention | Add `@NotNull` |
| A rejected password appears in the response | The field is not named `password`/`secret`/`token` | Rename it, or add a `ProblemDetailCustomizer` |
| A clean 404 became a 500 | A `ProblemDetailCustomizer` threw | Customizers must never throw |

**Investigating a 4xx you cannot explain:** business failures log at `DEBUG` with no stack, deliberately
— a 404 is expected traffic, not an incident.

```
logging.level.ae.gov.dubaicustoms.platform.errors=DEBUG
```

---

## 4. Security

| Symptom | Likely cause | Fix |
|---|---|---|
| Everything is 401 | No `issuer-uri` | The `FailureAnalyzer` should have failed startup — check the boot log |
| | Wrong issuer | `iss` must match exactly, trailing slash included |
| | Clock skew | A few minutes is enough. Run NTP |
| | Stale key cache after rotation | Check `jwk-set-uri` reachability |
| Health checks started failing | `permit-paths` was set and **replaced** the default six | Use a `SecurityCustomizer` instead |
| `@RequiresPermission` not enforced | **No `CurrentUserAccessor` bean → no advisor** | Check `/actuator/beans` for `requiresPermissionAdvisor` |
| | Self-invocation | → §9. This one is a **security bypass** |
| | `final` class or method | Cannot be proxied |
| | Wrong `roles-claim` | Decode the token and look |
| 403 that should be 200 | Exact string matching — `orders:*` does not grant `orders:read` | Write a provider for patterns |
| 401 when you expected 403 | The request was not authenticated at all | Correct behaviour. Check the token |
| Deny-by-default seems gone | **A `SecurityFilterChain` bean somewhere in your app** | It silently replaces the whole chain |

!!! warning "The most dangerous silent state in the platform"
    `@RequiresPermission` with no `CurrentUserAccessor` bean: the advisor is **never created**, so the
    annotation is documentation and **the method runs unprotected**. Nothing fails. Assert the advisor
    exists in a `prod`-profile context test.

---

## 5. Outbound calls

| Symptom | Likely cause | Fix |
|---|---|---|
| Calls hang far longer than configured | Retry multiplying the budget | attempts × read-timeout + backoff → [6](../chapters/06-restclient-resilience.md) §2.1 |
| | The client was **not** built by the factory | An injected `RestClient.Builder` has no timeouts |
| | Timeout set under the wrong client name | `curl -s …/actuator/configprops \| jq '.. \| .restclient?'` |
| Breaker never opens | Count-based window, size **10** | It needs ten outcomes. Check `…_buffered_calls` |
| Breaker opens constantly | 4xx counted as failures | `ignore-exceptions` — a 400 is not the dependency failing |
| Retries not happening | Self-invocation | → §9 |
| | The exception type is not retried | Check `retry-exceptions` |
| Token not relayed | Not authenticated, or not on the request thread | `SecurityContextHolder` is thread-local |
| | Guards not met | `/actuator/beans` for `platformTokenRelayCustomizer` |
| **A token reached a third party** | The relay is **destination-blind** | → [6](../chapters/06-restclient-resilience.md) §5.5. Strip it per client name |

---

## 6. Messaging

| Symptom | Likely cause | Fix |
|---|---|---|
| Startup failed, no transport | No `EventTransport` bean | The `FailureAnalyzer` names the starters |
| Published, but the handler never runs | Destination mismatch | Publisher and `@EventHandler` must agree exactly. Silent otherwise |
| | `eventType` filter mismatch | It compares `@EventType.value()`, **not** the class name |
| | Handler bean not a Spring bean | A `new`-ed object has no handlers |
| | Different consumer group | Same group load-balances; different groups each get a copy |
| | The handler throws every time | Check the DLQ — it may be running and failing |
| Messages in the DLQ | Working as designed | The ERROR log names the method, attempts, and destination |
| Duplicate side effects | At-least-once, working as specified | **Your handler is not idempotent** |
| Domain handlers never run | The transaction rolled back | Correct behaviour |
| | Parameter type mismatch | Dispatch is by **exact runtime type**, not assignability |
| Domain handlers run when they should not | No transaction was active | Dispatch was immediate. Check `@Transactional` and self-invocation |
| Consumers stall | A poison message occupying a consumer | attempts × backoff per message → [7](../chapters/07-messaging-events.md) §5.4 |

---

## 7. Data

| Symptom | Likely cause | Fix |
|---|---|---|
| Startup fails: Flyway missing | Working as designed | Add `flyway-core`, or `require-migrations: false` **with a comment** |
| `LazyInitializationException` after adopting | `open-in-view` is now `false` | An existing N+1 became **visible**. Fetch join, `@EntityGraph`, or a projection |
| Audit columns null | **`@EntityListeners(AuditingEntityListener.class)` missing** | The most common cause. Use a `@MappedSuperclass` base |
| `created_by` is `"system"` | No security, or a background thread | Both are correct behaviour |
| Money round-trips wrong | `@Convert` missing, or the column is short | `MONEY_LENGTH` is 40 |
| Timestamps off by hours | `hibernate.jdbc.time_zone` overridden | Check `/actuator/env` |
| Batching not happening | `GenerationType.IDENTITY` | **Disables insert batching.** Use a sequence |
| **Requests queue, database idle** | A remote call inside `@Transactional` | → §10. The classic |

```bash
curl -s localhost:8080/actuator/metrics/hikaricp.connections.pending | jq   # the earliest DB alert
```

---

## 8. Cache, coordination, storage, audit, limits

### Cache and Redis

| Symptom | Cause | Fix |
|---|---|---|
| Nothing is cached | Self-invocation → §9 | |
| Stale data across instances | Caffeine is **per-instance** | Use `cache-redis` for fleet-wide invalidation |
| A `SCAN`/`KEYS` pattern returns nothing | The pattern needs the key **prefix** | Looks exactly like data loss |
| Redis out of memory | Operational keys with no TTL, or a cache with no TTL | Check `maxmemory-policy` before raising `maxmemory` |
| A cache flush logged everyone out | Session state in the cache manager | Use the `redis` capability instead |

### Locking, scheduling, idempotency

| Symptom | Cause | Fix |
|---|---|---|
| The job runs on every instance | **No `LockManager`** — the startup WARN | Add a locking starter |
| | `@LockedSchedule` missing, or self-invocation | |
| The job never runs anywhere | A lock row not expiring | A crashed holder with a long `atMost`, or clock skew |
| Two instances ran it anyway | The lease expired mid-execution | Compare runtime with `atMost`. Fencing prevents theft, not overlap |
| `Optional.empty()` but it ran | **The action returned `null`** | Return a sentinel |
| Everything gets 409 | The key is not stable across retries | A UUID or correlation id in `keyExpression` |
| Duplicates still get through | An in-memory store, or a differing key | The store must be shared **and** atomic |

### Storage and files

| Symptom | Cause | Fix |
|---|---|---|
| Startup fails: root unwritable | Read-only filesystem, or an unmounted volume | The `FailureAnalyzer` names the path |
| `INVALID_KEY` | A traversal segment, backslash, NUL, or absolute key | **The security control working.** Sanitise first |
| A valid-looking file rejected | The magic-byte sniff did not recognise it | `xxd file \| head -1`. A BOM or leading whitespace is common |
| Oversized upload → container error | Rejected at the servlet layer, before your controller | By design. Handle `MaxUploadSizeExceededException` for a nicer body |
| S3 pool exhausted | **Unclosed `StoredObject` or `list` stream** | Symptom appears hours after the cause |
| Objects missing after restart | `fs.root` is the temp directory | Set a durable path |
| Downloads OOM | Something buffers instead of `StreamingDownloads` | |

### Audit, rate limiting, flags

| Symptom | Cause | Fix |
|---|---|---|
| No audit records | Self-invocation, or the log sink was selected | Check the startup WARN naming the sink |
| Wrong sink selected | Chain is messaging → jdbc → log | A `DataSource` **and** an `EventPublisher` gives messaging |
| `resource` null on failures | `resourceExpression` uses `#result` | Derive from **arguments** |
| Events dropped (`DC-AUDIT-0500`) | The sink is slower than the event rate | Fix the sink; capacity is burst headroom, not throughput |
| Limit not enforced | In-memory provider with N replicas → **N× the limit** | Use Redis |
| | `DC-RATELIMIT-0500` — **failing open** | Redis unreachable. You have no limiting right now |
| Everything throttled | The key collapses to one value | Log the derived key |
| A flag will not turn on | **Name mismatch** — unknown flags are silently `false` | Declare names as constants |
| | Flipped on one instance only | The endpoint changes that JVM only |
| A gated method returns `null` | The flag is off and the return type is a bare object | Return `Optional` |

---

## 9. The cause that explains a third of this appendix

**Self-invocation.** Calling an annotated method from another method on the **same bean** bypasses the
proxy, and therefore the annotation:

```java
@Service
class OrderService {
    @RequiresPermission("orders:read")
    Order get(String id) { ... }

    List<Order> getAll(List<String> ids) {
        return ids.stream().map(this::get).toList();   // NO permission check
    }
}
```

Affects `@Cacheable`, `@Transactional`, `@Validated`, `@RequiresPermission`, `@Audited`, `@RateLimited`,
`@Idempotent`, `@FeatureGate`, `@LockedSchedule`, `@Retry`, `@CircuitBreaker` — everything.

Also breaks it: a `final` class or method, a package-private method, or a `new`-ed object.

!!! warning "The consequences are not equal"
    A bypassed cache costs performance. A bypassed `@RequiresPermission` is a **security bypass**, and a
    bypassed `@Idempotent` is a duplicate charge. When you find self-invocation, check *which*
    annotation was bypassed before deciding how urgent it is.

**Fix:** annotate at the boundary callers actually reach, or split the class.

---

## 10. The other recurring cause

**A remote call while holding a transaction.**

```java
@Transactional
void process(String orderId) {
    Order order = repository.findById(orderId).orElseThrow();
    String status = paymentClient.check(orderId);      // up to 10s, holding a connection
    order.setStatus(status);
}
```

Ten concurrent requests exhaust a default pool of ten while the **database sits idle**. The symptom is
`hikaricp_connections_pending` climbing with no slow queries.

Applies equally to publishing a message, acquiring a distributed lock, and writing to object storage.

**Fix:** read in a transaction, call outside one, write in a second transaction.

---

## 11. Things that are working correctly

Worth checking before you debug them.

| Looks wrong | Actually |
|---|---|
| `detail` is a generic 500 message | Unmapped exceptions never leak. The real one is in the log |
| A 404 produces no log line | Business failures log at `DEBUG`, deliberately |
| `created_by` is `"system"` | No authenticated request. Correct |
| `actor` is `"anonymous"` | Same |
| `Optional.empty()` from `withLock` | Another instance holds it. Expected for a scheduled job |
| A 429 with `Retry-After` | The limiter working |
| A 409 on a repeated POST | Idempotency working. **The first attempt succeeded** |
| An unknown flag is `false` | Fail-safe-off |
| Requests allowed during a Redis outage | The limiter fails **open**, deliberately |
| A message in the DLQ | Bounded retry exhausted. The log names the handler |
| `INVALID_KEY` on a traversal key | The security control |
| `LazyInitializationException` | `open-in-view=false` revealing an existing N+1 |
| Startup fails without Flyway | The migration guard |
| A brand-new endpoint returns 401 | Deny-by-default |

---

## 12. Escalation

**To the platform team**, with:

1. `/actuator/platform` output — capabilities and providers
2. The **correlation id** of one failing request
3. The error `code`, not the message text
4. `/actuator/env` for any property you believe is misapplied — it names the winning source
5. The `--debug` condition report if a capability seems inactive

**Never** fork a platform module to work around something. Every mechanism in
[Extending the chassis](../crosscutting/extending.md) exists so that is unnecessary; if you believe it
is necessary, that is a bug report about the extension model.

---

## 13. The three failures that produce no symptom

Nothing degrades, no metric moves, every request succeeds. They will not appear in this appendix's
symptom tables, because there is no symptom.

| Silent failure | The only signal | Do this |
|---|---|---|
| `@LockedSchedule` running **unlocked** | A one-time startup WARN | Assert a `LockManager` bean exists in a `prod` context test |
| Audit **shedding** events | `DC-AUDIT-0500`, with a running total | Alert on the **first** occurrence |
| Rate limiter **failing open** | `DC-RATELIMIT-0500` | Alert on the **first** occurrence |

Add a fourth, which is not an error at all:

| Silent gap | Signal | Do this |
|---|---|---|
| No traces, because `otlp.enabled` is `false` | None whatsoever | Assert it in a `prod`-profile test |

!!! success "The general lesson"
    Everything in §1–§8 has a symptom you can search for. These do not. That is precisely why they need
    an **assertion at startup or an alert on first occurrence** rather than a place in a troubleshooting
    guide — by the time you are reading a troubleshooting guide, you already know something is wrong.

---

**Next:** [Appendix D — Version Compatibility Matrix](d-compatibility.md)

**Related:** [Observability strategy](../crosscutting/observability-strategy.md) — the 03:00 runbook ·
[Patterns and anti-patterns](../crosscutting/patterns.md) ·
[Runbook: local development](../../runbooks/local-dev.md)

[Back to the book](../index.md)
