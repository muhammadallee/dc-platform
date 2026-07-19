package ae.gov.dubaicustoms.platform.omega;

import org.springframework.http.ProblemDetail;

// Fixture: ProblemDetail is whitelisted for the errors capability ONLY - omega must be rejected.
public final class OmegaProblemLeak {
    public ProblemDetail leak() {
        return ProblemDetail.forStatus(500);
    }
}
