package ae.gov.dubaicustoms.platform.events.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the events capability. Bound from {@code dc.platform.events.*}.
 *
 * <p>Immutable; validated at startup. Registered by {@code PlatformEventsAutoConfiguration} via
 * {@code @EnableConfigurationProperties} (never scanned).
 *
 * @param enabled master kill switch for the whole capability
 * @param relay the integration-event relay's own settings
 * @since 0.2.0
 */
@Validated
@ConfigurationProperties(prefix = "dc.platform.events")
public record EventsProperties(
        /** Master switch for the capability. */
        @DefaultValue("true") boolean enabled,
        /** The integration-event relay's own settings. */
        Relay relay) {

    /** Normalizes the nested {@link Relay} group when unset. */
    public EventsProperties {
        relay = relay != null ? relay : new Relay(false, null);
    }

    /**
     * Settings for the optional relay that re-publishes {@code @EventType}-annotated domain events
     * as integration events after commit, via the messaging capability's {@code EventPublisher}
     * (only active when that bean actually exists).
     *
     * <p>This is a lightweight outbox precursor, not a true transactional outbox: the relay
     * publishes on the same after-commit callback as {@code @DomainEventHandler}, so a crash
     * between commit and publish still loses the integration event. A true outbox (durable
     * staging table + separate publisher process) is a documented future enhancement — see
     * {@code docs/decisions/decision-log.md}.
     *
     * @param enabled opt-in switch; the relay is inactive by default even when an
     *     {@code EventPublisher} bean exists
     * @param destinationPrefix prefix used to build the integration-event destination from a
     *     domain event's {@code @EventType} value, e.g. {@code "dc." + "OrderPlaced"}
     * @since 0.2.0
     */
    public record Relay(
            /** Opt-in switch; inactive by default even when an EventPublisher bean exists. */
            @DefaultValue("false") boolean enabled,
            /** Prefix used to build the integration-event destination from @EventType's value. */
            @DefaultValue("dc.") String destinationPrefix) {

        /** Fills in defaults for any unset component (used when constructed programmatically). */
        public Relay {
            destinationPrefix = destinationPrefix != null ? destinationPrefix : "dc.";
        }
    }
}
