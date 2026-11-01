package ae.gov.dubaicustoms.platform.messaging.outbox.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.messaging.EventEnvelope;
import ae.gov.dubaicustoms.platform.messaging.EventPublisher;
import ae.gov.dubaicustoms.platform.messaging.OutboxPublisher;
import java.util.Objects;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * What {@link OutboxPublisher} does with {@code dc.platform.messaging.outbox.enabled=false}: publishes after commit, so
 * application code keeps working but a crash between the commit and the send loses the event. The startup WARN says so.
 * Immediately when no transaction is active, and directly inside an after-commit callback. Refuses where a publish could
 * go out too early or never: an after-completion callback, and a transaction in an after-commit callback that cannot be
 * proven new. Thread-safe.
 */
public final class AfterCommitOutboxPublisher implements OutboxPublisher {

    private final EventPublisher publisher;

    public AfterCommitOutboxPublisher(EventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    }

    @Override
    public void publish(String destination, EventEnvelope<?> event) {
        // The phase comes first: Spring clears the synchronizations before after-completion callbacks, so that phase
        // looks like no transaction at all, and a publish there would go out even after a rollback.
        switch (currentPhase()) {
            case AFTER_COMMIT -> publisher.publish(destination, event);
            case UNSAFE -> throw new IllegalStateException("A publish here could go out too early or never: this is under "
                    + "an after-completion callback, or a transaction in an after-commit callback that may have joined "
                    + "the finished one. Publish directly in the after-commit callback, or from a @Transactional "
                    + "REQUIRES_NEW method");
            case NORMAL -> {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    publisher.publish(destination, event);
                    return;
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        publisher.publish(destination, event);
                    }
                });
            }
        }
    }

    @Override
    public boolean canPublish() {
        return true;
    }

    // Spring offers no flag for this phase, so the stack decides. A transaction opened in the callback is proven new only
    // by the interceptor's status: a joined one has already finished, and whatever it registers never runs.
    private static Phase currentPhase() {
        return StackWalker.getInstance().walk(frames -> {
            Phase nested = null;
            for (var it = frames.iterator(); it.hasNext(); ) {
                var frame = it.next();
                String type = frame.getClassName();
                String method = frame.getMethodName();
                if ("org.springframework.transaction.support.TransactionSynchronizationUtils".equals(type)) {
                    if (method.equals("invokeAfterCommit") || method.equals("invokeAfterCompletion")) {
                        if (nested != null) {
                            return nested;
                        }
                        return method.equals("invokeAfterCommit") ? Phase.AFTER_COMMIT : Phase.UNSAFE;
                    }
                } else if (nested == null && "org.springframework.transaction.support.TransactionTemplate".equals(type)
                        && "execute".equals(method)) {
                    nested = Phase.UNSAFE;
                } else if (nested == null
                        && "org.springframework.transaction.interceptor.TransactionAspectSupport".equals(type)
                        && "invokeWithinTransaction".equals(method)) {
                    nested = TransactionAspectSupport.currentTransactionStatus().isNewTransaction()
                            ? Phase.NORMAL : Phase.UNSAFE;
                }
            }
            return Phase.NORMAL;
        });
    }

    private enum Phase { NORMAL, AFTER_COMMIT, UNSAFE }
}
