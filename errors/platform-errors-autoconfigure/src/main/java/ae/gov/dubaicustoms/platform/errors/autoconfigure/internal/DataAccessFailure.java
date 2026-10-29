package ae.gov.dubaicustoms.platform.errors.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Classifies a data-access failure by walking its cause chain (D111). Spring translates exceptions
 * only behind a {@code @Repository} proxy or at commit; a service using the {@code EntityManager}
 * directly lets raw Hibernate and JPA types through, and a batched insert reports a
 * {@code BatchUpdateException} whose SQLState is the only sign of a constraint violation (all observed
 * on Oracle, {@code OracleExceptionPathsIT}). So types are matched by class name anywhere in the chain
 * and in each class's superclass line — no compile-time dependency on Spring Data, JPA or Hibernate —
 * then by SQLState. The outermost match wins.
 *
 * <p>Response bodies use a fixed {@code detail}: the exception messages carry SQL, constraint and
 * table names. Stateless and thread-safe.
 */
public enum DataAccessFailure {

    /** Unique, foreign-key, NOT NULL or check violation: the request conflicts with stored data. */
    INTEGRITY_CONFLICT(409, Codes.INTEGRITY_CONFLICT, "The request conflicts with existing data."),

    /** Optimistic or pessimistic locking failure: another request changed or holds the data. */
    CONCURRENT_UPDATE(409, Codes.CONCURRENT_UPDATE,
            "The data was changed or is locked by another request; reload it and try again."),

    /** No connection within the pool timeout, statement or transaction timeout, lost connection. */
    UNAVAILABLE(503, Codes.UNAVAILABLE, "The database is temporarily unavailable.");

    private static final Set<String> INTEGRITY_TYPES = Set.of(
            "org.springframework.dao.DataIntegrityViolationException",
            "org.hibernate.exception.ConstraintViolationException",
            // Hibernate's own nullability check, before any SQL (on when Bean Validation is absent).
            "org.hibernate.PropertyValueException",
            "java.sql.SQLIntegrityConstraintViolationException");

    private static final Set<String> CONCURRENCY_TYPES = Set.of(
            "org.springframework.dao.ConcurrencyFailureException",
            "jakarta.persistence.OptimisticLockException",
            "jakarta.persistence.PessimisticLockException",
            "jakarta.persistence.LockTimeoutException",
            "org.hibernate.StaleStateException",
            "org.hibernate.exception.LockAcquisitionException",
            "org.hibernate.exception.LockTimeoutException",
            "java.sql.SQLTransactionRollbackException");

    private static final Set<String> UNAVAILABLE_TYPES = Set.of(
            "org.springframework.dao.QueryTimeoutException",
            "org.springframework.dao.DataAccessResourceFailureException",
            "org.springframework.dao.TransientDataAccessResourceException",
            "org.springframework.transaction.CannotCreateTransactionException",
            "org.springframework.transaction.TransactionTimedOutException",
            "jakarta.persistence.QueryTimeoutException",
            "org.hibernate.exception.JDBCConnectionException",
            "java.sql.SQLTimeoutException",
            "java.sql.SQLTransientConnectionException",
            "java.sql.SQLNonTransientConnectionException",
            "java.sql.SQLRecoverableException");

    // Hibernate 7.4 JdbcCoordinatorImpl: a statement executed after the transaction deadline passed (flush, commit).
    // Only this message: any other org.hibernate.TransactionException is not an availability problem.
    private static final String HIBERNATE_TRANSACTION_EXCEPTION = "org.hibernate.TransactionException";
    private static final String HIBERNATE_TIMEOUT_EXPIRED = "Transaction timeout expired";

    private static final int MAX_DEPTH = 32;

    // Static constants (not enum fields) so the error-code registry gate sees them; a nested holder
    // because an enum constructor cannot read the enum's own static fields.
    private static final class Codes {
        static final ErrorCode INTEGRITY_CONFLICT = new ErrorCode("DC-DATA-0409");
        static final ErrorCode CONCURRENT_UPDATE = new ErrorCode("DC-DATA-0410");
        static final ErrorCode UNAVAILABLE = new ErrorCode("DC-DATA-0503");
    }

    private final int status;
    private final ErrorCode code;
    private final String detail;

    DataAccessFailure(int status, ErrorCode code, String detail) {
        this.status = status;
        this.code = code;
        this.detail = detail;
    }

    /** @return the HTTP status of the problem response */
    public int status() {
        return status;
    }

    /** @return the stable error code */
    public ErrorCode code() {
        return code;
    }

    /** @return the fixed, internals-free problem detail */
    public String detail() {
        return detail;
    }

    /**
     * Classifies {@code exception}, or returns empty when nothing in its cause chain is a known
     * data-access failure (the caller keeps its own handling, e.g. the generic 500).
     *
     * @param exception the failure; may be {@code null}
     * @return the classification, if any
     */
    public static Optional<DataAccessFailure> classify(Throwable exception) {
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        int depth = 0;
        for (Throwable t = exception; t != null && depth < MAX_DEPTH && seen.put(t, Boolean.TRUE) == null;
                t = t.getCause(), depth++) {
            Optional<DataAccessFailure> byType = byType(t.getClass());
            if (byType.isPresent()) {
                return byType;
            }
            if (isHibernateTransactionExpiry(t)) {
                return Optional.of(UNAVAILABLE);
            }
            if (t instanceof SQLException sql) {
                Optional<DataAccessFailure> byState = bySqlState(sql.getSQLState());
                if (byState.isPresent()) {
                    return byState;
                }
            }
        }
        return Optional.empty();
    }

    private static boolean isHibernateTransactionExpiry(Throwable t) {
        return t.getClass().getName().equals(HIBERNATE_TRANSACTION_EXCEPTION)
                && HIBERNATE_TIMEOUT_EXPIRED.equals(t.getMessage());
    }

    private static Optional<DataAccessFailure> byType(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (INTEGRITY_TYPES.contains(name)) {
                return Optional.of(INTEGRITY_CONFLICT);
            }
            if (CONCURRENCY_TYPES.contains(name)) {
                return Optional.of(CONCURRENT_UPDATE);
            }
            if (UNAVAILABLE_TYPES.contains(name)) {
                return Optional.of(UNAVAILABLE);
            }
        }
        return Optional.empty();
    }

    // SQL:2016 SQLState classes: 23 integrity constraint violation, 40 transaction rollback
    // (serialization failure, deadlock), 08 connection exception.
    private static Optional<DataAccessFailure> bySqlState(String state) {
        if (state == null || state.length() < 2) {
            return Optional.empty();
        }
        return switch (state.substring(0, 2)) {
            case "23" -> Optional.of(INTEGRITY_CONFLICT);
            case "40" -> Optional.of(CONCURRENT_UPDATE);
            case "08" -> Optional.of(UNAVAILABLE);
            default -> Optional.empty();
        };
    }
}
