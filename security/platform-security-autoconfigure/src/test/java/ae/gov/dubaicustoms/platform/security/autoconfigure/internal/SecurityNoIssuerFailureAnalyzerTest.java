package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class SecurityNoIssuerFailureAnalyzerTest {

    private final SecurityNoIssuerFailureAnalyzer analyzer = new SecurityNoIssuerFailureAnalyzer();

    @Test
    void namesTheIssuerPropertyAndKillSwitch() {
        FailureAnalysis analysis = analyzer.analyze(new NoSuchBeanDefinitionException(JwtDecoder.class));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getAction())
                .contains("issuer-uri")
                .contains("dc.platform.security.mode=disabled");
    }

    @Test
    void staysSilentForUnrelatedBeans() {
        assertThat(analyzer.analyze(new NoSuchBeanDefinitionException(String.class))).isNull();
    }
}
