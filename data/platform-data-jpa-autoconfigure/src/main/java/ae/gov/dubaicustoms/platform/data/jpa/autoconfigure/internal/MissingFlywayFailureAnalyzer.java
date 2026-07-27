package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// Turns the "added JPA, forgot migrations" startup failure into a Boot diagnostic whose Action names
// the exact fix (phase-16 A.2). Registered in META-INF/spring.factories.
public class MissingFlywayFailureAnalyzer extends AbstractFailureAnalyzer<MissingFlywayException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingFlywayException cause) {
        String action = "Add platform-starter-data-jpa (it bundles flyway-core) and put your migrations under "
                + "classpath:db/migration, or set dc.platform.data.jpa.require-migrations=false to opt out. "
                + "See docs/modules/data.md#migrations.";
        return new FailureAnalysis(cause.getMessage(), action, cause);
    }
}
