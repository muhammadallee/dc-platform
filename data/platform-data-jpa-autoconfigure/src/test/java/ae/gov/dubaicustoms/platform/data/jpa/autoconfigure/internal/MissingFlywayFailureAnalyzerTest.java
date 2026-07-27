package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

class MissingFlywayFailureAnalyzerTest {

    private final MissingFlywayFailureAnalyzer analyzer = new MissingFlywayFailureAnalyzer();

    @Test
    void actionNamesTheStarterAndOptOut() {
        FailureAnalysis analysis = analyzer.analyze(new MissingFlywayException("JPA is configured but Flyway is absent"));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("Flyway is absent");
        assertThat(analysis.getAction())
                .contains("platform-starter-data-jpa")
                .contains("dc.platform.data.jpa.require-migrations=false")
                .contains("db/migration");
    }
}
