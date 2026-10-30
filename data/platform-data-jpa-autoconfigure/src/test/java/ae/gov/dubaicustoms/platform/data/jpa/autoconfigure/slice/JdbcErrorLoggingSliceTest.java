package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.slice;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.PlatformDataJpaAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

/**
 * The logging default reaches the logging system (it is applied after the environment post-processors run):
 * Hibernate's per-statement error lines stay off, so a failed statement is logged once, where it is handled
 * (HI-0069; the phase-03 consumer counted three WARN lines per constraint violation before).
 */
@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "dc.platform.data.jpa.require-migrations=false"
})
@ImportAutoConfiguration(PlatformDataJpaAutoConfiguration.class)
class JdbcErrorLoggingSliceTest {

    @Test
    void hibernatesStatementErrorLinesAreOffByDefault() {
        LoggingSystem logging = LoggingSystem.get(getClass().getClassLoader());

        assertThat(logging.getLoggerConfiguration("org.hibernate.orm.jdbc.error").getEffectiveLevel())
                .isEqualTo(LogLevel.ERROR);
    }
}
