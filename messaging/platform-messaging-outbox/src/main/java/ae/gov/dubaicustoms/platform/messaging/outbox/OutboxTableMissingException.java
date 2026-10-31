package ae.gov.dubaicustoms.platform.messaging.outbox;

/**
 * The outbox table is missing or not readable when the drainer starts. A service that publishes to a table nobody can
 * read loses its events at the first request, so startup fails instead.
 *
 * @since 1.0.0
 */
public final class OutboxTableMissingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param cause the database's refusal
     */
    public OutboxTableMissingException(Throwable cause) {
        super("The platform_outbox table is missing or not readable", cause);
    }
}
