package ae.gov.dubaicustoms.platform.data.jpa.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.security.CurrentUser;
import ae.gov.dubaicustoms.platform.security.CurrentUserAccessor;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;

/**
 * Adapts the platform's {@link CurrentUserAccessor} to Spring Data's {@link AuditorAware}: the
 * auditor is the authenticated principal's subject, falling back to {@value #SYSTEM} when there is
 * no accessor bean or no authenticated request.
 *
 * <p>Only loaded when the security capability is on the classpath (its constructor references a
 * security type); the no-security path uses a plain {@code "system"} lambda instead.
 */
public final class AuditorAwareProvider implements AuditorAware<String> {

    /** Auditor recorded for unauthenticated writes and background/system activity. */
    public static final String SYSTEM = "system";

    private final CurrentUserAccessor accessor;

    /**
     * @param accessor the current-user accessor, or {@code null} when no such bean is registered
     */
    public AuditorAwareProvider(CurrentUserAccessor accessor) {
        this.accessor = accessor;
    }

    @Override
    public Optional<String> getCurrentAuditor() {
        if (accessor == null) {
            return Optional.of(SYSTEM);
        }
        return accessor.currentUser().map(CurrentUser::subject).or(() -> Optional.of(SYSTEM));
    }
}
