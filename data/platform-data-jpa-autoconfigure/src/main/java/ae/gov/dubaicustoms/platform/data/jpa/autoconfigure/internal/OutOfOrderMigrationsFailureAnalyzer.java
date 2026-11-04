package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// Turns the out-of-order guard's startup failure into a Boot diagnostic whose Action names the fix
// (D112). Registered in META-INF/spring.factories.
public class OutOfOrderMigrationsFailureAnalyzer extends AbstractFailureAnalyzer<OutOfOrderMigrationsException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, OutOfOrderMigrationsException cause) {
        String action = "Remove spring.flyway.out-of-order=true (and any flyway.outOfOrder setting) for this "
                + "environment and give the late migration a version above the latest applied one. Out-of-order "
                + "runs are allowed only under the 'local' profile. See docs/modules/data.md#migrations.";
        return new FailureAnalysis(cause.getMessage(), action, cause);
    }
}
