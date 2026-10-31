package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.function.Supplier;
import org.hibernate.PropertyValueException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.TransactionException;
import org.hibernate.TransientPropertyValueException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * Classification of the cause chains observed on Oracle ({@code OracleExceptionPathsIT} in
 * data-jpa-autoconfigure), rebuilt here from the real types, plus the negatives: an unrelated or a
 * syntax error stays a 500 (it is a bug, not a data conflict).
 */
class DataAccessFailureTest {

    private static final String ORACLE_UNIQUE = "ORA-00001: unique constraint (APP.ORDERS_REF_UK) violated";

    @Test
    void translatedDuplicateKeyFromABatchedInsertIsAnIntegrityConflict() {
        // DataIntegrityViolationException -> Hibernate ConstraintViolationException -> BatchUpdateException
        BatchUpdateException batch = new BatchUpdateException(ORACLE_UNIQUE, "23000", 1, new int[0]);
        Throwable chain = new DataIntegrityViolationException("could not execute batch",
                new ConstraintViolationException("could not execute batch", batch, "APP.ORDERS_REF_UK"));
        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
    }

    @Test
    void untranslatedHibernateConstraintViolationIsAnIntegrityConflict() {
        Throwable chain = new ConstraintViolationException("could not execute statement",
                new SQLIntegrityConstraintViolationException(ORACLE_UNIQUE, "23000", 1), "APP.ORDERS_REF_UK");
        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
    }

    @Test
    void integritySqlStateAnywhereInTheChainIsAnIntegrityConflict() {
        Throwable chain = new IllegalStateException("wrapped by some library",
                new BatchUpdateException("ORA-02291: integrity constraint violated", "23000", 2291, new int[0]));
        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
    }

    @Test
    void lockingFailuresAreConcurrentUpdates() {
        assertThat(DataAccessFailure.classify(new OptimisticLockingFailureException("row was updated")))
                .contains(DataAccessFailure.CONCURRENT_UPDATE);
        assertThat(DataAccessFailure.classify(new StaleObjectStateException("OracleWidget", 1L)))
                .contains(DataAccessFailure.CONCURRENT_UPDATE);
        assertThat(DataAccessFailure.classify(new jakarta.persistence.LockTimeoutException(
                "ORA-00054: resource busy and acquire with NOWAIT specified")))
                .contains(DataAccessFailure.CONCURRENT_UPDATE);
        assertThat(DataAccessFailure.classify(new SQLException("deadlock", "40001")))
                .contains(DataAccessFailure.CONCURRENT_UPDATE);
    }

    @Test
    void noConnectionTimeoutsAndLostConnectionsAreUnavailable() {
        Throwable poolTimeout = new CannotCreateTransactionException("Could not open JPA EntityManager",
                new JDBCConnectionException("Unable to acquire JDBC Connection",
                        new SQLTransientConnectionException("HikariPool-1 - Connection is not available")));
        assertThat(DataAccessFailure.classify(poolTimeout)).contains(DataAccessFailure.UNAVAILABLE);
        assertThat(DataAccessFailure.classify(new jakarta.persistence.QueryTimeoutException(
                "ORA-01013: user requested cancel of current operation")))
                .contains(DataAccessFailure.UNAVAILABLE);
        assertThat(DataAccessFailure.classify(new SQLRecoverableException("IO Error: Connection reset", "08006")))
                .contains(DataAccessFailure.UNAVAILABLE);
    }

    @Test
    void aTransactionDeadlinePassedBeforeFlushOrCommitIsUnavailable() {
        // Observed on Oracle (OracleTimeoutDefaultsIT): flush -> raw TransactionException; commit ->
        // JpaSystemException wrapping it. Only Hibernate's deadline message counts.
        TransactionException expired = new TransactionException("Transaction timeout expired");
        assertThat(DataAccessFailure.classify(expired)).contains(DataAccessFailure.UNAVAILABLE);
        assertThat(DataAccessFailure.classify(new JpaSystemException(expired))).contains(DataAccessFailure.UNAVAILABLE);
        assertThat(DataAccessFailure.classify(new TransactionException("Unable to commit against JDBC Connection")))
                .isEmpty();
    }

    @Test
    void aRollbackThatFailsOnABrokenConnectionIsUnavailable() {
        // Observed in the phase-03 consumer run (silent Oracle, driver read timeout): Spring rethrows the
        // rollback failure in place of the 08006 read timeout, and Hikari's closed-connection exception
        // carries no SQLState. Nothing was committed, so the request may be retried.
        Throwable rollback = new JpaSystemException(new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLException("Connection is closed")));
        assertThat(DataAccessFailure.classify(rollback)).contains(DataAccessFailure.UNAVAILABLE);
        // Hibernate uses the same message for any SQLException from rollback: other causes classify as usual.
        assertThat(DataAccessFailure.classify(new JpaSystemException(new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLException("I/O error", "08006")))))
                .contains(DataAccessFailure.UNAVAILABLE);
        assertThat(DataAccessFailure.classify(new JpaSystemException(new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLException("ORA-00600 internal error", "HY000")))))
                .isEmpty();
    }

    @Test
    void aFailedCommitIsNeverRetryableWhateverItsCause() {
        // The commit's outcome is unknown: a retry could apply the change twice, so even a lost-connection cause
        // stays unclassified (the generic, non-retryable 500).
        for (SQLException cause : new SQLException[] {new SQLException("Connection is closed"),
                new SQLRecoverableException("ORA-18730 Socket read timed out", "08006"),
                new SQLException("I/O error", "08006")}) {
            assertThat(DataAccessFailure.classify(new JpaSystemException(
                    new TransactionException("Unable to commit against JDBC Connection", cause)))).isEmpty();
        }
    }

    @Test
    void aCommitThatLosesItsConnectionThroughSpringsTranslationIsNotRetryable() {
        // Spring's translator turns Hibernate's commit failure over a recoverable SQLException into a
        // RecoverableDataAccessException without the Hibernate exception: the veto has to come from where it was
        // raised, the transaction manager's commit.
        Supplier<RuntimeException> lostOnCommit = () -> translate(new TransactionException(
                "Unable to commit against JDBC Connection", new SQLRecoverableException("ORA-17008: Closed connection",
                "08006")));
        RuntimeException translated = lostOnCommit.get();
        assertThat(translated).isNotInstanceOf(JpaSystemException.class);
        assertThat(DataAccessFailure.classify(translated)).contains(DataAccessFailure.UNAVAILABLE);

        // JpaTransactionManager translates inside doCommit, as the supplier does here.
        assertThat(DataAccessFailure.classify(failingCommit(lostOnCommit))).isEmpty();
    }

    @Test
    void whatTheCommitPhaseMayStillClassify() {
        // A deadline that passed before the commit (nothing committed) stays a 503, and a conflict found by the
        // commit-time flush stays a 409.
        assertThat(DataAccessFailure.classify(failingCommit(
                () -> new JpaSystemException(new TransactionException("Transaction timeout expired")))))
                .contains(DataAccessFailure.UNAVAILABLE);
        assertThat(DataAccessFailure.classify(failingCommit(() -> new DataIntegrityViolationException("duplicate key"))))
                .contains(DataAccessFailure.INTEGRITY_CONFLICT);
        // The same lost connection on a rollback is retryable: nothing was committed.
        assertThat(DataAccessFailure.classify(failingRollback(() -> translate(new TransactionException(
                "Unable to rollback against JDBC Connection", new SQLRecoverableException("ORA-17008: Closed connection",
                "08006"))))))
                .contains(DataAccessFailure.UNAVAILABLE);
    }

    private static RuntimeException translate(RuntimeException hibernate) {
        RuntimeException translated = new HibernateJpaDialect().translateExceptionIfPossible(hibernate);
        return translated != null ? translated : hibernate;
    }

    /** Raises {@code failure} from inside a real transaction manager's commit. */
    private static Throwable failingCommit(Supplier<RuntimeException> failure) {
        FailingTransactionManager manager = new FailingTransactionManager(failure, null);
        try {
            manager.commit(manager.getTransaction(new DefaultTransactionDefinition()));
        } catch (RuntimeException thrown) {
            return thrown;
        }
        throw new AssertionError("commit did not fail");
    }

    /** Raises {@code failure} from inside a real transaction manager's rollback. */
    private static Throwable failingRollback(Supplier<RuntimeException> failure) {
        FailingTransactionManager manager = new FailingTransactionManager(null, failure);
        try {
            manager.rollback(manager.getTransaction(new DefaultTransactionDefinition()));
        } catch (RuntimeException thrown) {
            return thrown;
        }
        throw new AssertionError("rollback did not fail");
    }

    private static final class FailingTransactionManager extends AbstractPlatformTransactionManager {

        private final Supplier<RuntimeException> onCommit;
        private final Supplier<RuntimeException> onRollback;

        FailingTransactionManager(Supplier<RuntimeException> onCommit, Supplier<RuntimeException> onRollback) {
            this.onCommit = onCommit;
            this.onRollback = onRollback;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            throw onCommit.get();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // Spring also rolls back after a failed commit; only a rollback test makes that fail too.
            if (onRollback != null) {
                throw onRollback.get();
            }
        }
    }

    @Test
    void hibernatesNullabilityCheckIsAnIntegrityConflictButATransientReferenceIsNot() {
        assertThat(DataAccessFailure.classify(new PropertyValueException(
                "not-null property references a null or transient value", "OracleWidget", "referenceCode")))
                .contains(DataAccessFailure.INTEGRITY_CONFLICT);
        // An unsaved reference is a programming error, not a conflict with stored data.
        assertThat(DataAccessFailure.classify(new TransientPropertyValueException(
                "unsaved transient instance", "Part", "OracleWidget", "part")))
                .isEmpty();
    }

    @Test
    void theOutermostClassificationWins() {
        Throwable chain = new DataIntegrityViolationException("conflict",
                new SQLTransientConnectionException("unrelated inner cause"));
        assertThat(DataAccessFailure.classify(chain)).contains(DataAccessFailure.INTEGRITY_CONFLICT);
    }

    @Test
    void unrelatedFailuresAndSqlSyntaxErrorsAreNotClassified() {
        assertThat(DataAccessFailure.classify(new IllegalStateException("boom"))).isEmpty();
        assertThat(DataAccessFailure.classify(new SQLException("ORA-00942: table or view does not exist", "42000")))
                .isEmpty();
        assertThat(DataAccessFailure.classify(null)).isEmpty();
    }

    @Test
    void aCauseCycleTerminates() {
        RuntimeException cyclic = new RuntimeException("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(DataAccessFailure.classify(cyclic)).isEmpty();
    }

    @Test
    void codesAndDetailsNameNoInternals() {
        assertThat(DataAccessFailure.INTEGRITY_CONFLICT.status()).isEqualTo(409);
        assertThat(DataAccessFailure.INTEGRITY_CONFLICT.code().value()).isEqualTo("DC-DATA-0409");
        assertThat(DataAccessFailure.CONCURRENT_UPDATE.status()).isEqualTo(409);
        assertThat(DataAccessFailure.CONCURRENT_UPDATE.code().value()).isEqualTo("DC-DATA-0410");
        assertThat(DataAccessFailure.UNAVAILABLE.status()).isEqualTo(503);
        assertThat(DataAccessFailure.UNAVAILABLE.code().value()).isEqualTo("DC-DATA-0503");
        for (DataAccessFailure failure : DataAccessFailure.values()) {
            assertThat(failure.detail()).doesNotContainIgnoringCase("sql").doesNotContain("ORA-");
        }
    }
}
