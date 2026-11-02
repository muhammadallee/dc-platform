package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Maps a token to authorities: {@code SCOPE_x} from {@code scope} or {@code scp}, as Spring does, and {@code ROLE_x} from
 * {@code roles}, Keycloak's {@code realm_access.roles}, and {@code resource_access.<client>.roles} of this service's own client
 * only: a role another client gave the user means nothing here. A claim of an unexpected shape grants nothing.
 */
public final class PlatformJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES = "roles";

    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    private final String resourceClient;

    /**
     * Creates the converter.
     *
     * @param resourceClient this service's entry in {@code resource_access}; may be {@code null} or blank for none
     */
    public PlatformJwtAuthoritiesConverter(String resourceClient) {
        this.resourceClient = resourceClient;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        Collection<GrantedAuthority> scoped = scopes.convert(jwt);
        if (scoped != null) {
            authorities.addAll(scoped);
        }
        addRoles(authorities, jwt.getClaims().get(ROLES));
        addRoles(authorities, nested(jwt.getClaims().get("realm_access"), ROLES));
        if (resourceClient != null && !resourceClient.isBlank()) {
            addRoles(authorities, nested(nested(jwt.getClaims().get("resource_access"), resourceClient), ROLES));
        }
        return List.copyOf(authorities);
    }

    private static Object nested(Object parent, String key) {
        return parent instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static void addRoles(Set<GrantedAuthority> authorities, Object claim) {
        if (claim instanceof String role) {
            add(authorities, role);
        } else if (claim instanceof Collection<?> roles) {
            for (Object role : roles) {
                if (role instanceof String text) {
                    add(authorities, text);
                }
            }
        }
    }

    private static void add(Set<GrantedAuthority> authorities, String role) {
        if (!role.isBlank()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        }
    }
}
