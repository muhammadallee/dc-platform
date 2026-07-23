package ae.gov.dubaicustoms.platform.audit;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One immutable record of a security-relevant or business-relevant action, as written to the
 * configured {@link ae.gov.dubaicustoms.platform.audit.spi.AuditSink AuditSink}.
 *
 * <pre>{@code
 * auditor.record(new AuditEvent(
 *         "order.create", currentUser, "order:42", Outcome.SUCCESS,
 *         Instant.now(), correlationId, Map.of("total", 199.0)));
 * }</pre>
 *
 * <p>Produced automatically by {@link Audited} (the aspect fills {@code actor} from the current user,
 * {@code correlationId} from the request context, and {@code outcome} from the method's return or
 * thrown exception) or built directly for programmatic auditing via {@link Auditor}.
 *
 * <p>Value object; immutable and thread-safe. {@code action}, {@code actor}, {@code outcome},
 * {@code at}, and {@code details} are never {@code null}; {@code resource} and {@code correlationId}
 * are {@code null} when unknown. {@code details} is defensively copied into an immutable map.
 *
 * @param action the logical action name, e.g. {@code order.create}; never {@code null}
 * @param actor the principal that performed the action, e.g. a subject id; never {@code null}
 *     (use a sentinel such as {@code anonymous} or {@code system} when there is no authenticated user)
 * @param resource the resource acted upon, e.g. {@code order:42}; {@code null} when not applicable
 * @param outcome whether the action succeeded or failed; never {@code null}
 * @param at when the action occurred; never {@code null}
 * @param correlationId the correlation id of the originating request; {@code null} when none is open
 * @param details additional structured context; never {@code null}, may be empty
 * @since 0.2.0
 */
public record AuditEvent(
        String action,
        String actor,
        String resource,
        Outcome outcome,
        Instant at,
        String correlationId,
        Map<String, Object> details) {

    /**
     * Validates required components and defensively copies {@code details}.
     *
     * @throws NullPointerException if {@code action}, {@code actor}, {@code outcome}, or {@code at}
     *     is null
     */
    public AuditEvent {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(at, "at must not be null");
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
