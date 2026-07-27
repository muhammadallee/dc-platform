package ae.gov.dubaicustoms.example.goldenpath;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Startup-budget guard (phase-15 deliverable C): boots the full application context and fails if the
 * cold-start wall-clock exceeds the checked-in baseline by more than 15%. The baseline lives in
 * {@code startup-baseline.properties} and is governed by comment, not by silently editing this test.
 *
 * <p>Runs with {@link WebApplicationType#NONE} so the measurement is the platform/bean wiring cost,
 * not Tomcat port binding, and sets the placeholder JWKS URI so security auto-configuration boots
 * offline exactly as it does in the other tests.
 */
class StartupBudgetTest {

    private static final double TOLERANCE = 1.15;

    @Test
    void contextStartsWithinBudget() throws IOException {
        long baselineMs = readBaselineMs();

        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        long start = System.nanoTime();
        try (ConfigurableApplicationContext context = application.run(
                "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.test/jwks.json")) {
            assertThat(context.isRunning()).isTrue();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long budgetMs = Math.round(baselineMs * TOLERANCE);
        assertThat(elapsedMs)
                .as("cold context startup %d ms must stay within baseline %d ms + 15%% (= %d ms); "
                        + "if the service legitimately got slower, raise startup.baseline.ms deliberately",
                        elapsedMs, baselineMs, budgetMs)
                .isLessThanOrEqualTo(budgetMs);
    }

    private static long readBaselineMs() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = StartupBudgetTest.class.getResourceAsStream("/startup-baseline.properties")) {
            assertThat(in).as("startup-baseline.properties must be on the test classpath").isNotNull();
            properties.load(in);
        }
        return Long.parseLong(properties.getProperty("startup.baseline.ms"));
    }
}
