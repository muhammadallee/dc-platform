package ae.gov.dubaicustoms.platform.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apiguardian.api.API;

/**
 * The authenticated principal, in platform-neutral shape: independent of the underlying token
 * format (JWT claims today; any resource-server principal tomorrow).
 *
 * <pre>{@code
 * currentUserAccessor.currentUser()
 *         .map(CurrentUser::subject)
 *         .ifPresent(auditTrail::recordActor);
 * }</pre>
 *
 * <p>Value object; immutable and thread-safe. {@code subject}, {@code roles}, and {@code claims}
 * are never {@code null}; {@code tenant} is {@code null} when the token carries none. {@code roles}
 * and {@code claims} are defensively copied into immutable collections.
 *
 * @param subject the authenticated principal identifier (JWT {@code sub} claim)
 * @param tenant the tenant identifier, or {@code null} when the token carries none
 * @param roles the principal's roles; never {@code null}, may be empty
 * @param claims the full claim set backing this principal; never {@code null}, may be empty
 * @since 0.2.0
 */
@API(status = API.Status.STABLE, since = "0.1.0")
public record CurrentUser(String subject, String tenant, Set<String> roles, Map<String, Object> claims) {

    /**
     * Validates required components and defensively copies the collections.
     *
     * @throws NullPointerException if {@code subject}, {@code roles}, or {@code claims} is null
     */
    public CurrentUser {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        Objects.requireNonNull(claims, "claims must not be null");
        roles = Set.copyOf(roles);
        claims = Map.copyOf(claims);
    }
}
