package ae.gov.dubaicustoms.platform.eta;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Fixture: mutable @ConfigurationProperties class (rule 6 violation).
@ConfigurationProperties(prefix = "dc.platform.eta")
public class EtaSettings {
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
