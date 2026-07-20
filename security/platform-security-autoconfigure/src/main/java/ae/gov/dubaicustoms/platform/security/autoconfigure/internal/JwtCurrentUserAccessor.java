package ae.gov.dubaicustoms.platform.security.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.Optional;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** {@link CurrentUserAccessor} reading the {@link Jwt} principal off the security context. */
public final class JwtCurrentUserAccessor implements CurrentUserAccessor {

    private static final String TENANT_CLAIM = "tenant";

    @Override
    public Optional<CurrentUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Object tenantClaim = jwt.getClaims().get(TENANT_CLAIM);
        String tenant = tenantClaim == null ? null : tenantClaim.toString();
        return Optional.of(new CurrentUser(jwt.getSubject(), tenant, roles, jwt.getClaims()));
    }
}
