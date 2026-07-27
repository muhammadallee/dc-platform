package ae.gov.dubaicustoms.platform.mu;

import static org.apiguardian.api.API.Status.DEPRECATED;

import org.apiguardian.api.API;

/** Dirty fixture: @API(status = DEPRECATED) without java.lang.@Deprecated (phase-16 A.1 guard). */
@API(status = DEPRECATED, since = "0.1.0")
public interface MuMissingDeprecated {
}
