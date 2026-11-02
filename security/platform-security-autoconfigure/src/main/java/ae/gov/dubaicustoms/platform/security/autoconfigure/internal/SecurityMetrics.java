package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

/** The security capability's counters. Tags carry nothing from a token or a user. */
public interface SecurityMetrics {

    /** Counts nothing, for an application without a meter registry. */
    SecurityMetrics NONE = new SecurityMetrics() {
        @Override
        public void tokenRejected() {
        }

        @Override
        public void jwksFetch(boolean success) {
        }
    };

    /** A presented bearer token was refused. */
    void tokenRejected();

    /**
     * The signing keys were fetched.
     *
     * @param success whether the fetch returned a response
     */
    void jwksFetch(boolean success);
}
