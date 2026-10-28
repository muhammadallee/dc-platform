package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import org.hibernate.StaleObjectStateException;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.JDBCConnectionException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

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
