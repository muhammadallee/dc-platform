package ae.gov.dubaicustoms.platform.audit;

import org.apiguardian.api.API;

/**
 * Whether an audited action completed successfully or failed. The {@link Audited} aspect derives this
 * from the intercepted method: a normal return is {@link #SUCCESS}, a thrown exception is
 * {@link #FAILURE}.
 *
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public enum Outcome {

    /** The action completed normally. */
    SUCCESS,

    /** The action failed (the audited method threw). */
    FAILURE
}
