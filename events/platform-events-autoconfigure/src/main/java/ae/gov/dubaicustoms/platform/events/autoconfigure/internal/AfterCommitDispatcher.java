package ae.gov.dubaicustoms.platform.events.autoconfigure.internal;

/**
 * Strategy for when a domain-event side effect actually runs: after the enclosing transaction
 * commits, or immediately when no transaction is active.
 */
public interface AfterCommitDispatcher {

    /**
     * Runs {@code action} after the enclosing transaction commits, or immediately if none is
     * active.
     *
     * @param action the side effect to run; never {@code null}
     */
    void dispatch(Runnable action);
}
