package ae.gov.dubaicustoms.platform.theta;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Fixture: immutable record @ConfigurationProperties (rule 6 happy path).
@ConfigurationProperties(prefix = "dc.platform.theta")
public record ThetaProperties(String name) {
}
