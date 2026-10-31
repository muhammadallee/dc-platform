# Phase 3 Gate F — re-check of item 3 of f-verify2.out.md (commit veto) only

Read-only checkout at eb0436d. You rejected the commit veto because Spring 7.0.8's HibernateJpaDialect turns a commit
TransactionException over a SQLRecoverableException into RecoverableDataAccessException without the Hibernate
exception. The change below vetoes an UNAVAILABLE classification raised inside
AbstractPlatformTransactionManager.processCommit (stack frame anywhere in the cause chain), except Hibernate's
"Transaction timeout expired"; INTEGRITY/CONCURRENCY results are not vetoed. Tests use the real translator and an
AbstractPlatformTransactionManager subclass that raises the exception inside doCommit/doRollback.

Is the veto now correct? Any path where a lost connection at commit still becomes a retryable 503 (e.g. a
JpaTransactionManager commit, a TransactionTemplate, a JTA or reactive manager, an exception created outside
processCommit and rethrown), or where a legitimate pre-commit 503 is now wrongly vetoed? One short answer:
accept / accept with change / reject, file:line.

```diff
diff --git a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
index 8764e35..5016fda 100644
--- a/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
+++ b/errors/platform-errors-autoconfigure/src/main/java/ae/gov/dubaicustoms/platform/errors/autoconfigure/internal/DataAccessFailure.java
@@ -6,6 +6,7 @@ import java.util.IdentityHashMap;
 import java.util.Map;
 import java.util.Optional;
 import java.util.Set;
+import java.util.function.Predicate;
 
 /**
  * Classifies a data-access failure by walking its cause chain (D111). Spring translates exceptions
@@ -74,6 +75,12 @@ public enum DataAccessFailure {
     private static final String HIBERNATE_ROLLBACK_FAILED = "Unable to rollback against JDBC Connection";
     private static final String HIKARI_CLOSED_CONNECTION = "Connection is closed";
 
+    // Spring's commit. Its translator can drop Hibernate's commit exception (a recoverable SQLException cause becomes
+    // RecoverableDataAccessException), so a lost connection is recognised as a commit failure by where it was raised.
+    private static final String SPRING_TRANSACTION_MANAGER =
+            "org.springframework.transaction.support.AbstractPlatformTransactionManager";
+    private static final String SPRING_COMMIT = "processCommit";
+
     private static final int MAX_DEPTH = 32;
 
     // Static constants (not enum fields) so the error-code registry gate sees them; a nested holder
@@ -117,6 +124,16 @@ public enum DataAccessFailure {
      * @return the classification, if any
      */
     public static Optional<DataAccessFailure> classify(Throwable exception) {
+        Optional<DataAccessFailure> failure = byCauseChain(exception);
+        // A connection lost while committing leaves the outcome unknown: never a retryable 503. A conflict found by
+        // the commit-time flush stays a 409; a deadline that passed before the commit is matched by its message.
+        if (failure.equals(Optional.of(UNAVAILABLE)) && raisedDuringCommit(exception) && !isDeadlineExpiry(exception)) {
+            return Optional.empty();
+        }
+        return failure;
+    }
+
+    private static Optional<DataAccessFailure> byCauseChain(Throwable exception) {
         Map<Throwable, Boolean> seen = new IdentityHashMap<>();
         int depth = 0;
         for (Throwable t = exception; t != null && depth < MAX_DEPTH && seen.put(t, Boolean.TRUE) == null;
@@ -144,6 +161,33 @@ public enum DataAccessFailure {
         return Optional.empty();
     }
 
+    private static boolean raisedDuringCommit(Throwable exception) {
+        return anyInChain(exception, t -> {
+            for (StackTraceElement frame : t.getStackTrace()) {
+                if (SPRING_COMMIT.equals(frame.getMethodName()) && SPRING_TRANSACTION_MANAGER.equals(frame.getClassName())) {
+                    return true;
+                }
+            }
+            return false;
+        });
+    }
+
+    private static boolean isDeadlineExpiry(Throwable exception) {
+        return anyInChain(exception, t -> isHibernateTransactionException(t, HIBERNATE_TIMEOUT_EXPIRED));
+    }
+
+    private static boolean anyInChain(Throwable exception, Predicate<Throwable> test) {
+        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
+        int depth = 0;
+        for (Throwable t = exception; t != null && depth < MAX_DEPTH && seen.put(t, Boolean.TRUE) == null;
+                t = t.getCause(), depth++) {
+            if (test.test(t)) {
+                return true;
+            }
+        }
+        return false;
+    }
+
     private static boolean isHibernateTransactionException(Throwable t, String message) {
         return t.getClass().getName().equals(HIBERNATE_TRANSACTION_EXCEPTION) && message.equals(t.getMessage());
     }
```
