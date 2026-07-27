package ae.gov.dubaicustoms.platform.flags.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.apiguardian.api.API;

/**
 * The context a {@link FlagProvider} evaluates a flag against: the current user and tenant (when the
 * platform can resolve them) plus arbitrary targeting attributes.
 *
 * <p>The platform populates {@code userId}/{@code tenantId} from the security capability's current-user
 * accessor when it is present; in an unauthenticated or security-less context both are absent, which is
 * the normal case for a background job — providers must tolerate it.
 *
 * <p>Value object; immutable and thread-safe. {@code attributes} is copied defensively.
 *
 * @param userId the current user id, or empty when unknown
 * @param tenantId the current tenant id, or empty when unknown
 * @param attributes additional targeting attributes; never {@code null}
 * @since 0.2.0
 */
@API(status = API.Status.EXPERIMENTAL, since = "0.1.0")
public record EvaluationContext(Optional<String> userId, Optional<String> tenantId,
                                Map<String, Object> attributes) {

    /**
     * Validates and defensively copies the components.
     *
     * @throws NullPointerException if any component is null
     */
    public EvaluationContext {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        attributes = Map.copyOf(attributes);
    }

    /**
     * An empty context: no user, no tenant, no attributes — for background/anonymous evaluation.
     *
     * @return the anonymous evaluation context
     */
    public static EvaluationContext anonymous() {
        return new EvaluationContext(Optional.empty(), Optional.empty(), Map.of());
    }
}
