package ae.gov.dubaicustoms.platform.authz.spi;

import ae.gov.dubaicustoms.platform.security.CurrentUser;

/**
 * Pluggable permission-evaluation strategy behind {@code @RequiresPermission}. The authz
 * autoconfiguration's method-security aspect calls this for every annotated invocation; the
 * default provider maps permissions from a roles claim (configurable claim name).
 *
 * <pre>{@code
 * @Bean
 * PermissionEvaluatorProvider entitlementServiceProvider(EntitlementClient client) {
 *     return (user, permission) -> client.hasPermission(user.subject(), permission);
 * }
 * }</pre>
 *
 * <p>Implementations must be thread-safe and fast — they run on the request thread for every
 * annotated invocation. {@code user} and {@code permission} are never {@code null}.
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface PermissionEvaluatorProvider {

    /**
     * Evaluates whether the given user holds the given permission.
     *
     * @param user the authenticated principal; never {@code null}
     * @param permission the permission being checked, e.g. {@code "orders:read"}; never {@code null}
     * @return {@code true} if the user holds the permission
     */
    boolean hasPermission(CurrentUser user, String permission);
}
