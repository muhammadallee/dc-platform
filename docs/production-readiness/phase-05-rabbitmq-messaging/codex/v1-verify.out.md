Reviewed `7f347db` and surrounding code. Static review only; no JVM or Docker execution. **Phase 5 is not ready for closing runs.**

| Item | Assessment | Specific result |
|---|---|---|
| D1 | **Partial** | An uncontended late bean is scanned before subscribing. Concurrent `start()` can still observe its partially populated routes. |
| D2 | **Partial** | Subscription addition and shutdown snapshots now share a lock. Registration still races with startup, and holding that lock across external calls introduces deadlocks. |
| D3 | **Partial** | Consumers correctly precede the outbox drainer. They can now execute before lifecycle-dependent handlers are ready, and continue after those dependencies stop. |
| D4 | **Right** | Each subscription captures its cancellation latch; restarting cannot replace the signal an old delivery reads. |
| D5 | **Partial** | Repeated prototype creation no longer throws. The check also suppresses distinct singleton registrations because it ignores `beanName`. |
| D6 | **Partial** | Logging and `inFlight` accounting are fixed. The requested requeue still drops the message when the queue is full. |
| D7 | **Partial** | ERROR logging is sanitized; DEBUG still prints the complete payload-bearing throwable. |
| D8 | **Right** | `forceStop(true)` implements returning buffered deliveries after the current delivery finishes, subject to the shutdown timeout. |
| Golden path | **Right for the requested H2/R6 correction** | The order and outbox insert share the transaction. The migration and asynchronous assertion have no concrete H2 defect identified. The in-memory transport does not establish durable end-to-end delivery. |

Concrete defects:

1. **High — scanning and subscription creation still have an atomicity gap.**  
   [EventHandlerRegistrar.java:132](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java:132), also lines 114–116 and 335.

   **Trigger:** thread A registers the first of a bean’s two handlers on a new destination, then releases `lifecycle`. Thread B runs `start()`, sees that destination and subscribes before A registers the second handler. A queued message for the second type reaches lines 169–172 and returns as `ignored`: **acknowledged without delivery**.

   When A finishes scanning, its retained `newDestinations` also causes another subscription at line 116, despite startup already having subscribed. The new test delivers only after registration returns, so it does not exercise either interleaving.

2. **High — the registrar lock can deadlock against bean creation or Rabbit’s connection lock.**  
   [EventHandlerRegistrar.java:322](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java:322), with unconditional acquisition for **every bean** at line 114 and transport invocation at line 159.

   Two concrete triggers:

   - Thread A holds `lifecycle` and resolves an uncreated serializer/collaborator. Thread B holds Spring’s singleton-creation lock while creating another bean, and its post-processing waits for `lifecycle`. A waits for B’s singleton lock. Spring 7 retains blocking singleton locking outside coordinated bootstrap. [Spring singleton implementation](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.7/spring-beans/src/main/java/org/springframework/beans/factory/support/DefaultSingletonBeanRegistry.java).
   - A connection-creation thread holds Rabbit’s connection lock and invokes a connection listener that creates a bean. Its post-processing waits for `lifecycle`. Meanwhile registrar startup holds `lifecycle` and `subscribe()` → topology declaration waits for that connection lock. Connection listeners execute under that lock. [Spring Rabbit connection implementation](https://raw.githubusercontent.com/spring-projects/spring-amqp/v4.1.0/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/connection/CachingConnectionFactory.java).

   **Consequence:** startup/registration hangs, and `stop()` cannot acquire the registrar lock to begin cancellation.

   Same-thread reentry alone does not deadlock: Java monitors are reentrant. Nor does a handler merely creating a bean necessarily deadlock—SMLC releases its startup latch before invoking handlers. Raw `registerSingleton()` also bypasses this post-processor. The defect is the cross-thread lock cycle, not callback execution in general. [SMLC implementation](https://raw.githubusercontent.com/spring-projects/spring-amqp/v4.1.0/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/listener/SimpleMessageListenerContainer.java).

3. **Medium — the earlier phase permits delivery outside a handler’s usable lifecycle.**  
   [EventHandlerRegistrar.java:61](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java:61).

   **Trigger:** an eagerly created handler implements default-phase `SmartLifecycle`, or uses a default-phase lifecycle service whose `start()` makes it usable. A backlog arrives immediately when the registrar starts. Registration does not establish a lifecycle dependency from the registrar to that handler, so invocation can precede the handler’s `start()`.

   Shutdown reverses the problem: that service stops while the registrar still consumes, potentially throughout web and outbox shutdown.

   **Consequence:** healthy messages encounter unavailable resources and can exhaust retries into the DLQ. Constructor injection into the handler does not establish the missing registrar dependency. Starting before the web server likewise breaks handlers requiring the local HTTP endpoint or server-start initialization. Ordinary initialized beans and pools destroyed later are unaffected. [Spring lifecycle ordering](https://raw.githubusercontent.com/spring-projects/spring-framework/v7.0.7/spring-context/src/main/java/org/springframework/context/support/DefaultLifecycleProcessor.java).

4. **Medium — distinct singleton handlers are mistaken for repeated scoped instances.**  
   [EventHandlerRegistrar.java:135](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java:135).

   **Trigger:** declare two differently named singleton beans of the same handler class, configured with different collaborators. Their reflected method, destination and event type match.

   **Consequence:** the second registration is silently excluded from routing apart from a WARN; startup no longer rejects the ambiguous configuration. Whichever instance was registered first receives everything. Method equality cannot distinguish repeated creation of one bean definition from two different definitions.

5. **Medium — D6 still discards the delivery requested for requeue.**  
   [SubscriptionDispatcher.java:100](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-inmemory/src/main/java/ae/gov/dubaicustoms/platform/messaging/inmemory/internal/SubscriptionDispatcher.java:100).

   **Trigger:** the dispatcher removes A; a publisher fills the freed slot with B; A throws `RequeueException`.

   **Consequence:** `offer(A)` fails and A is permanently discarded. Logging and decrementing `inFlight` repair observability/accounting, not delivery. The separate stop-after-poll return at line 87 still ignores a failed `offer`, retaining the original silent-loss/accounting problem under the analogous full-queue interleaving.

6. **Medium — enabling DEBUG restores the payload disclosure.**  
   [EventHandlerRegistrar.java:222](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java:222).

   **Trigger:** enable DEBUG for the registrar and exhaust a handler throwing `IllegalArgumentException(payload.toString())`, or a cause containing payload text.

   **Consequence:** the full exception message and cause chain enter logs. AC14 and the scope’s no-payload-on-failure requirement do not exempt DEBUG.

The shutdown budget is **additive**, not one 30-second allowance. For the described web/outbox/Rabbit sequence, budget approximately:

`web drain + outbox stop-timeout + up to 2 seconds of extra outbox joins + maximum subscription shutdown time + teardown overhead`

With the default 30-second allowances, that is approximately **92 seconds plus overhead and other lifecycle phases**. Subscription closes run concurrently, so their ordinary timeout contribution is the maximum, not their sum. However, this is **not a hard upper bound**: registrar lock acquisition and `thread.join()` are unbounded, and synchronous `stop()` implementations are not preempted by Spring’s per-phase callback timeout. [Registrar join](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java:382), [outbox joins](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java:206), [Boot timeout default](https://docs.spring.io/spring-boot/appendix/application-properties/index.html).

`forceStop(true)` itself introduces **no demonstrated message-loss defect** here. With AUTO acknowledgment and the default batch size, a completed delivery is acknowledged before normal forced-stop cleanup; buffered unacknowledged messages return to RabbitMQ. If `shutdown-timeout` expires before the ack completes, channel closure can redeliver a message whose handler already committed—or whose handler is still running. That is an at-least-once duplicate window, not evidence that the change loses messages. [Spring container behavior](https://docs.spring.io/spring-amqp/reference/amqp/containerAttributes.html).

For the example, `V2__platform_outbox.sql` uses H2-supported BLOB, CLOB, NUMERIC and timestamp-with-time-zone types. `OrderFlowTest` has no enclosing test transaction hiding the row from the drainer; its Awaitility lambda obtains a fresh thread-safe snapshot each time. It uses **`TestEventTransport`**, which records sends, rather than exercising the real in-memory dispatcher. No concrete flakiness mechanism was found in that changed assertion. Passing it proves publication after commit, not handler completion or crash durability; the real in-memory transport acknowledges enqueueing before processing. [H2 types](https://h2database.com/html/datatypes.html), [OrderFlowTest.java:47](/C:/Users/Ali/AppData/Local/Temp/hd/codex-p4r1/examples/example-golden-path/src/test/java/ae/gov/dubaicustoms/example/goldenpath/orders/OrderFlowTest.java:47).

VERDICT: REJECT
