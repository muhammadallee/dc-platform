package ae.gov.dubaicustoms.platform.messaging.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the messaging capability. Bound from {@code dc.platform.messaging.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformMessagingAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param defaultDestinationPrefix conventional prefix applications may use when naming
 *     destinations (not enforced; a naming convention only)
 * @param handler handler-side retry policy applied by {@code EventHandlerRegistrar}
 * @param dlq dead-letter destination naming
 * @param correlation correlation-header propagation on publish
 * @param rabbit rabbit-provider-specific settings, read only by the kafka/rabbit nested transport
 *     configurations in {@code PlatformMessagingAutoConfiguration}
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.messaging")
public record MessagingProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** Conventional destination-name prefix; not enforced, a naming convention only. */
        @DefaultValue("dc.") String defaultDestinationPrefix,
        /** Handler-side retry policy applied by EventHandlerRegistrar. */
        Handler handler,
        /** Dead-letter destination naming. */
        Dlq dlq,
        /** Correlation-header propagation on publish. */
        Correlation correlation,
        /** Rabbit-provider-specific settings. */
        Rabbit rabbit) {

    /**
     * Normalizes nested groups: Spring's constructor binding leaves unset object components
     * {@code null} rather than applying their own defaults, so each is filled in here.
     */
    public MessagingProperties {
        handler = handler != null ? handler : new Handler(null);
        dlq = dlq != null ? dlq : new Dlq(null);
        correlation = correlation != null ? correlation : new Correlation(true);
        rabbit = rabbit != null ? rabbit : new Rabbit(false);
    }

    /**
     * Handler-side retry policy.
     *
     * @param retry the retry sub-policy
     * @since 0.2.0
     */
    public record Handler(Retry retry) {

        /** Fills in {@link Retry} defaults when the group is present but empty. */
        public Handler {
            retry = retry != null ? retry : new Retry(0, null);
        }
    }

    /**
     * Bounded retry policy for {@code @EventHandler} method failures, applied by
     * {@code EventHandlerRegistrar} before a message is routed to the DLQ destination.
     *
     * @param maxAttempts total delivery attempts before giving up and republishing to the DLQ
     * @param backoff pause between attempts
     * @since 0.2.0
     */
    public record Retry(
            /** Total delivery attempts before giving up and republishing to the DLQ destination. */
            @DefaultValue("3") int maxAttempts,
            /** Pause between attempts. */
            @DefaultValue("1s") Duration backoff) {

        /** Fills in defaults for any unset component (used when constructed programmatically). */
        public Retry {
            maxAttempts = maxAttempts > 0 ? maxAttempts : 3;
            backoff = backoff != null ? backoff : Duration.ofSeconds(1);
        }
    }

    /**
     * Dead-letter destination naming.
     *
     * @param suffix appended to a destination name to form its DLQ destination, e.g.
     *     {@code "dc.orders" + ".dlq" = "dc.orders.dlq"}
     * @since 0.2.0
     */
    public record Dlq(
            /** Appended to a destination name to form its DLQ destination. */
            @DefaultValue(".dlq") String suffix) {

        /** Fills in the default suffix when unset. */
        public Dlq {
            suffix = suffix != null ? suffix : ".dlq";
        }
    }

    /**
     * Correlation-header propagation on publish.
     *
     * @param propagate publish the current {@code RequestContext} correlation id as the
     *     {@code correlationId} header
     * @since 0.2.0
     */
    public record Correlation(
            /** Publish the current RequestContext correlation id as the correlationId header. */
            @DefaultValue("true") boolean propagate) {
    }

    /**
     * Rabbit-provider-specific settings.
     *
     * @param quorumQueues declare quorum queues instead of classic queues; opt-in for production
     *     clusters
     * @since 0.2.0
     */
    public record Rabbit(
            /** Declare quorum queues instead of classic queues; opt-in for production clusters. */
            @DefaultValue("false") boolean quorumQueues) {
    }
}
