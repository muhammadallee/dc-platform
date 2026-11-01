package ae.gov.dubaicustoms.platform.core;

import org.apiguardian.api.API;

/**
 * Evidence, attached as a suppressed exception, that a failure came out of the database commit itself, so nobody knows
 * whether the transaction committed. A retry could apply the change twice, so the failure must not be reported as
 * retryable.
 *
 * <p>Usage: a transaction manager adds it to whatever its commit throws, with
 * {@code failure.addSuppressed(new CommitOutcomeUnknown())}; a classifier looks for it in the failure's cause chain.
 * It is never thrown by itself. It has no stack trace and no message, so it adds nothing to a log.
 *
 * <p>Immutable and thread-safe. No member returns {@code null}, except the inherited {@code getMessage()}.
 *
 * @since 1.0.0
 */
@API(status = API.Status.STABLE, since = "1.0.0")
public final class CommitOutcomeUnknown extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the evidence. */
    public CommitOutcomeUnknown() {
        super(null, null, false, false);
    }
}
