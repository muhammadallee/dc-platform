package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.oauth2.jwt.JwtDecoder;

// The platform resource-server chain wires oauth2ResourceServer().jwt(), which needs a JwtDecoder.
// Boot only contributes one when a JWT issuer is configured, so a service that enabled security but
// set no issuer fails at startup with a bare NoSuchBeanDefinitionException(JwtDecoder). This analyzer
// (phase-16 A.2) turns that into an Action naming the issuer property and the kill switch. Highest
// precedence so it wins over Boot's generic analyzer for the JwtDecoder type only.
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityNoIssuerFailureAnalyzer extends AbstractFailureAnalyzer<NoSuchBeanDefinitionException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, NoSuchBeanDefinitionException cause) {
        Class<?> missing = cause.getBeanType();
        if (missing == null || !JwtDecoder.class.isAssignableFrom(missing)) {
            return null; // not the missing-issuer case — let Boot's generic analyzer handle it
        }
        String description = "The platform security resource-server chain requires a JwtDecoder, but none could be "
                + "created because no JWT issuer is configured.";
        String action = "Set spring.security.oauth2.resourceserver.jwt.issuer-uri (or .jwk-set-uri) to your "
                + "identity provider, or set dc.platform.security.mode=disabled to turn the platform security "
                + "chain off. See docs/modules/security.md.";
        return new FailureAnalysis(description, action, cause);
    }
}
