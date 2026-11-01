package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.core.CommitOutcomeUnknown;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A {@link JpaTransactionManager} that marks what the commit itself throws with {@link CommitOutcomeUnknown}. Only
 * {@code doCommit} is covered, so a failure in a {@code beforeCommit} callback is not marked: nothing was committed yet.
 * Thread-safe, as its parent is.
 */
public final class CommitEvidenceJpaTransactionManager extends JpaTransactionManager {

    private static final long serialVersionUID = 1L;

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        try {
            super.doCommit(status);
        } catch (RuntimeException failure) {
            failure.addSuppressed(new CommitOutcomeUnknown());
            throw failure;
        }
    }
}
