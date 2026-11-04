# Phase 3 Gate F — verification pass 2: HI-0070 (configurable Oracle read timeouts) and the commit veto

Read-only checkout at 2bcdded. Spring Boot 4.1, HikariCP 7.0.2, ojdbc11, Flyway 11 (Boot-managed). Review the diff
below and the files it touches. Be brief; answer per item accept / accept with change / reject, with file:line.

## Context

- A consumer run failed startup: on a slow disk a CREATE TABLE migration ran over 60 s, and the platform's 60 s
  `oracle.jdbc.ReadTimeout` on Flyway's own connection (spring.flyway.user, Boot's SimpleDriverDataSource) closed it.
  The read timeout is a per-statement cap: it also cuts a longer `@Transactional(timeout)` at 60 s.
- Owner decision HD14: two properties, `dc.platform.data.jpa.oracle.read-timeout` (pool, 60s) and
  `dc.platform.data.jpa.oracle.flyway-read-timeout` (Flyway's own connection, 10m). A data-source-properties entry or
  JVM system property still wins. Both BeanPostProcessors bind the values from the Environment with
  `Binder.bindOrCreate` rather than injecting DataJpaProperties (static BPPs).
- Measured in the consumer (three identical runs): a peer that accepts TCP and never answers (busybox nc) fails
  startup after 608-610 s: `ORA-17002: I/O error: Socket read timed out, connect lapse 600043 ms`. So
  oracle.net.CONNECT_TIMEOUT (10 s) covers only the TCP connect; the handshake read is bounded by the read timeout.
  Mid-transaction silence ends at 63 s with 503 DC-DATA-0503 (pool, 60 s). Documented in data.md.
- Your previous pass (f-verify.out.md) asked for a commit veto and a narrowed rollback rule in DataAccessFailure;
  both are in this diff too (a5d6ae4), and the Hibernate-logging default was reverted (scope amendment A2).

## Questions

1. Is binding from the Environment in the BPPs correct and consistent with the @ConfigurationProperties record
   (defaults, relaxed names, validation of negatives: the record's compact constructor throws)? Any case where the
   two disagree (e.g. a value only in a later property source, a profile-specific value)?
2. Is anything missing for migrations that run through the pool (no separate Flyway user), or for the `{vendor}`
   detection connection?
3. The commit veto and narrowed rollback rule: correct now?
4. Docs (data.md, CHANGELOG): accurate about the silent-peer timing and the per-statement cap?

## Diff

```diff
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
index 752b41a..8764e35 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
@@ -61,14 +61,18 @@ public enum DataAccessFailure {
             "java.sql.SQLNonTransientConnectionException",
             "java.sql.SQLRecoverableException");
 
-    // Hibernate 7.4 JdbcCoordinatorImpl: a statement executed after the transaction deadline passed (flush, commit).
-    // AbstractLogicalConnectionImplementor: a rollback on a connection that broke (observed: the driver read timeout
-    // closed it; Spring then rethrows this instead of the 08006 cause, and Hikari's closed-connection exception has no
-    // SQLState). Nothing was committed, so it is retryable. Only these messages: a failed commit's outcome is unknown,
-    // and any other org.hibernate.TransactionException is not an availability problem.
+    // Hibernate 7.4 messages of org.hibernate.TransactionException (any other one is not an availability problem):
+    // - JdbcCoordinatorImpl: a statement executed after the transaction deadline passed (flush, commit) -> 503;
+    // - AbstractLogicalConnectionImplementor, for any SQLException from commit: the outcome is unknown, so nothing
+    //   below it may make the failure retryable (a retry could apply the change twice);
+    // - the same, from rollback: its cause classifies as usual, and Hikari's closed-connection exception (no
+    //   SQLState) counts as a lost connection. Observed: the driver read timeout closed the connection mid-transaction
+    //   and Spring rethrew the failed rollback in place of the 08006 cause; nothing was committed.
     private static final String HIBERNATE_TRANSACTION_EXCEPTION = "org.hibernate.TransactionException";
-    private static final Set<String> HIBERNATE_UNAVAILABLE_MESSAGES = Set.of(
-            "Transaction timeout expired", "Unable to rollback against JDBC Connection");
+    private static final String HIBERNATE_TIMEOUT_EXPIRED = "Transaction timeout expired";
+    private static final String HIBERNATE_COMMIT_FAILED = "Unable to commit against JDBC Connection";
+    private static final String HIBERNATE_ROLLBACK_FAILED = "Unable to rollback against JDBC Connection";
+    private static final String HIKARI_CLOSED_CONNECTION = "Connection is closed";
 
     private static final int MAX_DEPTH = 32;
 
@@ -121,7 +125,13 @@ public enum DataAccessFailure {
             if (byType.isPresent()) {
                 return byType;
             }
-            if (isHibernateUnavailable(t)) {
+            if (isHibernateTransactionException(t, HIBERNATE_TIMEOUT_EXPIRED)) {
+                return Optional.of(UNAVAILABLE);
+            }
+            if (isHibernateTransactionException(t, HIBERNATE_COMMIT_FAILED)) {
+                return Optional.empty();
+            }
+            if (isHibernateTransactionException(t, HIBERNATE_ROLLBACK_FAILED) && isClosedConnection(t.getCause())) {
                 return Optional.of(UNAVAILABLE);
             }
             if (t instanceof SQLException sql) {
@@ -134,9 +144,13 @@ public enum DataAccessFailure {
         return Optional.empty();
     }
 
-    private static boolean isHibernateUnavailable(Throwable t) {
-        return t.getClass().getName().equals(HIBERNATE_TRANSACTION_EXCEPTION)
-                && t.getMessage() != null && HIBERNATE_UNAVAILABLE_MESSAGES.contains(t.getMessage());
+    private static boolean isHibernateTransactionException(Throwable t, String message) {
+        return t.getClass().getName().equals(HIBERNATE_TRANSACTION_EXCEPTION) && message.equals(t.getMessage());
+    }
+
+    private static boolean isClosedConnection(Throwable cause) {
+        return cause instanceof SQLException sql && sql.getSQLState() == null
+                && HIKARI_CLOSED_CONNECTION.equals(sql.getMessage());
     }
 
     private static Optional<DataAccessFailure> byType(Class<?> type) {
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
index e54a810..af51826 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
@@ -96,9 +96,25 @@ class DataAccessFailureTest {
         Throwable rollback = new JpaSystemException(new TransactionException(
                 "Unable to rollback against JDBC Connection", new SQLException("Connection is closed")));
         assertThat(DataAccessFailure.classify(rollback)).contains(DataAccessFailure.UNAVAILABLE);
-        // A failed commit is not: its outcome is unknown, so a retry could apply the change twice.
+        // Hibernate uses the same message for any SQLException from rollback: other causes classify as usual.
         assertThat(DataAccessFailure.classify(new JpaSystemException(new TransactionException(
-                "Unable to commit against JDBC Connection", new SQLException("Connection is closed"))))).isEmpty();
+                "Unable to rollback against JDBC Connection", new SQLException("I/O error", "08006")))))
+                .contains(DataAccessFailure.UNAVAILABLE);
+        assertThat(DataAccessFailure.classify(new JpaSystemException(new TransactionException(
+                "Unable to rollback against JDBC Connection", new SQLException("ORA-00600 internal error", "HY000")))))
+                .isEmpty();
+    }
+
+    @Test
+    void aFailedCommitIsNeverRetryableWhateverItsCause() {
+        // The commit's outcome is unknown: a retry could apply the change twice, so even a lost-connection cause
+        // stays unclassified (the generic, non-retryable 500).
+        for (SQLException cause : new SQLException[] {new SQLException("Connection is closed"),
+                new SQLRecoverableException("ORA-18730 Socket read timed out", "08006"),
+                new SQLException("I/O error", "08006")}) {
+            assertThat(DataAccessFailure.classify(new JpaSystemException(
+                    new TransactionException("Unable to commit against JDBC Connection", cause)))).isEmpty();
+        }
     }
 
     @Test
diff --git a/CHANGELOG.md b/CHANGELOG.md
index 2879319..b4d9535 100644
--- a/CHANGELOG.md
+++ b/CHANGELOG.md
@@ -92,13 +92,20 @@ versioning: Semantic Versioning on the release train (all artifacts share one ve
   inside a transaction, so give it `.setTimeout(…)` where that matters; Oracle pools
   and a separate Flyway migration connection get the driver's `oracle.jdbc.ReadTimeout` 60 s and
   `oracle.net.CONNECT_TIMEOUT` 10 s (not a pool the application started itself, nor a `Flyway` bean of its
-  own built from a URL: set them there); leak detection (20 s) under `local`, never with `prod`.
+  own built from a URL: set them there); leak detection (20 s) under `local`, never with `prod`. The
+  read timeout is also the longest any single statement may run: raise `dc.platform.data.jpa.oracle.read-timeout`
+  for transactions longer than 60 s. Flyway's own connection (a separate migration user) gets 10 min instead
+  (`dc.platform.data.jpa.oracle.flyway-read-timeout`), so a long migration statement can finish (HD14);
+  a database that accepts connections but never answers then fails startup after that time (10 min).
 - **Flyway naming is validated**: `spring.flyway.validate-migration-naming=true`, so a migration file not
   named `V<version>__<description>.sql` now fails startup instead of being silently skipped. Out-of-order
   migrations outside the `local` profile fail startup with a diagnostic, however they were enabled; `clean-disabled`,
   `validate-on-migrate` and `out-of-order=false` are pinned (D112).
 
 ### Added
+- Data: `dc.platform.data.jpa.oracle.read-timeout` (default `60s`) and
+  `dc.platform.data.jpa.oracle.flyway-read-timeout` (default `10m`): the Oracle driver read timeout on the pool
+  and on Flyway's own connection (HI-0070, HD14). Zero switches the bound off; negative values fail startup.
 - Data: `platform-starter-data-jpa-oracle` — the data-jpa stack plus the Oracle JDBC driver and
   `flyway-database-oracle` (HD12). Archetype: `-Ddatabase=oracle` (with the `data` feature) generates a
   service on it; `-Ddatabase` defaults to `h2` (D113).
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/DataJpaProperties.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/DataJpaProperties.java
index 1e1394f..3d7a6ea 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/DataJpaProperties.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/DataJpaProperties.java
@@ -1,5 +1,6 @@
 package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure;
 
+import java.time.Duration;
 import org.springframework.boot.context.properties.ConfigurationProperties;
 import org.springframework.boot.context.properties.bind.DefaultValue;
 import org.springframework.validation.annotation.Validated;
@@ -16,6 +17,7 @@ import org.springframework.validation.annotation.Validated;
  * @param enabled master kill switch for the whole capability
  * @param requireMigrations fail startup when JPA is configured but Flyway is absent; set to
  *        {@code false} to run JPA without managed migrations
+ * @param oracle the Oracle driver's socket read timeouts (D110)
  * @since 0.2.0
  */
 @Validated
@@ -24,5 +26,35 @@ public record DataJpaProperties(
         /** Master switch for the capability. */
         @DefaultValue("true") boolean enabled,
         /** Fail startup when JPA is present but Flyway is not; set false to opt out of migrations. */
-        @DefaultValue("true") boolean requireMigrations) {
+        @DefaultValue("true") boolean requireMigrations,
+        /** Oracle driver read timeouts for the pool and for Flyway's own connection. */
+        @DefaultValue Oracle oracle) {
+
+    /**
+     * The Oracle driver's socket read timeout ({@code oracle.jdbc.ReadTimeout}), the backstop for what a
+     * statement cancel cannot stop (a dead network, a PL/SQL or lock wait). It is also the longest any
+     * single statement may run, whatever its transaction timeout. A {@code data-source-properties} entry
+     * or a JVM system property of that name still wins.
+     *
+     * @param readTimeout read timeout on every connection of an Oracle pool, and so the longest a single
+     *        statement may run; above the 30 s transaction default, so a slow statement is cancelled by that
+     *        first. Raise it for a service whose transactions are longer
+     * @param flywayReadTimeout read timeout on Flyway's own connection ({@code spring.flyway.user} or
+     *        {@code url}), so a long migration statement (an index build) can finish while a silent database
+     *        still fails startup
+     * @since 1.0.0
+     */
+    public record Oracle(
+            /** Read timeout on an Oracle pool's connections; the longest a single statement may run. */
+            @DefaultValue("60s") Duration readTimeout,
+            /** Read timeout on Flyway's own Oracle connection (a separate migration user or URL). */
+            @DefaultValue("10m") Duration flywayReadTimeout) {
+
+        /** Rejects a negative timeout; zero switches the bound off (the driver waits indefinitely). */
+        public Oracle {
+            if (readTimeout.isNegative() || flywayReadTimeout.isNegative()) {
+                throw new IllegalArgumentException("dc.platform.data.jpa.oracle read timeouts must not be negative");
+            }
+        }
+    }
 }
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java
index 337687c..913beb9 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfiguration.java
@@ -102,8 +102,8 @@ public class PlatformDataJpaAutoConfiguration {
 
         @Bean
         @ConditionalOnMissingBean(name = "platformOracleConnectionDefaults")
-        static OracleConnectionDefaults platformOracleConnectionDefaults() {
-            return new OracleConnectionDefaults();
+        static OracleConnectionDefaults platformOracleConnectionDefaults(Environment environment) {
+            return new OracleConnectionDefaults(environment);
         }
     }
 
@@ -125,8 +125,8 @@ public class PlatformDataJpaAutoConfiguration {
 
         @Bean
         @ConditionalOnMissingBean(name = "platformOracleFlywayConnectionDefaults")
-        static OracleFlywayConnectionDefaults platformOracleFlywayConnectionDefaults() {
-            return new OracleFlywayConnectionDefaults();
+        static OracleFlywayConnectionDefaults platformOracleFlywayConnectionDefaults(Environment environment) {
+            return new OracleFlywayConnectionDefaults(environment);
         }
     }
 
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
index e1be294..eaab6a4 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaults.java
@@ -1,14 +1,19 @@
 package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
 
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.DataJpaProperties;
 import com.zaxxer.hikari.HikariDataSource;
+import java.time.Duration;
 import java.util.Map;
 import org.springframework.beans.factory.config.BeanPostProcessor;
+import org.springframework.boot.context.properties.bind.Binder;
 import org.springframework.core.Ordered;
+import org.springframework.core.env.Environment;
 
 /**
  * Adds the Oracle driver's network bounds to a Hikari pool whose JDBC URL is {@code jdbc:oracle:}
  * (D110): a socket read timeout as the backstop for what a statement cancel cannot stop (a PL/SQL or
- * lock wait, a dead network) and a connect timeout for an unreachable host.
+ * lock wait, a dead network), {@code dc.platform.data.jpa.oracle.read-timeout}, and a 10 s connect timeout
+ * for an unreachable host.
  *
  * <p>Applied to the pool bean after Boot has bound {@code spring.datasource.hikari.*} and before the
  * pool starts, keyed on the pool's own URL — not on an environment property (the URL can come from a
@@ -16,15 +21,22 @@ import org.springframework.core.Ordered;
  * Oracle-starter service runs on H2 locally). A value the application already set, as a
  * {@code data-source-properties} entry or a JVM system property, is left alone. A pool the application
  * started itself ({@code new HikariDataSource(config)}) is left alone too: its configuration is sealed.
+ * The timeouts are read from the environment, not injected: a
+ * {@code BeanPostProcessor} must not pull other beans in early.
  *
- * <p>Thread-safe; stateless.
+ * <p>Thread-safe; stateless (the environment is read for each pool).
  */
 public final class OracleConnectionDefaults implements BeanPostProcessor, Ordered {
 
-    /** Above the 30 s transaction timeout, so a slow query is cancelled by that first. */
-    static final Map<String, String> DEFAULTS = Map.of(
-            "oracle.jdbc.ReadTimeout", "60000",
-            "oracle.net.CONNECT_TIMEOUT", "10000");
+    static final String READ_TIMEOUT = "oracle.jdbc.ReadTimeout";
+    static final String CONNECT_TIMEOUT = "oracle.net.CONNECT_TIMEOUT";
+    private static final String CONNECT_TIMEOUT_MILLIS = "10000";
+
+    private final Environment environment;
+
+    public OracleConnectionDefaults(Environment environment) {
+        this.environment = environment;
+    }
 
     @Override
     public Object postProcessBeforeInitialization(Object bean, String beanName) {
@@ -32,7 +44,7 @@ public final class OracleConnectionDefaults implements BeanPostProcessor, Ordere
         // (running, suspended or closed: getHikariPoolMXBean() is set from the start).
         if (bean instanceof HikariDataSource pool && pool.getHikariPoolMXBean() == null && !pool.isClosed()
                 && isOracle(pool.getJdbcUrl())) {
-            DEFAULTS.forEach((key, value) -> {
+            driverProperties(oracle(environment).readTimeout()).forEach((key, value) -> {
                 if (!pool.getDataSourceProperties().containsKey(key) && System.getProperty(key) == null) {
                     pool.addDataSourceProperty(key, value);
                 }
@@ -41,7 +53,17 @@ public final class OracleConnectionDefaults implements BeanPostProcessor, Ordere
         return bean;
     }
 
-    private static boolean isOracle(String url) {
+    /** The two driver properties, in milliseconds, for a given read timeout. */
+    static Map<String, String> driverProperties(Duration readTimeout) {
+        return Map.of(READ_TIMEOUT, Long.toString(readTimeout.toMillis()), CONNECT_TIMEOUT, CONNECT_TIMEOUT_MILLIS);
+    }
+
+    /** The bound {@code dc.platform.data.jpa.oracle} values, with their defaults when unset. */
+    static DataJpaProperties.Oracle oracle(Environment environment) {
+        return Binder.get(environment).bindOrCreate("dc.platform.data.jpa.oracle", DataJpaProperties.Oracle.class);
+    }
+
+    static boolean isOracle(String url) {
         return url != null && url.startsWith("jdbc:oracle:");
     }
 
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaults.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaults.java
index 6250885..937049d 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaults.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaults.java
@@ -3,35 +3,44 @@ package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;
 import java.util.Properties;
 import org.flywaydb.core.Flyway;
 import org.springframework.beans.factory.config.BeanPostProcessor;
+import org.springframework.core.env.Environment;
 import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;
 
 /**
- * Adds the Oracle driver's network bounds (D110, the same values as {@link OracleConnectionDefaults}) to
- * Flyway's own data source. With {@code spring.flyway.user} or {@code spring.flyway.url} set — a separate
- * schema owner for migrations — Boot gives Flyway a {@code SimpleDriverDataSource} that is not a bean and
- * shares nothing with the pool, so without this a database that stops answering during migration would
- * block startup indefinitely.
+ * Adds the Oracle driver's network bounds (D110) to Flyway's own data source: the 10 s connect timeout of
+ * {@link OracleConnectionDefaults} and a longer read timeout, {@code dc.platform.data.jpa.oracle.flyway-read-timeout}
+ * (10 min), so a long migration statement can finish while a database that stops answering still fails startup.
+ * With {@code spring.flyway.user} or {@code spring.flyway.url} set — a separate schema owner for migrations —
+ * Boot gives Flyway a {@code SimpleDriverDataSource} that is not a bean and shares nothing with the pool, so
+ * without this a silent database would block startup indefinitely.
  *
  * <p>Applied on the {@code Flyway} bean, whose configuration is complete, before anything connects. A
- * JVM system property of the same name wins (it is the only way to set driver properties on this data
+ * JVM system property of the same name wins (it is the only other way to set driver properties on this data
  * source). Thread-safe; stateless.
  */
 public final class OracleFlywayConnectionDefaults implements BeanPostProcessor {
 
+    private final Environment environment;
+
+    public OracleFlywayConnectionDefaults(Environment environment) {
+        this.environment = environment;
+    }
+
     @Override
     public Object postProcessBeforeInitialization(Object bean, String beanName) {
         if (bean instanceof Flyway flyway
                 && flyway.getConfiguration().getDataSource() instanceof AbstractDriverBasedDataSource dataSource
-                && dataSource.getUrl() != null && dataSource.getUrl().startsWith("jdbc:oracle:")) {
+                && OracleConnectionDefaults.isOracle(dataSource.getUrl())) {
             Properties properties = new Properties();
             if (dataSource.getConnectionProperties() != null) {
                 properties.putAll(dataSource.getConnectionProperties());
             }
-            OracleConnectionDefaults.DEFAULTS.forEach((key, value) -> {
-                if (!properties.containsKey(key) && System.getProperty(key) == null) {
-                    properties.setProperty(key, value);
-                }
-            });
+            OracleConnectionDefaults.driverProperties(OracleConnectionDefaults.oracle(environment).flywayReadTimeout())
+                    .forEach((key, value) -> {
+                        if (!properties.containsKey(key) && System.getProperty(key) == null) {
+                            properties.setProperty(key, value);
+                        }
+                    });
             dataSource.setConnectionProperties(properties);
         }
         return bean;
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
index 85d4630..29a6ee6 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaAutoConfigurationTest.java
@@ -10,6 +10,7 @@ import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal.OracleFlyway
 import com.zaxxer.hikari.HikariConfig;
 import com.zaxxer.hikari.HikariDataSource;
 import jakarta.persistence.EntityManagerFactory;
+import java.time.Duration;
 import java.util.Optional;
 import org.junit.jupiter.api.Test;
 import org.springframework.boot.autoconfigure.AutoConfigurations;
@@ -127,7 +128,15 @@ class PlatformDataJpaAutoConfigurationTest {
     void propertiesDefaultSnapshot() {
         // §7.2: a changed default fails here until the snapshot is consciously updated (CHANGELOG).
         runner.run(context -> assertThat(context.getBean(DataJpaProperties.class))
-                .isEqualTo(new DataJpaProperties(true, true)));
+                .isEqualTo(new DataJpaProperties(true, true,
+                        new DataJpaProperties.Oracle(Duration.ofSeconds(60), Duration.ofMinutes(10)))));
+    }
+
+    @Test
+    void aNegativeOracleReadTimeoutFailsStartup() {
+        runner.withPropertyValues("dc.platform.data.jpa.oracle.read-timeout=-1s")
+                .run(context -> assertThat(context).hasFailed()
+                        .getFailure().rootCause().hasMessageContaining("must not be negative"));
     }
 
     @Test
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
index e42a986..19cd47a 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleConnectionDefaultsTest.java
@@ -5,10 +5,11 @@ import static org.assertj.core.api.Assertions.assertThat;
 import com.zaxxer.hikari.HikariConfig;
 import com.zaxxer.hikari.HikariDataSource;
 import org.junit.jupiter.api.Test;
+import org.springframework.mock.env.MockEnvironment;
 
 class OracleConnectionDefaultsTest {
 
-    private final OracleConnectionDefaults defaults = new OracleConnectionDefaults();
+    private final OracleConnectionDefaults defaults = new OracleConnectionDefaults(new MockEnvironment());
 
     @Test
     void anOraclePoolGetsTheDriverReadAndConnectTimeouts() {
@@ -20,6 +21,19 @@ class OracleConnectionDefaultsTest {
                 .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
     }
 
+    @Test
+    void thePlatformReadTimeoutPropertySetsThePoolBound() {
+        // A service whose transactions run longer than 60 s raises the per-statement cap with it.
+        OracleConnectionDefaults configured = new OracleConnectionDefaults(
+                new MockEnvironment().withProperty("dc.platform.data.jpa.oracle.read-timeout", "5m"));
+        HikariDataSource pool = pool("jdbc:oracle:thin:@//db:1521/APP");
+        configured.postProcessBeforeInitialization(pool, "dataSource");
+
+        assertThat(pool.getDataSourceProperties())
+                .containsEntry("oracle.jdbc.ReadTimeout", "300000")
+                .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
+    }
+
     @Test
     void anotherDatabaseIsLeftAlone() {
         // An Oracle-starter service runs on H2 locally with ojdbc11 on the classpath.
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaultsTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaultsTest.java
index efea808..89d0239 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaultsTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/internal/OracleFlywayConnectionDefaultsTest.java
@@ -5,23 +5,36 @@ import static org.assertj.core.api.Assertions.assertThat;
 import java.util.Properties;
 import org.flywaydb.core.Flyway;
 import org.junit.jupiter.api.Test;
+import org.springframework.mock.env.MockEnvironment;
 import org.springframework.jdbc.datasource.SimpleDriverDataSource;
 
 /** The data source Boot builds for {@code spring.flyway.user}/{@code url}; nothing connects here. */
 class OracleFlywayConnectionDefaultsTest {
 
-    private final OracleFlywayConnectionDefaults defaults = new OracleFlywayConnectionDefaults();
+    private final OracleFlywayConnectionDefaults defaults = new OracleFlywayConnectionDefaults(new MockEnvironment());
 
     @Test
-    void aSeparateOracleMigrationDataSourceGetsTheDriverReadAndConnectTimeouts() {
+    void aSeparateOracleMigrationDataSourceGetsTheLongerFlywayReadTimeoutAndTheConnectTimeout() {
+        // 10 min, not the pool's 60 s: one migration statement (an index build) may run for minutes.
         SimpleDriverDataSource dataSource = dataSource("jdbc:oracle:thin:@//db:1521/APP");
         defaults.postProcessBeforeInitialization(flyway(dataSource), "flyway");
 
         assertThat(dataSource.getConnectionProperties())
-                .containsEntry("oracle.jdbc.ReadTimeout", "60000")
+                .containsEntry("oracle.jdbc.ReadTimeout", "600000")
                 .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
     }
 
+    @Test
+    void theFlywayReadTimeoutPropertySetsTheMigrationBound() {
+        OracleFlywayConnectionDefaults configured = new OracleFlywayConnectionDefaults(new MockEnvironment()
+                .withProperty("dc.platform.data.jpa.oracle.flyway-read-timeout", "30m")
+                .withProperty("dc.platform.data.jpa.oracle.read-timeout", "2m"));
+        SimpleDriverDataSource dataSource = dataSource("jdbc:oracle:thin:@//db:1521/APP");
+        configured.postProcessBeforeInitialization(flyway(dataSource), "flyway");
+
+        assertThat(dataSource.getConnectionProperties()).containsEntry("oracle.jdbc.ReadTimeout", "1800000");
+    }
+
     @Test
     void anotherDatabaseIsLeftAlone() {
         SimpleDriverDataSource dataSource = dataSource("jdbc:h2:mem:app");
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleFlywayPostureIT.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleFlywayPostureIT.java
index fc97576..e950007 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleFlywayPostureIT.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/oracle/OracleFlywayPostureIT.java
@@ -119,7 +119,7 @@ class OracleFlywayPostureIT {
                             .getBean(Flyway.class).getConfiguration().getDataSource();
                     assertThat(migration.getUsername()).isEqualTo(owner[0]);
                     assertThat(migration.getConnectionProperties())
-                            .containsEntry("oracle.jdbc.ReadTimeout", "60000")
+                            .containsEntry("oracle.jdbc.ReadTimeout", "600000") // flyway-read-timeout, 10 min
                             .containsEntry("oracle.net.CONNECT_TIMEOUT", "10000");
                 });
         try (Connection connection = DriverManager.getConnection(OracleContainers.oracle().getJdbcUrl(), owner[0],
diff --git a/docs/modules/data.md b/docs/modules/data.md
index 5aad085..dc7824c 100644
--- a/docs/modules/data.md
+++ b/docs/modules/data.md
@@ -51,11 +51,20 @@ a `Money` attribute converter, and a Flyway-presence guard — all on by default
     Hibernate's `Session.createSelectionQuery(…)` and a query on the raw `getDelegate()`. On those, set the
     timeout on the query yourself (`.setTimeout(seconds)`). `EntityManager.find` outside a transaction is
     not a query; only the read timeout below bounds it;
-  - on a pool whose URL is `jdbc:oracle:`, the driver's `oracle.jdbc.ReadTimeout=60000` (the backstop
-    for what a cancel cannot stop: a PL/SQL or lock wait, a dead network) and
-    `oracle.net.CONNECT_TIMEOUT=10000` are added to the pool's `data-source-properties`. The same two are
-    added to Flyway's own connection when migrations run as a separate user (see *Schema owner and
-    runtime user*). A pool you build and start yourself (`new HikariDataSource(config)`) is sealed, so
+  - on a pool whose URL is `jdbc:oracle:`, the driver's `oracle.jdbc.ReadTimeout` (the backstop for what
+    a cancel cannot stop: a PL/SQL or lock wait, a dead network) and `oracle.net.CONNECT_TIMEOUT=10000` are
+    added to the pool's `data-source-properties`. The read timeout is
+    `dc.platform.data.jpa.oracle.read-timeout` (default `60s`), and it is also **the longest any single
+    statement may run**, whatever its transaction timeout: a service with `@Transactional(timeout = 300)`
+    batch work raises it to match. Flyway's own connection, when migrations run as a separate user (see
+    *Schema owner and runtime user*), gets the connect timeout and
+    `dc.platform.data.jpa.oracle.flyway-read-timeout` (default `10m`), so a long migration statement (an
+    index build) can finish while a database that stops answering still fails startup. Migrations that run
+    through the pool (no separate Flyway user) get the pool's value. The connect timeout covers only the
+    TCP connect: a database that accepts the connection and then never answers is ended by the read timeout
+    (measured: a silent peer at startup failed after 610 s with a separate Flyway user, `ORA-17002 … Socket
+    read timed out`). A shorter startup probe restarts the service first, which also bounds it; lower
+    `flyway-read-timeout` if a faster, clearer failure matters more than long migration statements; A pool you build and start yourself (`new HikariDataSource(config)`) is sealed, so
     nothing is added to it: set both properties on its config;
   - `leak-detection-threshold=20000` (a stack trace for a connection held over 20 s) only under the
     `local` profile, and never when `prod` is active as well.
```
