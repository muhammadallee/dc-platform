package ae.gov.dubaicustoms.platform.security;

import java.util.Optional;

/**
 * Reads the authenticated principal of the current request, independent of the underlying
 * authentication mechanism.
 *
 * <pre>{@code
 * @Service
 * class AuditTrail {
 *     private final CurrentUserAccessor accessor;
 *
 *     void recordChange(Order order) {
 *         accessor.currentUser().ifPresent(u -> log.info("changed by {}", u.subject()));
 *     }
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe and read-only: population happens exclusively in
 * platform security infrastructure.
 *
 * @since 0.2.0
 */
public interface CurrentUserAccessor {

    /**
     * Returns the authenticated principal of the current request, if any.
     *
     * @return the current user; empty when the request is unauthenticated
     */
    Optional<CurrentUser> currentUser();
}
