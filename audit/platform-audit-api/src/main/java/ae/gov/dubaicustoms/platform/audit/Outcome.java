package ae.gov.dubaicustoms.platform.audit;

/**
 * Whether an audited action completed successfully or failed. The {@link Audited} aspect derives this
 * from the intercepted method: a normal return is {@link #SUCCESS}, a thrown exception is
 * {@link #FAILURE}.
 *
 * @since 0.2.0
 */
public enum Outcome {

    /** The action completed normally. */
    SUCCESS,

    /** The action failed (the audited method threw). */
    FAILURE
}
