package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

/**
 * What messaging records. No Micrometer type appears here: those jars are optional, and one in the signature of an
 * always-present bean breaks reflection without them. Implementations are thread-safe.
 */
public interface MessagingInstrumentation {

    /** Records nothing. */
    MessagingInstrumentation NOOP = new MessagingInstrumentation() {
        @Override
        public void count(String meter, String... tags) {
        }

        @Override
        public void observePublish(String destination, String eventType, Runnable publish) {
            publish.run();
        }
    };

    /**
     * Adds one to a counter.
     *
     * @param meter the meter name; never {@code null}
     * @param tags tag names and values in turn; never {@code null}
     */
    void count(String meter, String... tags);

    /**
     * Runs a publish under an observation.
     *
     * @param destination the destination; never {@code null}
     * @param eventType the event type; never {@code null}
     * @param publish the publish; never {@code null}
     */
    void observePublish(String destination, String eventType, Runnable publish);
}
