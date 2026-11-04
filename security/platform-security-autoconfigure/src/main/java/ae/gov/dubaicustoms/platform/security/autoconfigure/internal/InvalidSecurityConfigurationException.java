package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

/** A security setup the platform refuses to start with; its message says what to change. */
public final class InvalidSecurityConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String action;

    /**
     * Creates the failure.
     *
     * @param message what is wrong
     * @param action what to change
     */
    public InvalidSecurityConfigurationException(String message, String action) {
        super(message);
        this.action = action;
    }

    /**
     * Returns what to change.
     *
     * @return the action; never {@code null}
     */
    public String action() {
        return action;
    }
}
