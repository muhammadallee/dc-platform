package ae.gov.dubaicustoms.platform.audit.autoconfigure.internal;

/**
 * Resolves the actor recorded on an {@code AuditEvent} for the current invocation, decoupling the
 * {@code @Audited} aspect from the security capability. The default resolves to {@code anonymous};
 * when the security capability is present, a resolver backed by {@code CurrentUserAccessor} is
 * contributed instead (guarded reference — the aspect itself never touches security-api).
 *
 * @since 0.2.0
 */
@FunctionalInterface
public interface ActorResolver {

    /** Sentinel actor when no authenticated principal can be resolved. */
    String ANONYMOUS = "anonymous";

    /**
     * Returns the current actor.
     *
     * @return the actor identifier; never {@code null} (use {@link #ANONYMOUS} when unknown)
     */
    String currentActor();
}
