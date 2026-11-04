package ae.gov.dubaicustoms.platform.messaging.autoconfigure.internal;

import java.util.Map;

/**
 * Carries a trace across a message. No tracing type appears here: that jar is optional. Implementations are thread-safe.
 */
public interface MessageTrace {

    /** Carries nothing. */
    MessageTrace NONE = new MessageTrace() {
        @Override
        public Map<String, String> currentHeaders() {
            return Map.of();
        }

        @Override
        public Scope continueFrom(Map<String, String> headers) {
            return () -> { };
        }

        @Override
        public Runnable carry(Runnable action) {
            return action;
        }
    };

    /**
     * The current trace as W3C headers.
     *
     * @return only {@code traceparent} and {@code tracestate}; empty without a trace; never {@code null}
     */
    Map<String, String> currentHeaders();

    /**
     * Opens a consumer span continuing the trace in a message's headers, or a new one if they hold none.
     *
     * @param headers the message's headers; never {@code null}
     * @return the scope to close when handling ends; never {@code null}
     */
    Scope continueFrom(Map<String, String> headers);

    /**
     * Makes an action run on another thread under the caller's current trace.
     *
     * @param action the action; never {@code null}
     * @return the action to run there; never {@code null}
     */
    Runnable carry(Runnable action);

    /** An open span. */
    interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
