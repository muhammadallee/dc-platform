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
        /** Rabbit-provider-specific settings. */
        Rabbit rabbit) {

    /**
     * Normalizes nested groups: Spring's constructor binding leaves unset object components
     * {@code null} rather than applying their own defaults, so each is filled in here.
     */
    public MessagingProperties {
        handler = handler != null ? handler : new Handler(null);
        dlq = dlq != null ? dlq : new Dlq(null);
        rabbit = rabbit != null ? rabbit : new Rabbit(true, null, 0, null, 0);
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
            retry = retry != null ? retry : new Retry(0, null, null);
        }
    }

    /**
     * Bounded retry policy for {@code @EventHandler} method failures, applied by
     * {@code EventHandlerRegistrar} before a message is routed to the DLQ destination.
     *
     * @param maxAttempts total attempts before the message is dead-lettered
     * @param backoff pause before the second attempt; doubles per attempt
     * @param maxBackoff the longest pause between attempts
     * @since 0.2.0
     */
    public record Retry(
            /** Total attempts before the message is dead-lettered. */
            @DefaultValue("3") int maxAttempts,
            /** Pause before the second attempt; doubles per attempt. */
            @DefaultValue("1s") Duration backoff,
            /** The longest pause between attempts. */
            @DefaultValue("30s") Duration maxBackoff) {

        /** Fills in defaults for any unset component (used when constructed programmatically). */
        public Retry {
            maxAttempts = maxAttempts > 0 ? maxAttempts : 3;
            backoff = backoff != null ? backoff : Duration.ofSeconds(1);
            maxBackoff = maxBackoff != null ? maxBackoff : Duration.ofSeconds(30);
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
     * Rabbit-provider-specific settings.
     *
     * @param quorumQueues declare quorum queues, the main and the dead-letter one; {@code false} for classic queues
     * @param confirmTimeout how long a send waits for the broker's publisher confirm before it fails
     * @param prefetch how many messages a consumer holds at once, and so how many a retrying handler can delay
     * @param shutdownTimeout how long a stopping consumer waits for the handler in flight
     * @param deliveryLimit returns a quorum queue allows before dead-lettering: catches a message that keeps killing its
     *     consumer, which in-process retries cannot count. A message merely held by a stopping consumer counts too, so it
     *     is well above what rolling restarts reach
     * @since 0.2.0
     */
    public record Rabbit(
            /** Declare quorum queues, the main and the dead-letter one; false for classic queues. */
            @DefaultValue("true") boolean quorumQueues,
            /** How long a send waits for the broker's publisher confirm before it fails. */
            @DefaultValue("5s") Duration confirmTimeout,
            /** How many messages a consumer holds at once, and so how many a retrying handler can delay. */
            @DefaultValue("10") int prefetch,
            /** How long a stopping consumer waits for the handler in flight. */
            @DefaultValue("30s") Duration shutdownTimeout,
            /** Deliveries a quorum queue allows before dead-lettering a message that keeps killing its consumer. */
            @DefaultValue("20") int deliveryLimit) {

        /** Fills in the default timeout when unset (used when constructed programmatically). */
        public Rabbit {
            confirmTimeout = confirmTimeout != null ? confirmTimeout : Duration.ofSeconds(5);
            prefetch = prefetch > 0 ? prefetch : 10;
            shutdownTimeout = shutdownTimeout != null ? shutdownTimeout : Duration.ofSeconds(30);
            deliveryLimit = deliveryLimit > 0 ? deliveryLimit : 20;
        }
    }
}
