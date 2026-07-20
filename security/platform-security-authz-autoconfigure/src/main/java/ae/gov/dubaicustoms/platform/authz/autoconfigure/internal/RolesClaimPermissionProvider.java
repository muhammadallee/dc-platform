package ae.gov.dubaicustoms.platform.authz.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.authz.spi.PermissionEvaluatorProvider;
import ae.gov.dubaicustoms.platform.security.CurrentUser;
import java.util.Collection;
import java.util.Objects;

/**
 * Default {@link PermissionEvaluatorProvider}: grants a permission when it appears in the
 * configured claim (a collection or single string), falling back to the {@link CurrentUser}'s
 * {@link CurrentUser#roles()} (populated from the token's granted authorities) when the claim is
 * absent or not present in that shape.
 */
public final class RolesClaimPermissionProvider implements PermissionEvaluatorProvider {

    private final String rolesClaim;

    public RolesClaimPermissionProvider(String rolesClaim) {
        this.rolesClaim = Objects.requireNonNull(rolesClaim, "rolesClaim must not be null");
    }

    @Override
    public boolean hasPermission(CurrentUser user, String permission) {
        Object claim = user.claims().get(rolesClaim);
        if (claim instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).anyMatch(permission::equals);
        }
        if (claim instanceof String single) {
            return single.equals(permission);
        }
        return user.roles().contains(permission);
    }
}
