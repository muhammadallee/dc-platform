package ae.gov.dubaicustoms.platform.restclient.autoconfigure;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Which clients carry the caller's bearer token. Bound from {@code dc.platform.restclient.token-relay.*}.
 *
 * @param clients the client names the token is relayed to; none by default, since a token sent to a service can be replayed by it
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "dc.platform.restclient.token-relay")
public record TokenRelayProperties(
        /** The client names the token is relayed to; none by default. */
        @DefaultValue List<String> clients) {
}
