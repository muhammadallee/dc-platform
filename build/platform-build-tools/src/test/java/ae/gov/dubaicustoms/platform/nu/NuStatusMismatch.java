package ae.gov.dubaicustoms.platform.nu;

import static org.apiguardian.api.API.Status.STABLE;

import org.apiguardian.api.API;

/** Dirty fixture: java.lang.@Deprecated but @API status is STABLE, not DEPRECATED (phase-16 A.1 guard). */
@Deprecated
@API(status = STABLE, since = "0.1.0")
public interface NuStatusMismatch {
}
