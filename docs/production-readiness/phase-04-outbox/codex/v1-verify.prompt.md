Commit under review: 922ab13

# Phase 4 — verification pass on the fixes since round 2

Read-only checkout at the commit named below; no JVM or Docker in your sandbox. Your round-2 output is
`docs/production-readiness/phase-04-outbox/codex/r2-review.out.md`; the dispositions are in
`docs/production-readiness/phase-04-outbox/review-round-2.md`. The diff below is everything since you reviewed `311d488`.

Facts from the Gate F consumer on this code (real Oracle, real RabbitMQ behind Toxiproxy, two instances, 79/79):
SIGKILL with another instance alive: 300 of 300 delivered, 24 duplicates. SIGKILL of the only instance: 200 of 200, 24
duplicates. SIGTERM mid-batch: 150 of 150, 0 duplicates. Two instances: 500 of 500, 0 duplicates, shared 250/250. RabbitMQ
down: requests unaffected, 30 of 30 after recovery, nothing parked. Unroutable destination: parked, replay delivers it.

The consumer also found, and this diff fixes:
- HI-0083: the Rabbit starter brought no `spring-boot-amqp`, so no `RabbitTemplate` on Boot 4.
- HI-0084: `eventHandlerRegistrar`, a non-static BeanPostProcessor, resolved the `MeterRegistry` during post-processor
  registration, costing any messaging service its JVM, pool and repository meters and common tags. It is now static and lazy.
- HI-0085: a counter's name collided with the parked gauge in Prometheus.

Answer briefly:

1. R2-1 to R2-10: confirmed fixed / partial / not fixed, one line each. Do you accept R2-3 and the rest of R2-5 as documented
   Medium residuals (HI-0079, HI-0082)?
2. Is the lazy registrar correct? First-use resolution happens while a handler bean is post-processed: can that create a
   cycle or an early-init problem (a handler bean that depends on what the transport needs; `ConditionalOnSingleCandidate` on
   a static method; `ObjectProvider.getObject` failing with the no-transport failure analyzer still applying, including the
   analyzer's bean type check)? Does a delivery arriving before the context is ready now behave differently?
3. Any new defect in this diff that matters: severity, `file:line`, a concrete failing scenario, the fix. Do not repeat
   accepted residuals and do not list style issues. If there is nothing, say so.

```diff
diff --git a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/TransactionalDispatcher.java b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/TransactionalDispatcher.java
index f4f1c13..87cae61 100644
--- a/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/TransactionalDispatcher.java
+++ b/events/platform-events-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/events/autoconfigure/internal/TransactionalDispatcher.java
@@ -25,9 +25,21 @@ public final class TransactionalDispatcher implements AfterCommitDispatcher {
         });
     }
 
+    // Spring offers no flag for this phase, so the nearest frame decides. A transaction opened inside the callback
+    // has its own callbacks, and a handler must wait for it: it may yet roll back.
     private static boolean afterCommitPhase() {
-        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
-                "org.springframework.transaction.support.TransactionSynchronizationUtils".equals(frame.getClassName())
-                        && frame.getMethodName().equals("invokeAfterCommit")));
+        return StackWalker.getInstance().walk(frames -> frames.map(frame -> {
+            String type = frame.getClassName();
+            String method = frame.getMethodName();
+            if ("org.springframework.transaction.support.TransactionSynchronizationUtils".equals(type)
+                    && method.equals("invokeAfterCommit")) {
+                return Boolean.TRUE;
+            }
+            boolean inner = ("org.springframework.transaction.support.TransactionTemplate".equals(type)
+                    && "execute".equals(method))
+                    || ("org.springframework.transaction.interceptor.TransactionAspectSupport".equals(type)
+                    && "invokeWithinTransaction".equals(method));
+            return inner ? Boolean.FALSE : null;
+        }).filter(java.util.Objects::nonNull).findFirst().orElse(Boolean.FALSE));
     }
 }
diff --git a/messaging/platform-messaging-autoconfigure/pom.xml b/messaging/platform-messaging-autoconfigure/pom.xml
index b2423bc..f5251c4 100644
--- a/messaging/platform-messaging-autoconfigure/pom.xml
+++ b/messaging/platform-messaging-autoconfigure/pom.xml
@@ -100,6 +100,11 @@
       <artifactId>spring-boot-configuration-processor</artifactId>
       <optional>true</optional>
     </dependency>
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-amqp</artifactId>
+      <scope>test</scope>
+    </dependency>
     <!-- Runs PlatformArchRules over this module (ArchConstitutionTest). -->
     <dependency>
       <groupId>ae.gov.dubaicustoms.platform</groupId>
diff --git a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java
index fd2a1a6..54a2f4d 100644
--- a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java
+++ b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/PlatformMessagingAutoConfiguration.java
@@ -76,10 +76,14 @@ public class PlatformMessagingAutoConfiguration {
     @Bean
     @ConditionalOnMissingBean
     @ConditionalOnSingleCandidate(EventTransport.class)
-    EventHandlerRegistrar eventHandlerRegistrar(EventTransport transport, EventSerializer serializer,
-            MessagingProperties properties, Environment environment, ObjectProvider<MeterRegistry> meterRegistry) {
+    static EventHandlerRegistrar eventHandlerRegistrar(ObjectProvider<EventTransport> transport,
+            ObjectProvider<EventSerializer> serializer, ObjectProvider<MessagingProperties> properties,
+            Environment environment, ObjectProvider<MeterRegistry> meterRegistry) {
+        // Static and lazy: a BeanPostProcessor must not pull anything in while post-processors are being registered, or
+        // what it obtains skips post-processing. An early MeterRegistry costs the service its meters and common tags.
         String group = environment.getProperty("spring.application.name", "application");
-        return new EventHandlerRegistrar(transport, serializer, properties, group, meterRegistry.getIfAvailable());
+        return new EventHandlerRegistrar(transport::getObject, serializer::getObject, properties::getObject, group,
+                meterRegistry::getIfAvailable);
     }
 
     @Bean
diff --git a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java
index e53f92a..4957782 100644
--- a/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java
+++ b/messaging/platform-messaging-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/autoconfigure/internal/EventHandlerRegistrar.java
@@ -15,11 +15,13 @@ import java.util.Map;
 import java.util.Objects;
 import java.util.UUID;
 import java.util.concurrent.CopyOnWriteArrayList;
+import java.util.function.Supplier;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.beans.factory.DisposableBean;
 import org.springframework.beans.factory.config.BeanPostProcessor;
 import org.springframework.util.ReflectionUtils;
+import org.springframework.util.function.SingletonSupplier;
 
 /**
  * {@link BeanPostProcessor} scanning every bean for {@code @EventHandler}-annotated methods and
@@ -44,20 +46,39 @@ public final class EventHandlerRegistrar implements BeanPostProcessor, Disposabl
     private static final Logger log = LoggerFactory.getLogger(EventHandlerRegistrar.class);
     private static final String METRIC_NAME = "platform.messaging.handled";
 
-    private final EventTransport transport;
-    private final EventSerializer serializer;
-    private final MessagingProperties properties;
+    private final Supplier<EventTransport> transport;
+    private final Supplier<EventSerializer> serializer;
+    private final Supplier<MessagingProperties> properties;
     private final String group;
-    private final MeterRegistry meterRegistry;
+    private final Supplier<MeterRegistry> meterRegistry;
     private final CopyOnWriteArrayList<EventTransport.Subscription> subscriptions = new CopyOnWriteArrayList<>();
 
     public EventHandlerRegistrar(EventTransport transport, EventSerializer serializer, MessagingProperties properties,
             String group, MeterRegistry meterRegistry) {
-        this.transport = Objects.requireNonNull(transport, "transport must not be null");
-        this.serializer = Objects.requireNonNull(serializer, "serializer must not be null");
-        this.properties = Objects.requireNonNull(properties, "properties must not be null");
+        this(() -> Objects.requireNonNull(transport, "transport must not be null"),
+                () -> Objects.requireNonNull(serializer, "serializer must not be null"),
+                () -> Objects.requireNonNull(properties, "properties must not be null"), group, () -> meterRegistry);
+    }
+
+    /**
+     * Resolves its collaborators on first use, never at construction. This is a {@code BeanPostProcessor}, created while
+     * post-processors are being registered: a bean it obtains then skips post-processing. That is how a messaging service
+     * lost its JVM, pool and repository meters and its common tags: the {@code MeterRegistry} was created too early for
+     * Boot to configure it.
+     *
+     * @param transport supplies the transport; never {@code null}
+     * @param serializer supplies the serializer; never {@code null}
+     * @param properties supplies the properties; never {@code null}
+     * @param group the consumer group; never {@code null}
+     * @param meterRegistry supplies the registry, which may supply {@code null}; never {@code null}
+     */
+    public EventHandlerRegistrar(Supplier<EventTransport> transport, Supplier<EventSerializer> serializer,
+            Supplier<MessagingProperties> properties, String group, Supplier<MeterRegistry> meterRegistry) {
+        this.transport = SingletonSupplier.of(Objects.requireNonNull(transport, "transport must not be null"));
+        this.serializer = SingletonSupplier.of(Objects.requireNonNull(serializer, "serializer must not be null"));
+        this.properties = SingletonSupplier.of(Objects.requireNonNull(properties, "properties must not be null"));
         this.group = Objects.requireNonNull(group, "group must not be null");
-        this.meterRegistry = meterRegistry;
+        this.meterRegistry = SingletonSupplier.of(Objects.requireNonNull(meterRegistry, "meterRegistry must not be null"));
     }
 
     @Override
@@ -75,7 +96,7 @@ public final class EventHandlerRegistrar implements BeanPostProcessor, Disposabl
         }
         EventHandler annotation = method.getAnnotation(EventHandler.class);
         method.setAccessible(true);
-        EventTransport.Subscription subscription = transport.subscribe(annotation.destination(), group,
+        EventTransport.Subscription subscription = transport.get().subscribe(annotation.destination(), group,
                 (key, value, headers) -> handle(bean, method, annotation, value, headers));
         subscriptions.add(subscription);
     }
@@ -85,7 +106,7 @@ public final class EventHandlerRegistrar implements BeanPostProcessor, Disposabl
         if (!annotation.eventType().isEmpty() && !annotation.eventType().equals(eventType)) {
             return;
         }
-        int maxAttempts = properties.handler().retry().maxAttempts();
+        int maxAttempts = properties.get().handler().retry().maxAttempts();
         for (int attempt = 1; attempt <= maxAttempts; attempt++) {
             try {
                 invoke(bean, method, value, headers, eventType);
@@ -122,11 +143,11 @@ public final class EventHandlerRegistrar implements BeanPostProcessor, Disposabl
     private Object resolveArgument(Method method, byte[] value, Map<String, String> headers, String eventType) {
         Class<?> paramType = method.getParameterTypes()[0];
         if (!EventEnvelope.class.isAssignableFrom(paramType)) {
-            return serializer.deserialize(value, paramType);
+            return serializer.get().deserialize(value, paramType);
         }
         // Generic erasure: EventEnvelope<T>'s T is only visible via the method's generic signature.
         Class<?> payloadType = envelopePayloadType(method);
-        Object payload = serializer.deserialize(value, payloadType);
+        Object payload = serializer.get().deserialize(value, payloadType);
         int eventVersion = parseVersion(headers.get("eventVersion"));
         // A message from a non-platform producer has no id; it still gets one, though a redelivery will differ.
         String eventId = headers.getOrDefault(EventEnvelope.EVENT_ID_HEADER, UUID.randomUUID().toString());
@@ -153,21 +174,22 @@ public final class EventHandlerRegistrar implements BeanPostProcessor, Disposabl
     }
 
     private void republishToDlq(String destination, byte[] value, Map<String, String> headers) {
-        String dlqDestination = destination + properties.dlq().suffix();
-        transport.send(dlqDestination, null, value, headers);
+        String dlqDestination = destination + properties.get().dlq().suffix();
+        transport.get().send(dlqDestination, null, value, headers);
     }
 
     private void sleepBackoff() {
         try {
-            Thread.sleep(properties.handler().retry().backoff());
+            Thread.sleep(properties.get().handler().retry().backoff());
         } catch (InterruptedException e) {
             Thread.currentThread().interrupt();
         }
     }
 
     private void count(String outcome) {
-        if (meterRegistry != null) {
-            meterRegistry.counter(METRIC_NAME, "outcome", outcome).increment();
+        MeterRegistry registry = meterRegistry.get();
+        if (registry != null) {
+            registry.counter(METRIC_NAME, "outcome", outcome).increment();
         }
     }
 
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxProperties.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxProperties.java
index 32aebbd..8ab951f 100644
--- a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxProperties.java
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/OutboxProperties.java
@@ -13,7 +13,7 @@ import org.springframework.validation.annotation.Validated;
  * {@code @EnableConfigurationProperties} (never scanned).
  *
  * @param enabled {@code false} publishes after commit, no longer guaranteed, and logs a WARN saying so
- * @param poolSize the most database connections the drainer holds, on its own pool
+ * @param poolSize the most database connections the drainer holds, on its own pool; at least 2
  * @param batchSize the most rows claimed in one transaction, and so the most duplicates a killed instance can cause
  * @param pollInterval the pause when nothing is due
  * @param maxRejections how many times the sink may reject a row before it is parked; outages do not count
@@ -57,6 +57,17 @@ public record OutboxProperties(
         /** The Oracle driver's read timeout on the drainer's connections, unless the application pool sets one. */
         @DefaultValue("60s") Duration readTimeout) {
 
+    /** Refuses a pool too small to run a batch and the metrics beside it, and a read timeout of none. */
+    public OutboxProperties {
+        if (poolSize < 2) {
+            throw new IllegalArgumentException("dc.platform.messaging.outbox.pool-size must be at least 2: one for batches, "
+                    + "one for metrics and retention, so a slow batch cannot stop them");
+        }
+        if (readTimeout.isZero() || readTimeout.isNegative()) {
+            throw new IllegalArgumentException("dc.platform.messaging.outbox.read-timeout must be positive");
+        }
+    }
+
     /**
      * The drainer's settings.
      *
diff --git a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxConnections.java b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxConnections.java
index 258fe69..08e85d6 100644
--- a/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxConnections.java
+++ b/messaging/platform-messaging-outbox-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/autoconfigure/internal/OutboxConnections.java
@@ -16,11 +16,13 @@ import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
  * The drainer's own bounded connections, so a stuck or backlogged drainer can never starve request threads. Holds the
  * pool instead of being a {@code DataSource} bean: such a bean would make Boot back off from the service's own pool.
  *
- * <p>Built from an allow-list of a Hikari application pool's connection settings (URL, credentials, driver properties,
- * connection-init SQL), never its pool behaviour: not its size, auto-commit, metrics or timeouts. On Oracle it adds the
- * driver's read and connect bounds itself, because a service without the data-jpa starter or with a pool it started
- * itself has none. An unpooled source is wrapped. A source of any other kind may be a pool in disguise, and wrapping one
- * would let the drainer hold request capacity, so it is refused: define an {@code OutboxConnections} bean instead.
+ * <p>Built from an allow-list of a URL-configured application source's connection settings (URL, credentials, driver
+ * properties, connection-init SQL), never its pool behaviour: not its size, auto-commit, metrics or timeouts. On Oracle it
+ * adds the driver's read and connect bounds itself, because a service without the data-jpa starter or with a pool it
+ * started itself has none. An embedded database is wrapped. Anything else is refused, because it may be a pool in
+ * disguise, which would let the drainer hold request capacity, or a Hikari pool whose {@code dataSource} or
+ * {@code dataSourceClassName} takes precedence over its URL, which could mean another database: define an
+ * {@code OutboxConnections} bean instead.
  * Credentials are read once: rotating them on the application pool needs a restart. Thread-safe.
  */
 public final class OutboxConnections implements AutoCloseable {
@@ -58,7 +60,8 @@ public final class OutboxConnections implements AutoCloseable {
     public static OutboxConnections of(DataSource application, int poolSize, Duration readTimeout, MeterRegistry registry) {
         HikariConfig config = new HikariConfig();
         HikariDataSource hikari = unwrapHikari(application);
-        if (hikari != null && hikari.getJdbcUrl() != null) {
+        if (hikari != null && hikari.getJdbcUrl() != null && hikari.getDataSource() == null
+                && hikari.getDataSourceClassName() == null) {
             config.setJdbcUrl(hikari.getJdbcUrl());
             config.setUsername(hikari.getUsername());
             config.setPassword(hikari.getPassword());
@@ -70,7 +73,15 @@ public final class OutboxConnections implements AutoCloseable {
             config.setSchema(hikari.getSchema());
             config.setCatalog(hikari.getCatalog());
             addOracleBounds(config, readTimeout);
-        } else if (application instanceof EmbeddedDatabase || application instanceof AbstractDriverBasedDataSource) {
+        } else if (application instanceof AbstractDriverBasedDataSource driver && driver.getUrl() != null) {
+            config.setJdbcUrl(driver.getUrl());
+            config.setUsername(driver.getUsername());
+            config.setPassword(driver.getPassword());
+            if (driver.getConnectionProperties() != null) {
+                config.setDataSourceProperties(copy(driver.getConnectionProperties()));
+            }
+            addOracleBounds(config, readTimeout);
+        } else if (application instanceof EmbeddedDatabase) {
             config.setDataSource(application);
         } else {
             throw new IllegalStateException("The outbox drainer needs its own connections, and cannot derive them from a "
diff --git a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/JdbcOutboxPublisher.java b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/JdbcOutboxPublisher.java
index 939cc4f..b43bc87 100644
--- a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/JdbcOutboxPublisher.java
+++ b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/JdbcOutboxPublisher.java
@@ -23,9 +23,12 @@ import org.springframework.transaction.support.TransactionSynchronizationManager
  * Writes the outbox row through the application's {@link DataSource}, inside the caller's transaction, so it
  * commits or rolls back with the business write. It refuses unless a transaction really owns a connection of that
  * {@code DataSource}: bound, and not auto-committing. A connection merely synchronized under another database's
- * transaction would auto-commit on its own and silently lose atomicity. It also refuses once the transaction has
- * committed (an {@code afterCommit} callback still sees its flags and connection, but nothing commits again), unless
- * that callback started a new transaction. Serializes now: a later class change cannot alter a stored event.
+ * transaction would auto-commit on its own and silently lose atomicity. (Spring exposes no way to verify ownership
+ * further: a pool with {@code auto-commit=false}, a second transaction manager, and a read through this data source
+ * inside that manager's transaction is not detected; use one transaction manager per outbox database.) It also
+ * refuses anywhere under an {@code afterCommit} or {@code afterCompletion} callback: that thread still sees the
+ * transaction's flags and connection, nothing commits again, and a {@code REQUIRED} method there joins the finished
+ * transaction, so a frame cannot prove a new one. Serializes now: a later class change cannot alter a stored event.
  *
  * <p>Thread-safe.
  *
@@ -113,25 +116,17 @@ public final class JdbcOutboxPublisher implements OutboxPublisher {
     }
 
     /**
-     * Whether the current thread is in a transaction's after-commit or after-completion callback, with no newer
-     * transaction opened inside it. Spring offers no flag for this phase, so the nearest frame decides.
+     * Whether the current thread is anywhere under a transaction's after-commit or after-completion callback. Spring
+     * offers no flag for this phase, so the stack decides, and it fails closed: a {@code REQUIRES_NEW} transaction opened
+     * in such a callback is refused too, because a {@code REQUIRED} one looks the same and would lose the row.
      *
-     * @return {@code true} if nothing written now would be committed by the transaction it appears to join
+     * @return {@code true} if a row written now may never be committed
      */
     public static boolean afterCommitPhase() {
-        return StackWalker.getInstance().walk(frames -> frames.map(frame -> {
-            String type = frame.getClassName();
-            String method = frame.getMethodName();
-            if ("org.springframework.transaction.support.TransactionSynchronizationUtils".equals(type)
-                    && (method.equals("invokeAfterCommit") || method.equals("invokeAfterCompletion"))) {
-                return Boolean.TRUE;
-            }
-            boolean newTransaction = ("org.springframework.transaction.support.TransactionTemplate".equals(type)
-                    && "execute".equals(method))
-                    || ("org.springframework.transaction.interceptor.TransactionAspectSupport".equals(type)
-                    && "invokeWithinTransaction".equals(method));
-            return newTransaction ? Boolean.FALSE : null;
-        }).filter(Objects::nonNull).findFirst().orElse(Boolean.FALSE));
+        return StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
+                "org.springframework.transaction.support.TransactionSynchronizationUtils".equals(frame.getClassName())
+                        && (frame.getMethodName().equals("invokeAfterCommit")
+                                || frame.getMethodName().equals("invokeAfterCompletion"))));
     }
 
     private static void refuseCredentialName(String name) {
diff --git a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java
index cd84984..a016846 100644
--- a/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java
+++ b/messaging/platform-messaging-outbox/src/main/java/ae/gov/dubaicustoms/platform/messaging/outbox/OutboxDrainer.java
@@ -95,6 +95,7 @@ public final class OutboxDrainer implements SmartLifecycle {
 
     private volatile Thread worker;
     private volatile Thread maintenance;
+    private Thread lastWorker;
     private volatile boolean stopping;
     private volatile boolean inBatch;
     private volatile Instant lastGaugeRefresh;
@@ -120,7 +121,8 @@ public final class OutboxDrainer implements SmartLifecycle {
         this.registry = registry;
         this.sentCounter = registry != null ? registry.counter("platform.outbox.sent") : null;
         this.failedCounter = registry != null ? registry.counter("platform.outbox.failed") : null;
-        this.parkedCounter = registry != null ? registry.counter("platform.outbox.parked.total") : null;
+        // Not "platform.outbox.parked.total": Prometheus maps it to the same family as the parked gauge, and drops one.
+        this.parkedCounter = registry != null ? registry.counter("platform.outbox.parkings") : null;
         registerGauges();
     }
 
@@ -154,10 +156,15 @@ public final class OutboxDrainer implements SmartLifecycle {
         if (worker != null) {
             return;
         }
+        // A send that ignored the stop may still own its batch; a new worker must not run beside it.
+        if (lastWorker != null && lastWorker.isAlive()) {
+            throw new IllegalStateException("The previous outbox batch has not finished; try again when it has");
+        }
         probeTable();
         registerGauges();
         stopping = false;
         worker = daemon(this::runWorker, "platform-outbox-drainer");
+        lastWorker = worker;
         maintenance = daemon(this::runMaintenance, "platform-outbox-maintenance");
         worker.start();
         maintenance.start();
@@ -182,6 +189,7 @@ public final class OutboxDrainer implements SmartLifecycle {
         if (drainer != null) {
             await(drainer);
         }
+        joinBriefly(housekeeper);
         if (registry != null) {
             gauges.forEach(registry::remove);
             gauges.clear();
@@ -207,6 +215,17 @@ public final class OutboxDrainer implements SmartLifecycle {
         }
     }
 
+    private static void joinBriefly(Thread thread) {
+        if (thread == null) {
+            return;
+        }
+        try {
+            thread.join(1000);
+        } catch (InterruptedException e) {
+            Thread.currentThread().interrupt();
+        }
+    }
+
     @Override
     public boolean isRunning() {
         return worker != null;
@@ -372,7 +391,7 @@ public final class OutboxDrainer implements SmartLifecycle {
         try {
             stored = parseHeaders(row.headers());
         } catch (JsonProcessingException e) {
-            markFailed(connection, row, e.getClass().getName(), row.rejections(), true, afterCommit);
+            markFailed(connection, row, Map.of(), e.getClass().getName(), row.rejections(), true, afterCommit);
             return true;
         }
         try (OutboxContext.Scope ignored = context.restore(stored)) {
@@ -382,8 +401,8 @@ public final class OutboxDrainer implements SmartLifecycle {
             } catch (RuntimeException e) {
                 boolean rejected = e instanceof EventPublishException publish && publish.rejected();
                 int rejections = row.rejections() + (rejected ? 1 : 0);
-                markFailed(connection, row, e.getClass().getName(), rejections, rejections >= settings.maxRejections(),
-                        afterCommit);
+                markFailed(connection, row, stored, e.getClass().getName(), rejections,
+                        rejections >= settings.maxRejections(), afterCommit);
                 return false;
             }
             markSent(connection, row);
@@ -423,8 +442,8 @@ public final class OutboxDrainer implements SmartLifecycle {
         }
     }
 
-    private void markFailed(Connection connection, Row row, String error, int rejections, boolean park,
-            List<Runnable> afterCommit) throws SQLException {
+    private void markFailed(Connection connection, Row row, Map<String, String> stored, String error, int rejections,
+            boolean park, List<Runnable> afterCommit) throws SQLException {
         int attempts = row.attempts() + 1;
         try (PreparedStatement statement = connection.prepareStatement(MARK_FAILED)) {
             statement.setQueryTimeout(timeoutSeconds());
@@ -442,8 +461,10 @@ public final class OutboxDrainer implements SmartLifecycle {
         if (park) {
             afterCommit.add(() -> {
                 increment(parkedCounter);
-                log.warn("Outbox event {} of type {} parked ({} rejections): {}", row.eventId(), row.eventType(),
-                        rejections, error);
+                try (OutboxContext.Scope ignored = context.restore(stored)) {
+                    log.warn("Outbox event {} of type {} parked ({} rejections): {}", row.eventId(), row.eventType(),
+                            rejections, error);
+                }
             });
         }
     }
diff --git a/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java b/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java
index 59f09de..3a73e55 100644
--- a/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java
+++ b/messaging/platform-messaging-rabbit/src/main/java/ae/gov/dubaicustoms/platform/messaging/rabbit/RabbitEventTransport.java
@@ -7,9 +7,12 @@ import java.util.HashMap;
 import java.util.LinkedHashMap;
 import java.util.Map;
 import java.util.Objects;
+import java.util.Set;
 import java.util.concurrent.CompletableFuture;
+import java.util.concurrent.ConcurrentHashMap;
 import java.util.concurrent.CopyOnWriteArrayList;
 import java.util.concurrent.ExecutionException;
+import java.util.concurrent.Semaphore;
 import java.util.concurrent.TimeUnit;
 import java.util.concurrent.TimeoutException;
 import org.aopalliance.intercept.MethodInterceptor;
@@ -21,6 +24,7 @@ import org.springframework.amqp.core.MessageProperties;
 import org.springframework.amqp.core.Queue;
 import org.springframework.amqp.core.TopicExchange;
 import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
+import org.springframework.amqp.rabbit.connection.AbstractRoutingConnectionFactory;
 import org.springframework.amqp.rabbit.connection.ConnectionFactory;
 import org.springframework.amqp.rabbit.connection.CorrelationData;
 import org.springframework.amqp.rabbit.core.RabbitAdmin;
@@ -59,6 +63,7 @@ import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
 public final class RabbitEventTransport implements EventTransport {
 
     private static final String UNACKNOWLEDGED = "A send could be lost without anyone knowing. Set ";
+    private static final int MAX_SENDERS_IN_FLIGHT = 64;
 
     private final RabbitTemplate rabbitTemplate;
     private final ConnectionFactory connectionFactory;
@@ -68,6 +73,8 @@ public final class RabbitEventTransport implements EventTransport {
     private final boolean quorumQueues;
     private final Duration confirmTimeout;
     private final CopyOnWriteArrayList<SimpleMessageListenerContainer> containers = new CopyOnWriteArrayList<>();
+    private final Semaphore sendersInFlight = new Semaphore(MAX_SENDERS_IN_FLIGHT);
+    private final Set<Thread> senders = ConcurrentHashMap.newKeySet();
 
     /**
      * Creates the transport.
@@ -103,6 +110,11 @@ public final class RabbitEventTransport implements EventTransport {
     }
 
     private static void requireAcknowledgedPublishing(RabbitTemplate template, ConnectionFactory factory) {
+        // A send runs on its own thread, so a routing key bound to the caller's thread would not be seen there, and the
+        // message could reach the default broker.
+        if (factory instanceof AbstractRoutingConnectionFactory) {
+            throw new IllegalStateException("A routing ConnectionFactory is not supported by this transport");
+        }
         if (!factory.isPublisherConfirms()) {
             throw new IllegalStateException(UNACKNOWLEDGED + "spring.rabbitmq.publisher-confirm-type=correlated");
         }
@@ -138,17 +150,26 @@ public final class RabbitEventTransport implements EventTransport {
         CorrelationData correlation = new CorrelationData();
         // The whole operation is bounded. A broker under a resource alarm blocks the publish itself, before any confirm
         // can be awaited, so it runs apart from the caller. If it gets through after the timeout, that is a duplicate
-        // of the retry, which at-least-once delivery allows; the sender ends when the broker unblocks.
+        // of the retry, which at-least-once delivery allows. Senders the broker is holding are capped: past the cap a
+        // send fails at once, as an outage, rather than piling up threads and channels during a long alarm.
+        if (!sendersInFlight.tryAcquire()) {
+            throw new EventPublishException("too many sends waiting on the broker: " + destination, null);
+        }
         long deadline = System.nanoTime() + confirmTimeout.toNanos();
         CompletableFuture<Void> publish = new CompletableFuture<>();
-        Thread sender = Thread.ofVirtual().name("platform-rabbit-send").start(() -> {
+        Thread sender = Thread.ofVirtual().name("platform-rabbit-send").unstarted(() -> {
             try {
                 rabbitTemplate.send(dest.exchange(), dest.routingKey(), message, correlation);
                 publish.complete(null);
             } catch (RuntimeException e) {
                 publish.completeExceptionally(e);
+            } finally {
+                sendersInFlight.release();
+                senders.remove(Thread.currentThread());
             }
         });
+        senders.add(sender);
+        sender.start();
         try {
             publish.get(confirmTimeout.toNanos(), TimeUnit.NANOSECONDS);
             awaitConfirm(destination, correlation, Math.max(0, deadline - System.nanoTime()));
diff --git a/messaging/platform-starter-messaging-rabbit/pom.xml b/messaging/platform-starter-messaging-rabbit/pom.xml
index fbfb736..74b827e 100644
--- a/messaging/platform-starter-messaging-rabbit/pom.xml
+++ b/messaging/platform-starter-messaging-rabbit/pom.xml
@@ -33,6 +33,11 @@
       <artifactId>platform-messaging-rabbit</artifactId>
       <version>${revision}</version>
     </dependency>
+    <!-- Boot 4 keeps its Rabbit auto-configuration in its own module: without it there is no RabbitTemplate. -->
+    <dependency>
+      <groupId>org.springframework.boot</groupId>
+      <artifactId>spring-boot-amqp</artifactId>
+    </dependency>
     <!-- The autoconfigure module's default JSON EventSerializer needs Jackson; it's optional
          there (a consumer supplying their own EventSerializer shouldn't be forced to add it), but
          the starter's whole point is a working zero-config default, so it's required here. -->
```
