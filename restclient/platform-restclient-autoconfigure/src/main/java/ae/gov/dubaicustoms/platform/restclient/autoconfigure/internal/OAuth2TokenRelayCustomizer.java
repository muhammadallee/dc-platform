package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

/**
 * Relays the current request's bearer token to outbound calls. Registered only when the security
 * capability's {@code CurrentUserAccessor} bean is actually present (see
 * {@code PlatformRestClientAutoConfiguration}'s guarded configuration).
 */
public final class OAuth2TokenRelayCustomizer implements PlatformRestClientCustomizer {

    @Override
    public void customize(String name, RestClient.Builder builder) {
        builder.requestInterceptor((request, body, execution) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                request.getHeaders().setBearerAuth(jwt.getTokenValue());
            }
            return execution.execute(request, body);
        });
    }
}
