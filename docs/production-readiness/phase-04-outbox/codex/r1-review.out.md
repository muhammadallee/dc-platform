I would not approve this phase yet. Static review of `de67f30` against `39e354d` found the following failures. No JVM, container or consumer tests were executed.

1. **High — Resource presence does not prove transaction participation.**  
   `JdbcOutboxPublisher.java:84–86`  
   **Scenario:** Begin a transaction on database B, then execute a JDBC query against outbox database A. Spring binds A’s connection for synchronization without starting a transaction on it. `hasResource(A)` now passes, and the outbox insert can auto-commit even if B rolls back. The existing other-database test never primes this resource. Conversely, `TransactionAwareDataSourceProxy` can cause false refusal because the transaction manager binds its target, while this check uses the proxy; the relay then silently falls back to after-commit delivery. [Spring connection binding](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.1/spring-jdbc/src/main/java/org/springframework/jdbc/datasource/DataSourceUtils.java), [JPA proxy handling](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.1/spring-orm/src/main/java/org/springframework/orm/jpa/JpaTransactionManager.java).  
   **Fix:** Resolve the transaction manager’s canonical resource consistently for both checking and insertion, and verify actual transactional ownership rather than mere synchronization. Reject auto-commit connections. Preserve `LazyConnectionDataSourceProxy` when that is the manager’s resource; do not indiscriminately unwrap everything to Hikari. Cover primed foreign resources, wrappers and `REQUIRES_NEW`.

2. **High — Publishing during an after-commit callback can lose the event.**  
   `JdbcOutboxPublisher.java:84`; `DomainEventRelay.java:58`; `AfterCommitOutboxPublisher.java:25`  
   **Scenario:** A `@DomainEventHandler` publishes another domain event. It runs after commit, while Spring’s transaction flags and resources can still be present. The relay sees `canPublish()==true` and inserts into a transaction that will receive no further commit. If insertion fails, the handler registrar logs and swallows the exception after the business commit has already succeeded. Disabled mode also registers a new `afterCommit` synchronization during iteration of an already captured synchronization list; that callback never runs. [Spring explicitly documents the post-commit resource trap](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html#afterCommit()).  
   **Fix:** Track transaction phase explicitly and refuse guaranteed publication after commit has started. Stage guaranteed relay events before the original commit. For intentionally post-commit work, require a separate transaction and document its weaker relationship to the original business commit. Disabled publication must execute immediately when already in the post-commit phase.

3. **High — Copying a metered Hikari pool can prevent startup.**  
   `OutboxConnections.java:40–51`  
   **Scenario:** The application pool already has a `MicrometerMetricsTrackerFactory`. `copyStateTo` copies it, then `setMetricRegistry(null)` throws because Hikari rejects that setter whenever a tracker factory exists—even when the new registry value is null. The current pool-copy test does not install application-pool metrics. [Hikari implementation](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java).  
   **Fix:** Build a fresh configuration from an explicit connection-settings allowlist, then install the drainer’s metrics. If retaining copying, clear mutually exclusive metrics settings conditionally and in the correct order.

4. **High — The fallback pool is not isolated from the application pool.**  
   `OutboxConnections.java:42`  
   **Scenario:** A tracing/metrics wrapper hides its underlying Hikari pool, or the application uses another connection pool. The new Hikari pool borrows and retains connections from that application pool. With a small main pool, the drainer can consume all request capacity, including while its borrowed connections are idle.  
   **Fix:** Construct independent physical connections from connection details or an explicitly supplied unpooled connection factory. Reject unsupported pooled/wrapped sources instead of silently creating a pool around a pool.

5. **High — Oracle bounds are inherited accidentally rather than applied.**  
   `OutboxConnections.java:35–52`; `OutboxDrainer.java:167–171`  
   **Scenario:** A JDBC-only service has no Phase 3 JPA post-processor, or supplies an already-started Oracle pool that that processor deliberately skips. The copied pool has no Oracle read/connect bounds. A stalled LOB read or commit can hold the worker and row locks indefinitely; statement query timeouts do not cover every such operation. The startup probe itself has no query timeout, and acquisition timeout is copied from application configuration, potentially effectively unlimited.  
   **Fix:** Apply Oracle network bounds directly when constructing the drainer’s physical source, set independent acquisition/validation bounds, and time-limit the probe. Apply this consistently without depending on application-pool bean processing.

6. **High — Retention silently rolls back when application auto-commit is disabled.**  
   `OutboxConnections.java:40`; `OutboxDrainer.java:393–406`  
   **Scenario:** `spring.datasource.hikari.auto-commit=false` is copied into the drainer pool. `deleteSent()` never commits. Multiple “bounded” deletes become one transaction, and returning the connection rolls them all back. Retention repeatedly reports deletions while retaining every row. [Hikari connection cleanup](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/pool/ProxyConnection.java).  
   **Fix:** Explicitly establish housekeeping transaction semantics and commit each bounded delete. Do not inherit application auto-commit/read-only defaults.

7. **High — Runtime credential rotation leaves the drainer using obsolete credentials.**  
   `OutboxConnections.java:40`  
   **Scenario:** Rotation updates the application Hikari pool through `setCredentials`/`setPassword`. Hikari’s configuration copy snapshots credentials into another holder; subsequent updates do not propagate. Existing drainer connections eventually expire, after which draining cannot recover despite the application pool working. [Hikari credential-copy implementation](https://raw.githubusercontent.com/brettwooldridge/HikariCP/HikariCP-7.0.2/src/main/java/com/zaxxer/hikari/HikariConfig.java).  
   **Fix:** Share a supported dynamic credentials provider or explicitly update/rebuild both pools during rotation.

8. **High — The mandatory check permits acknowledged, unroutable loss.**  
   `RabbitEventTransport.java:111`; `RabbitEventTransport.java:133`  
   **Scenario:** A customized template has a mandatory expression that evaluates true for the empty constructor probe but false for a real event—for example, based on an event header. Rabbit acknowledges and drops an unroutable non-mandatory publication; `getReturned()` stays null and the drainer marks it `SENT`. Mandatory is evaluated per actual message. [RabbitTemplate implementation](https://raw.githubusercontent.com/spring-projects/spring-amqp/v4.1.0/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/core/RabbitTemplate.java).  
   **Fix:** Enforce unconditional mandatory publishing on the transport-owned publishing path, or reject every actual message for which mandatory is false. Validate the publishing factory used by that template as well.

9. **High — Connection failures are classified as permanent message rejection.**  
   `RabbitEventTransport.java:153–154`; `OutboxDrainer.java:319–321`  
   **Scenario:** A connection repeatedly closes while confirms are outstanding. Spring synthesizes negative confirms; this code marks every nack `rejected=true`. After ten such outages, valid events become `PARKED` and recovery never drains them automatically. [Spring confirm semantics](https://docs.spring.io/spring-amqp/reference/amqp/template.html).  
   **Fix:** Treat channel/connection-close nacks and ambiguous outcomes as retryable. Park only explicitly classified permanent message failures. A bare negative acknowledgement is insufficient evidence of permanent rejection.

10. **High — The confirm timeout does not bound `send()`.**  
    `RabbitEventTransport.java:133–140`  
    **Scenario:** Broker resource alarms or a stalled socket block `rabbitTemplate.send()` before `awaitConfirm()` begins. The five-second timeout never starts. This also blocks direct publishers and `EventHandlerRegistrar`’s DLQ republish, including services without an outbox. Rabbit documents that `basicPublish` can block under resource alarms. [Rabbit Java client guide](https://www.rabbitmq.com/client-libraries/java-api-guide).  
    **Fix:** Bound the entire operation: connection/channel acquisition, publication/write and confirmation. Timeout handling must terminate the underlying publishing operation/channel; simply abandoning a future would leave an uncontrolled sender running.

11. **High — One unreadable header document can stop the table indefinitely.**  
    `OutboxDrainer.java:274`  
    **Scenario:** An old or manually repaired row contains malformed JSON, or JSON rejected by the reader’s constraints. Parsing happens outside the per-row failure handler. Every batch containing that row rolls back; it receives neither backoff nor parking. Earlier successfully sent rows in that batch can also be delivered repeatedly.  
    **Fix:** Handle decoding as a row-local permanent failure, record a class-only error and park the row while committing completed work. Validate that decoded headers are a non-null string map. Distinguish row corruption from connection-wide LOB read failure.

12. **High — Worker death and restart are not managed safely.**  
    `OutboxDrainer.java:123–155`; `OutboxDrainer.java:184–198`  
    **Scenario:** An `AssertionError`, linkage error or memory error terminates the daemon while `running` remains true. The application continues accepting durable writes with no functioning drainer. Separately, `start()` is not idempotent, and `stop()` discards the thread reference even when interruption failed to terminate it. Restarting can reactivate the old loop alongside a new worker.  
    **Fix:** Serialize lifecycle transitions, retain ownership until the worker exits, reset state in `finally`, and expose/supervise unexpected termination. Roll back unfinished batches on all exceptional exits without blindly continuing after fatal JVM errors.

13. **High — Default shutdown cannot finish a valid default-sized batch.**  
    `OutboxDrainer.java:141–146`; `OutboxProperties.java:36–56`  
    **Scenario:** Fifty successful sends each take one second—well below the five-second confirm timeout. SIGTERM near the batch start interrupts it after thirty seconds. The pending send has an ambiguous outcome, or subsequent database work fails, producing duplicates on restart. A worker asleep in five-minute backoff also wastes the shutdown budget before being interrupted.  
    **Fix:** Establish an end-to-end maximum batch duration and align stop timeout, Spring shutdown timeout and pod grace period with it. Wake idle waits immediately while allowing the active batch to finish. Do not report completion while the worker still owns a batch.

14. **High — Normal auto-configuration can omit the relay entirely.**  
    `PlatformEventsAutoConfiguration.java:42`; `PlatformEventsAutoConfiguration.java:99`  
    **Scenario:** With ordinary auto-configured messaging and `events.relay.enabled=true`, events configuration is processed before messaging. `@ConditionalOnBean(EventPublisher.class)` fails and is never reconsidered. Business commits then produce no integration event. The new relay test directly constructs the listener and cannot expose this. Boot sorts otherwise unordered configurations alphabetically. [Boot sorter](https://raw.githubusercontent.com/spring-projects/spring-boot/v4.1.0/core/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/AutoConfigurationSorter.java).  
    **Fix:** Explicitly order relay configuration after messaging and outbox configuration, and test the combined auto-configuration with a real transaction.

15. **Medium — Trace and principal sources depend on registration order.**  
    `OutboxAutoConfiguration.java:121`; `OutboxAutoConfiguration.java:133`  
    **Scenario:** Tracing and `CurrentUserAccessor` arrive through their normal later auto-configurations. These conditions see no beans and permanently select empty trace/null principal sources. Tests pre-register mocks with `withBean`, hiding the failure.  
    **Fix:** Order these integrations after their providers, or resolve optional providers lazily without registration-time bean conditions. Add replacement back-off conditions to the source beans.

16. **High — Optional tracing classes leak into an unconditional bean.**  
    `PlatformOutboxContext.java:52`; `OutboxAutoConfiguration.java:64`  
    **Scenario:** A service has messaging, JDBC and metrics but no `micrometer-tracing`, which is optional. The unconditional `PlatformOutboxContext` declares a method with `Tracer`/`Propagator` parameters. `EventHandlerRegistrar` reflects over that bean’s methods and can fail resolving the absent classes. Skipping `TraceConfiguration` does not protect this class.  
    **Fix:** Move tracing-dependent signatures and implementation into a separate class loaded exclusively by the guarded tracing configuration. Test with tracing classes actually removed through `FilteredClassLoader`.

17. **Medium — Restoration does not cover the actual trace/principal scope or failure logs.**  
    `PlatformOutboxContext.java:75–81`; `OutboxDrainer.java:275–283`  
    **Scenario:** Only the MDC trace ID is restored; there is no Micrometer current trace and no restored principal context. With Rabbit template observations enabled, sending can create a new root trace and replace the stored wire propagation. On any send failure, try-with-resources closes the restored scope before `markFailed()` emits its WARN, so the principal diagnostic lines lack the original trace. [Rabbit send observation implementation](https://raw.githubusercontent.com/spring-projects/spring-amqp/v4.1.0/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/core/RabbitTemplate.java).  
    **Fix:** Restore a proper tracing scope and a narrowly defined subject context, preserve the required stored wire header, and perform failure logging inside that scope. Ensure empty-context rows also isolate residual thread context.

18. **High — Credential refusal has a concrete gap and an injection bypass.**  
    `JdbcOutboxPublisher.java:40–41`; `JdbcOutboxPublisher.java:74–76`; `PlatformOutboxContext.java:60`  
    **Scenario:** Header `pwd` is persisted even though the logging capability explicitly treats it as a credential. Additionally, the propagator can inject baggage or other headers after credential validation; a propagated `authorization` or `x-auth-token` therefore bypasses refusal entirely.  
    **Fix:** Use the complete credential-name policy, including exact aliases such as `pwd`. Allowlist injected propagation to `traceparent`/`tracestate`, then validate the final merged headers before insertion.

19. **Medium — Raw destinations can disclose credentials in logs.**  
    `OutboxDrainer.java:335–339`  
    **Scenario:** A custom transport uses a destination containing URL user-info or an opaque secret path. Failure and parking WARNs print it verbatim. Restricting `last_error` to a class name does not protect these messages.  
    **Fix:** Log the event ID and a safe configured destination alias, or an explicitly sanitized destination representation. Do not rely on arbitrary destination strings being credential-free.

20. **Medium — Accepted duration values silently remove bounds.**  
    `OutboxSettings.java:26–38`; `OutboxDrainer.java:410–411`; `OutboxDrainer.java:141`  
    **Scenario:** `statement-timeout=500ms` becomes JDBC timeout zero, meaning unlimited. A zero/sub-millisecond stop timeout becomes `join(0)`, meaning wait forever. Negative polling/backoff values and excessively large durations also pass startup validation and can cause worker failure or repeated exceptions.  
    **Fix:** Validate positivity, representable ranges and relationships at binding time. Round positive statement durations up to at least one second, and reject stop durations that become zero milliseconds.

21. **Medium — Gauge freshness and maintenance scheduling break during failures.**  
    `OutboxDrainer.java:187–196`; `OutboxDrainer.java:343–351`; `OutboxDrainer.java:379`  
    **Scenario:** A blocked send prevents all gauge refreshes; database failure leaves the last successful values indefinitely, possibly zero backlog. Five-minute retry sleeps override a configured five-second gauge interval. A gauge-query failure also skips retention, while a full successful batch followed by housekeeping failure leaves `moreDue=true`, bypassing the newly increased pause.  
    **Fix:** Schedule bounded maintenance independently using the drainer pool, isolate gauge and retention failures, and expose last-success/freshness. Calculate age from a cached oldest timestamp without querying on scrape; never present stale observations as current.

22. **Medium — Gauges retain the first drainer’s state across replacement.**  
    `OutboxDrainer.java:112–115`  
    **Scenario:** Two drainers share a registry, or a refreshed context reuses one. Identical names and tags cause the second registrations to be ignored. The gauges continue reading the first drainer’s atomics, eventually becoming stale or `NaN`. [Micrometer registration semantics](https://docs.micrometer.io/micrometer/reference/concepts/gauges.html).  
    **Fix:** Give these meters an explicit lifecycle owner and shared/replaced state, or a bounded configured outbox identity. Remove only meters owned by the terminating instance.

23. **Medium — Parking metrics and WARNs can describe rolled-back state.**  
    `OutboxDrainer.java:332–336`; `OutboxDrainer.java:233`  
    **Scenario:** The final rejection updates a row and immediately increments `parked.total` and emits “parked”; commit then fails. The row remains pending, while every retry can announce another parking transition that never committed.  
    **Fix:** Collect state-transition notifications during the batch and publish them only after successful commit. Keep attempt counters separately defined if they intentionally count rolled-back attempts.

For the acceptance assessment, **met** means the static implementation and relevant slice coverage support the criterion; it does not represent a test run performed here.

| AC | Assessment | Reason |
|---|---|---|
| AC1 | **Partly** | Ordinary same-source commit/rollback paths have coverage; transaction-ownership and post-commit holes remain, and deferred business-constraint failure is untested. |
| AC2 | **Not met** | A connection synchronized under another database’s transaction defeats the guard. |
| AC3 | **Met** | No additional `DataSource` or transaction-manager bean is declared; the JPA context test checks Boot retains both. |
| AC4 | **Partly** | The concurrent Oracle 10-of-25 test exists; its execution and exact stopped-cursor/prefetch lock footprint are not established here. |
| AC5 | **Not met** | Transient synthetic nacks can park valid events; worker failure and stale metrics also defeat recovery expectations. |
| AC6 | **Not met** | Publication can block before the confirm timeout, and the dummy mandatory check permits unroutable `SENT` rows. |
| AC7 | **Pending Gate F** | The rollback simulation does not demonstrate SIGKILL, database lock release timing or consumer-observed duplicate bounds. |
| AC8 | **Pending Gate F** | Two real instances remain untested; “absent a crash” must exclude ambiguous confirms/commits, which can duplicate without process death. |
| AC9 | **Partly** | Rejection-based parking and same-ID replay have tests; the supplied `max-attempts` criterion is unimplemented, and outage nacks are misclassified. |
| AC10 | **Not met** | The wrapper fallback can borrow and retain the application pool’s connections. |
| AC11 | **Not met** | The delete predicate protects pending/parked rows, but inherited `autoCommit=false` prevents retention from committing. |
| AC12 | **Not met** | Registration order, restoration/log scope and credential-filter failures violate propagation and redaction requirements. |
| AC13 | **Partly** | Ordinary disabled publication and missing-table diagnostics have coverage; post-commit fallback publication can disappear, and consumer startup combinations remain pending. |
| AC14 | **Not met** | The default shutdown budget is shorter than a legitimate full batch; actual SIGTERM/exit-code evidence is also pending. |
| AC15 | **Partly** | DDL and grants-only Oracle fixtures exist; independent driver bounds are not applied reliably. |
| AC16 | **Not met** | Auto-configuration can omit the relay, and post-commit domain callbacks can enter an already-completed transaction. |
| AC17 | **Partly** | Mocked nack/return/confirm-timeout paths exist; real timeout coverage is absent and whole-send boundedness fails. |
| AC18 | **Pending Gate F** | Consumer regressions, downstream compilation, the new golden path and CI results have not been established for this review. |

For **AC4 specifically**, Oracle 19c supports fetch-time locking with ordered `SKIP LOCKED`; `ORDER BY` alone is not evidence that the whole result is locked. The relevant boundary is the driver’s server fetch, not each Java `next()`. Prefetched-but-unconsumed locks remain transaction-owned after cursor close. Here the fetch size equals the batch size and the loop avoids an extra `next()`; I found no evidence warranting a claim that this necessarily over-locks. Certification should nevertheless measure remaining-row accessibility with the actual ojdbc version, several batch sizes and large LOBs. [Oracle 19c example](https://asktom.oracle.com/ords/asktom.search?tag=fetch-limited-rows-for-update), [Oracle JDBC prefetch behavior](https://docs.oracle.com/en/database/oracle/oracle-database/19/jjdbc/performance-extensions.html).
