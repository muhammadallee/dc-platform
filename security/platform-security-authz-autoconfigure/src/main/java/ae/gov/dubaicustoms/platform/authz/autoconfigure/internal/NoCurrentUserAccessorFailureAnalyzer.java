package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Says how to resolve authz being on with nothing to read the current user from. */
public class NoCurrentUserAccessorFailureAnalyzer extends AbstractFailureAnalyzer<NoCurrentUserAccessorException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoCurrentUserAccessorException cause) {
        return new FailureAnalysis(cause.getMessage(),
                "Add platform-starter-security, supply your own CurrentUserAccessor bean, or set dc.platform.authz.enabled=false "
                        + "if nothing in this application uses @RequiresPermission.", cause);
    }
}
