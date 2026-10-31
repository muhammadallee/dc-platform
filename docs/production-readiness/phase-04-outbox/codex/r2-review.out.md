I would **not approve yet**. Static review of `311d488`; no JVM or Docker tests run. “Met” below means supported by inspected code and tests, not independently executed here.

Remaining defects and regressions, ranked by severity:

1. **High — `REQUIRED` bypasses the post-commit guard.** `JdbcOutboxPublisher.java:129`  
   An `afterCommit` callback calls a default `TransactionTemplate` or `@Transactional(REQUIRED)` method. Its nearer frame is treated as a **new transaction**, although it joins the completed transaction. Disabled publication registers a callback that never fires; enabled publication has no owning commit, and a pool defaulting to `autoCommit=false` rolls it back on return. **Fix:** track transaction identity and completion state; an interceptor frame is insufficient evidence. [Spring transaction handling](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.1/spring-tx/src/main/java/org/springframework/transaction/support/AbstractPlatformTransactionManager.java).

2. **High — the dispatcher’s different stack check causes premature delivery.** `TransactionalDispatcher.java:28`  
   An outer `afterCommit` opens `REQUIRES_NEW`, publishes a domain event, then rolls back. The dispatcher sees the outer `invokeAfterCommit` anywhere on the stack and executes the action immediately. Handlers—and relay fallback without an available outbox—observe a rolled-back event. **Fix:** distinguish the current transaction from the completed outer transaction. **Yes, this difference is a defect.** Simply copying the publisher’s flawed check would not suffice.

3. **High — non-autocommit still does not prove transaction ownership.** `JdbcOutboxPublisher.java:108`  
   Configure outbox pool A with `autoCommit=false`; transact on B, then prime A with `JdbcTemplate`. The synchronized A connection passes `canPublish()`, but B’s commit does not commit A’s outbox insert. **Fix:** verify ownership by the supported transaction manager, not connection mode; add this variant to the foreign-resource test. [Spring connection binding](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.1/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceUtils.java).

4. **High — timeout bounds the caller, not outstanding Rabbit work.** `RabbitEventTransport.java:144`, `:156`, `:264`  
   A publish/acquisition that ignores interruption survives timeout. Repeated direct publishes, outbox retries, or DLQ republishes accumulate senders and channel resources during a prolonged stall; `close()` does not track them. The test’s interruptible sleep misses this. **Fix:** bound outstanding sends, own and terminate the underlying publishing resources on timeout, and drain/cancel them during shutdown. Rabbit contains uninterruptible waits; interrupt alone is not cancellation. [Rabbit channel implementation](https://raw.githubusercontent.com/rabbitmq/rabbitmq-java-client/main/src/main/java/com/rabbitmq/client/impl/AMQChannel.java).

5. **High — moving the shared template to another thread changes publishing semantics.** `RabbitEventTransport.java:135`, `:146`  
   A caller’s thread-bound routing selection is absent on the sender, potentially publishing successfully to the default broker. A context-dependent mandatory expression can pass the caller-side check and evaluate false during the actual send, allowing acknowledged unroutable loss. Template invocation/channel state is also thread-bound. **Fix:** resolve and enforce the actual publishing configuration on a transport-owned path; explicitly preserve supported context or reject incompatible configurations. [RabbitTemplate implementation](https://raw.githubusercontent.com/spring-projects/spring-amqp/v4.1.0/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/core/RabbitTemplate.java).

6. **High — pool reconstruction can select a different database.** `OutboxConnections.java:61`  
   Hikari may use an explicitly supplied `dataSource` or `dataSourceClassName` while retaining an ignored JDBC URL. This code copies that URL instead of the effective source. If both databases contain the table, startup succeeds while the drainer reads the wrong outbox. Without a URL, these otherwise valid Hikari configurations are rejected. **Fix:** respect Hikari’s source precedence and require an explicit independent connection factory where faithful reconstruction is unavailable. Masked password getters and credentials providers also require supported credential extraction, rather than assuming the individual getters are authoritative. [Hikari source and credential selection](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/PoolBase.java).

7. **High — restart can revive an unfinished old batch.** `OutboxDrainer.java:177`, `:159`, `:319`  
   A send ignores interruption beyond `stopTimeout`; `stop()` returns after discarding ownership. Restart clears the shared `stopping` flag. When the old send finishes, its remaining batch resumes alongside the new worker—the identity check protects only the outer loop. Both manipulate shared `inBatch` and `pause`. Maintenance is never joined either. **Fix:** retain ownership until both threads terminate, use cancellation state per worker generation, and serialize batch admission with stopping.

8. **High — accepted Oracle sources still escape network bounds.** `OutboxConnections.java:73`, `:99`  
   An Oracle `DriverManagerDataSource` takes the unpooled branch without receiving read/connect properties; a stalled commit or LOB read can retain locks indefinitely. Additionally, `read-timeout=0` reaches Oracle without validation. **Fix:** apply positive, representable network bounds to every supported physical source, or require an explicitly bounded custom factory.

9. **Medium — `pool-size=1` defeats independent maintenance.** `OutboxDrainer.java:266`, `OutboxProperties.java:36`  
   A slow batch holds the sole connection; maintenance repeatedly hits acquisition timeout, so metrics and retention remain blocked by publishing despite the second thread. **Fix:** require at least two connections for this architecture, or reserve separate maintenance capacity.

10. **Medium — deferred parking WARN loses its trace.** `OutboxDrainer.java:443`  
    A traced rejected event reaches parking. Its deferred WARN runs at line 326, after `context.restore(...)` has closed, so the parking diagnostic lacks the original trace. **Fix:** restore the row’s context around the deferred notification.

Additional checks: a **late confirm is confined to its own `CorrelationData`**; it cannot mark a later attempt sent. A timeout followed by successful late publication can legitimately duplicate, but does not excuse unbounded outstanding work. `failed = !stopping` does **not** erase the persisted failure or row backoff; by itself it only suppresses the worker pause during shutdown. `schema` and `catalog` are copied. Not inheriting application `readOnly` or transaction isolation is reasonable for a writable drainer using Oracle’s default isolation.

Round-1 dispositions:

| Finding | Disposition |
|---|---|
| F1 | **Partial** — proxy handling fixed; non-autocommit foreign resources still pass (#3). |
| F2 | **Partial** — direct callback covered; `REQUIRED` bypass and dispatcher regression remain (#1–2). |
| F3 | **Confirmed fixed** — metrics configuration is no longer copied. |
| F4 | **Confirmed fixed** — unknown pooled/wrapped sources are refused. |
| F5 | **Partial** — URL-based Hikari covered; other accepted sources escape bounds (#8). |
| F6 | **Confirmed fixed** — retention explicitly enables autocommit. |
| F7 | **Not fixed** — **accept Medium downgrade**, with documented restart/custom-factory limitation carried to Gate E. |
| F8 | **Partial** — actual-message check added, but thread-dependent reevaluation remains (#5). |
| F9 | **Confirmed fixed** — ambiguous connection-close nacks remain retryable. |
| F10 | **Partial** — caller deadline added; resource cancellation remains missing (#4). |
| F11 | **Confirmed fixed** — malformed header documents are parked locally. |
| F12 | **Partial** — ordinary idempotence/error survival improved; unfinished-worker ownership remains unsafe (#7). |
| F13 | **Confirmed fixed** for the full-batch timing flaw; **accept the different fix**. Amend R4.1/AC14 to completed-prefix commit; lifecycle defects remain separate. |
| F14 | **Confirmed fixed** — explicit auto-configuration ordering added. |
| F15 | **Confirmed fixed** — optional providers resolved per call. |
| F16 | **Confirmed fixed** — tracing signatures isolated behind guarded configuration. |
| F17 | **Partial** — accept that classification, **not closure**: observation-enabled propagation, principal scope and parking-log scope remain unresolved. |
| F18 | **Confirmed fixed** — `pwd`, propagation allow-list and merged-header validation added. |
| F19 | **Confirmed fixed** — raw destinations removed from drainer WARNs. |
| F20 | **Partial** — minimum durations checked; upper/arithmetic bounds and `readTimeout` validation remain incomplete. |
| F21 | **Partial** — freshness exposed; single-connection maintenance starvation remains (#9). |
| F22 | **Confirmed fixed** within the stated sequential replacement/single-drainer scope. |
| F23 | **Confirmed fixed** — parking notifications follow successful commit. |

**Accept A3:** parking on classified rejections avoids parking valid events merely because an outage lasts too long.

Acceptance assessment:

| AC | Status |
|---|---|
| AC1 | **Partly** — ordinary/deferred rollback coverage added; ownership and completed-transaction holes remain. |
| AC2 | **Not met** — foreign non-autocommit connection passes the guard. |
| AC3 | **Met** — no additional datasource or transaction-manager bean. |
| AC4 | **Met** — inspected Oracle tests cover distinct full batches, multiple sizes and large payloads. |
| AC5 | **Partly** — recovery logic improved; sender accumulation and lifecycle defects remain. |
| AC6 | **Not met** — outstanding work is unbounded; mandatory reevaluation can permit loss. |
| AC7 | **Pending Gate F** — actual SIGKILL and duplicate-bound evidence absent. |
| AC8 | **Not met as written** — timeout/late-publication retries can duplicate without a crash; clarify before Gate F. |
| AC9 | **Met under A3** — rejection counting, parking and same-ID replay supported. |
| AC10 | **Pending Gate F** — independent pool supported; exhaustion/isolation needs consumer evidence. |
| AC11 | **Met** — bounded committed deletes protect pending/parked rows. |
| AC12 | **Partly** — storage/redaction improved; tracing and restoration gaps remain. |
| AC13 | **Partly** — disabled publication can disappear through the `REQUIRED` callback path. |
| AC14 | **Partly** — accept completed-prefix shutdown; restart/termination races and SIGTERM evidence remain. |
| AC15 | **Partly** — DDL/grants fixtures present; Oracle bounds incomplete across accepted sources. |
| AC16 | **Partly** — relay ordering fixed; transaction guard and dispatcher defects remain. |
| AC17 | **Partly** — real return/missing-exchange coverage; timeout/alarm cleanup only mocked or untested. |
| AC18 | **Pending Gate F** — consumer regressions, downstream compilation, golden path and CI evidence outstanding. |
