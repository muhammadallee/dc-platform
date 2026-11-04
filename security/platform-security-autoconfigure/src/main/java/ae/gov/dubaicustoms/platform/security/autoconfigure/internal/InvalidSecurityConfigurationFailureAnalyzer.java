package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a refused security setup into a startup report naming what to change. */
public class InvalidSecurityConfigurationFailureAnalyzer extends AbstractFailureAnalyzer<InvalidSecurityConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidSecurityConfigurationException cause) {
        return new FailureAnalysis(cause.getMessage(), cause.action(), cause);
    }
}
