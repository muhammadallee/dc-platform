package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.authz.spi.PermissionEvaluatorProvider;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import java.util.Collection;
import java.util.Objects;

/**
 * Default {@link PermissionEvaluatorProvider}: grants a permission when it appears in the
 * configured claim (a collection or single string), or among the {@link CurrentUser}'s
 * {@link CurrentUser#roles()} (the token's granted authorities) as itself or as a role, {@code ROLE_x}.
 * A scope, {@code SCOPE_x}, is not a permission {@code x}.
 */
public final class RolesClaimPermissionProvider implements PermissionEvaluatorProvider {

    private final String rolesClaim;

    public RolesClaimPermissionProvider(String rolesClaim) {
        this.rolesClaim = Objects.requireNonNull(rolesClaim, "rolesClaim must not be null");
    }

    @Override
    public boolean hasPermission(CurrentUser user, String permission) {
        Object claim = user.claims().get(rolesClaim);
        boolean inClaim = claim instanceof Collection<?> values
                ? values.stream().anyMatch(permission::equals)
                : permission.equals(claim);
        return inClaim || user.roles().contains(permission) || user.roles().contains("ROLE_" + permission);
    }
}
