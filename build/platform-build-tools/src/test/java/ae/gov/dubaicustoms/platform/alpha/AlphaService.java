package ae.gov.dubaicustoms.platform.alpha;

import ae.gov.dubaicustoms.platform.alpha.internal.AlphaSecret;

// Fixture: using your OWN module's internals is legal (rule 1 happy path).
public final class AlphaService {
    private final AlphaSecret secret = new AlphaSecret();

    public String reveal() {
        return secret.value();
    }
}
