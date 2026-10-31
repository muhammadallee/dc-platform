# Phase 3 Gate F — verification pass for two consumer-found fixes (HI-0068, HI-0069)

You are verifying a small change in an internal Spring Boot 4.1 platform (Hibernate 7.4.1, HikariCP 7.0.2,
ojdbc11, Oracle). Read-only checkout at commit 764dc14. Review only the diff below and the files it touches
(errors/platform-errors-autoconfigure/.../internal/DataAccessFailure.java,
data/platform-data-jpa-autoconfigure/.../PlatformDataJpaEnvironmentPostProcessor.java and their tests).

## What the consumer run observed

1. HI-0068. Oracle silent mid-transaction (Toxiproxy timeout toxic). The 60 s oracle.jdbc.ReadTimeout fires:
   `SQLRecoverableException: ORA-18730 Interrupted IO error: Socket read timed out` (SQLState 08006), translated
   to `DataAccessResourceFailureException` (would be 503). Spring's TransactionInterceptor then rolls back, the
   rollback fails, logs "Application exception overridden by rollback exception" and rethrows:
   `JpaSystemException: Unable to rollback against JDBC Connection` → `org.hibernate.TransactionException: Unable
   to rollback against JDBC Connection` → `java.sql.SQLException: Connection is closed` (Hikari's
   ProxyConnection.ClosedConnection; no SQLState). The handler answered 500 DC-CORE-0500, not retryable.
   Fix: that exact Hibernate message classifies as 503 DC-DATA-0503; "Unable to commit against JDBC Connection"
   stays unclassified because a failed commit's outcome is unknown. After the fix the consumer got
   503 DC-DATA-0503 after 63 s.
2. HI-0069. Each constraint violation wrote three WARN lines on the request's trace: Hibernate's
   `org.hibernate.orm.jdbc.error` "HHH000247: ErrorCode: 1, SQLState: 23000", the same logger with the ORA
   message (constraint and table names), and the platform handler's `data access failure [DC-DATA-0409]` WARN
   with the whole chain. The scope says the full exception is "logged once on the request's trace". Fix: the
   data-jpa environment post-processor (lowest-precedence defaults) adds
   `logging.level.org.hibernate.orm.jdbc.error=error`. After the fix the consumer saw exactly one WARN per
   violation, carrying the ORA- detail.

## Questions

For each fix: is it correct and sufficient, is anything over-classified (a 503 that must not be retryable) or
lost (an operator signal that now disappears, e.g. a failed statement whose exception is caught and swallowed by
application code, or a batch/async path with no handler), and are the tests adequate? Is the logger name right
for Hibernate 7.4 and does a lowest-precedence `logging.level.*` from an EnvironmentPostProcessor take effect
(EPP listener order vs LoggingApplicationListener)? Answer per fix: accept / accept with change / reject, with
file:line references. Be brief.

## Diff

```diff
diff --git a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
index 892fc70..397bdf1 100644
--- a/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
+++ b/data/platform-data-jpa-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessor.java
@@ -47,6 +47,10 @@ public class PlatformDataJpaEnvironmentPostProcessor implements EnvironmentPostP
         defaults.put("spring.jpa.properties.hibernate.order_updates", "true");
         // Persist and read timestamps in UTC regardless of the JVM/db session zone.
         defaults.put("spring.jpa.properties.hibernate.jdbc.time_zone", "UTC");
+        // Hibernate logs every failed statement's error code and driver message (constraint and table names) as two
+        // WARN lines before the exception propagates; the exception is then logged once, with that detail, where it is
+        // handled (the errors capability's single WARN on the request's trace, D111). Errors-only keeps it to that one.
+        defaults.put("logging.level.org.hibernate.orm.jdbc.error", "error");
         poolAndTimeoutDefaults(environment, defaults);
         flywayDefaults(defaults);
         environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java
index be07cb3..c8194f0 100644
--- a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/PlatformDataJpaEnvironmentPostProcessorTest.java
@@ -38,6 +38,7 @@ class PlatformDataJpaEnvironmentPostProcessorTest {
                 "spring.jpa.properties.hibernate.order_inserts", "true",
                 "spring.jpa.properties.hibernate.order_updates", "true",
                 "spring.jpa.properties.hibernate.jdbc.time_zone", "UTC",
+                "logging.level.org.hibernate.orm.jdbc.error", "error",
                 "spring.datasource.hikari.connection-timeout", "5000",
                 "spring.transaction.default-timeout", "30s",
                 "spring.jpa.properties.jakarta.persistence.query.timeout", "30000",
diff --git a/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/JdbcErrorLoggingSliceTest.java b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/JdbcErrorLoggingSliceTest.java
new file mode 100644
index 0000000..25a22a1
--- /dev/null
+++ b/data/platform-data-jpa-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/data/jpa/autoconfigure/slice/JdbcErrorLoggingSliceTest.java
@@ -0,0 +1,31 @@
+package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;
+
+import static org.assertj.core.api.Assertions.assertThat;
+
+import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
+import org.junit.jupiter.api.Test;
+import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
+import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
+import org.springframework.boot.logging.LogLevel;
+import org.springframework.boot.logging.LoggingSystem;
+
+/**
+ * The logging default reaches the logging system (it is applied after the environment post-processors run):
+ * Hibernate's per-statement error lines stay off, so a failed statement is logged once, where it is handled
+ * (HI-0069; the phase-03 consumer counted three WARN lines per constraint violation before).
+ */
+@DataJpaTest(properties = {
+        "spring.flyway.enabled=false",
+        "dc.platform.data.jpa.require-migrations=false"
+})
+@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
+class JdbcErrorLoggingSliceTest {
+
+    @Test
+    void hibernatesStatementErrorLinesAreOffByDefault() {
+        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());
+
+        assertThat(logging.getLoggerConfiguration("org.hibernate.orm.jdbc.error").getEffectiveLevel())
+                .isEqualTo(LogLevel.ERROR);
+    }
+}
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
index e83154c..752b41a 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
@@ -62,9 +62,13 @@ public enum DataAccessFailure {
             "java.sql.SQLRecoverableException");
 
     // Hibernate 7.4 JdbcCoordinatorImpl: a statement executed after the transaction deadline passed (flush, commit).
-    // Only this message: any other org.hibernate.TransactionException is not an availability problem.
+    // AbstractLogicalConnectionImplementor: a rollback on a connection that broke (observed: the driver read timeout
+    // closed it; Spring then rethrows this instead of the 08006 cause, and Hikari's closed-connection exception has no
+    // SQLState). Nothing was committed, so it is retryable. Only these messages: a failed commit's outcome is unknown,
+    // and any other org.hibernate.TransactionException is not an availability problem.
     private static final String HIBERNATE_TRANSACTION_EXCEPTION = "org.hibernate.TransactionException";
-    private static final String HIBERNATE_TIMEOUT_EXPIRED = "Transaction timeout expired";
+    private static final Set<String> HIBERNATE_UNAVAILABLE_MESSAGES = Set.of(
+            "Transaction timeout expired", "Unable to rollback against JDBC Connection");
 
     private static final int MAX_DEPTH = 32;
 
@@ -117,7 +121,7 @@ public enum DataAccessFailure {
             if (byType.isPresent()) {
                 return byType;
             }
-            if (isHibernateTransactionExpiry(t)) {
+            if (isHibernateUnavailable(t)) {
                 return Optional.of(UNAVAILABLE);
             }
             if (t instanceof SQLException sql) {
@@ -130,9 +134,9 @@ public enum DataAccessFailure {
         return Optional.empty();
     }
 
-    private static boolean isHibernateTransactionExpiry(Throwable t) {
+    private static boolean isHibernateUnavailable(Throwable t) {
         return t.getClass().getName().equals(HIBERNATE_TRANSACTION_EXCEPTION)
-                && HIBERNATE_TIMEOUT_EXPIRED.equals(t.getMessage());
+                && t.getMessage() != null && HIBERNATE_UNAVAILABLE_MESSAGES.contains(t.getMessage());
     }
 
     private static Optional<DataAccessFailure> byType(Class<?> type) {
diff --git a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
index 49ad305..e54a810 100644
--- a/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
+++ b/errors/platform-errors-autoconfigure/src/test/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailureTest.java
@@ -88,6 +88,19 @@ class DataAccessFailureTest {
                 .isEmpty();
     }
 
+    @Test
+    void aRollbackThatFailsOnABrokenConnectionIsUnavailable() {
+        // Observed in the phase-03 consumer run (silent Oracle, driver read timeout): Spring rethrows the
+        // rollback failure in place of the 08006 read timeout, and Hikari's closed-connection exception
+        // carries no SQLState. Nothing was committed, so the request may be retried.
+        Throwable rollback = new JpaSystemException(new TransactionException(
+                "Unable to rollback against JDBC Connection", new SQLException("Connection is closed")));
+        assertThat(DataAccessFailure.classify(rollback)).contains(DataAccessFailure.UNAVAILABLE);
+        // A failed commit is not: its outcome is unknown, so a retry could apply the change twice.
+        assertThat(DataAccessFailure.classify(new JpaSystemException(new TransactionException(
+                "Unable to commit against JDBC Connection", new SQLException("Connection is closed"))))).isEmpty();
+    }
+
     @Test
     void hibernatesNullabilityCheckIsAnIntegrityConflictButATransientReferenceIsNot() {
         assertThat(DataAccessFailure.classify(new PropertyValueException(
```
