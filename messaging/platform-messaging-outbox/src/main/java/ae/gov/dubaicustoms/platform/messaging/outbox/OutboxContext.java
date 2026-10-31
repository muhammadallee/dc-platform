package ae.gov.dubaicustoms.platform.messaging.outbox;

import java.util.Map;

/**
 * What the outbox reads from the caller's context when writing a row, and restores from it when sending. An SPI
 * of this module so it stays free of tracing and security types; the auto-configuration supplies the real one.
 *
 * <p>Implementations are thread-safe.
 *
 * @since 1.0.0
 */
public interface OutboxContext {

    /** Stores nothing and restores nothing. */
    OutboxContext NONE = new OutboxContext() {
        @Override
        public Map<String, String> traceHeaders() {
            return Map.of();
        }

        @Override
        public String principal() {
            return null;
        }

        @Override
        public Scope restore(Map<String, String> headers) {
            return () -> { };
        }
    };

    /**
     * The current trace, as W3C headers ({@code traceparent}, {@code tracestate}).
     *
     * @return the headers; empty without a current trace; never {@code null}
     */
    Map<String, String> traceHeaders();

    /**
     * The current principal's subject id, never a token.
     *
     * @return the subject, or {@code null} if there is none
     */
    String principal();

    /**
     * Restores the stored trace on the current thread while a row is sent, so log lines carry it.
     *
     * @param headers the row's stored headers; never {@code null}
     * @return closes the restored context; never {@code null}
     */
    Scope restore(Map<String, String> headers);

    /** Ends a {@linkplain #restore restored} context. */
    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
