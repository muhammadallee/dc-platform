/**
 * Test support for the messaging capability: {@link ae.gov.dubaicustoms.platform.messaging.testing.TestEventTransport}
 * records sent messages and lets a test simulate inbound ones,
 * {@link ae.gov.dubaicustoms.platform.messaging.testing.EventsAssert} asserts over them, and
 * {@link ae.gov.dubaicustoms.platform.messaging.testing.AutoConfigureTestTransport} wires the
 * former into a test's Spring context.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.messaging.testing;
