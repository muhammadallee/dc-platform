/**
 * Auto-configuration contributing the messaging audit sink:
 * {@link ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure.MessagingAuditSinkAutoConfiguration}
 * publishes {@code AuditEvent}s to the messaging transport, the top link in the audit degradation
 * chain.
 *
 * <p>A separate module from {@code platform-audit-autoconfigure} so that module stays within the
 * fan-out ceiling and messaging-api reaches only consumers who opt into audit-over-messaging
 * (decision D53). Activates only when an {@code EventPublisher} bean is present.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.audit.messaging.autoconfigure;
