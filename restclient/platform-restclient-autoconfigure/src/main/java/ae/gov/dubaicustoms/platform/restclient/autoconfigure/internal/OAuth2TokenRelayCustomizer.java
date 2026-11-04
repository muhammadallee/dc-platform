package ae.gov.dubaicustoms.platform.restclient.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.restclient.PlatformRestClientCustomizer;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.client.RestClient;

/**
 * Relays the current request's bearer token to the outbound calls of the named clients, and no others, since whoever receives a
 * token can replay it. Registered only when the security
 * capability's {@code CurrentUserAccessor} bean is actually present (see
 * {@code PlatformRestClientAutoConfiguration}'s guarded configuration).
 */
public final class OAuth2TokenRelayCustomizer implements PlatformRestClientCustomizer {

    private final Set<String> clients;

    /**
     * Creates the customizer.
     *
     * @param clients the client names that carry the token
     */
    public OAuth2TokenRelayCustomizer(List<String> clients) {
        this.clients = Set.copyOf(clients);
    }

    @Override
    public void customize(String name, RestClient.Builder builder) {
        if (!clients.contains(name)) {
            return;
        }
        builder.requestInterceptor((request, body, execution) -> {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                request.getHeaders().setBearerAuth(jwt.getTokenValue());
            }
            return execution.execute(request, body);
        });
    }
}
