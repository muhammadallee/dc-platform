package ae.gov.dubaicustoms.platform.messaging;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apiguardian.api.API;

/**
 * Names and rules for the headers on an event message.
 *
 * <p>Usage: {@code if (EventHeaders.isCredentialName(name)) { refuse it }}. Every message also carries
 * {@link EventEnvelope#EVENT_ID_HEADER}, {@code eventType}, {@code eventVersion}, and {@code traceparent} when a trace is
 * active.
 *
 * <p>Stateless and thread-safe. No method returns {@code null}.
 *
 * @since 1.0.0
 */
@API(status = API.Status.STABLE, since = "1.0.0")
public final class EventHeaders {

    /** The header naming the service that published the event. */
    public static final String PRODUCER = "producer";

    private static final Set<String> CREDENTIAL_SUFFIXES =
            Set.of("password", "passwd", "pwd", "secret", "token", "apikey", "authorization", "cookie");

    private EventHeaders() {
    }

    /**
     * Whether a header of this name would carry a credential, which an event must not.
     *
     * @param name the header name; never {@code null}
     * @return {@code true} if it must be refused, whatever its case and separators
     */
    public static boolean isCredentialName(String name) {
        String normalized = Objects.requireNonNull(name, "name must not be null").toLowerCase(Locale.ROOT)
                .replace("-", "").replace("_", "");
        return CREDENTIAL_SUFFIXES.stream().anyMatch(normalized::endsWith);
    }
}
