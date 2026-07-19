package ae.gov.dubaicustoms.platform.beta;

import ae.gov.dubaicustoms.platform.alpha.internal.AlphaSecret;

// Fixture: reaches into ANOTHER module's internals (rule 1 violation).
public final class BetaLeak {
    private final AlphaSecret stolen = new AlphaSecret();

    public String steal() {
        return stolen.value();
    }
}
