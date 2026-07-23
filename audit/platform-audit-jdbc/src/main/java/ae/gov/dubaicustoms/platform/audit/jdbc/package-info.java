/**
 * The JDBC audit sink: {@link ae.gov.dubaicustoms.platform.audit.jdbc.JdbcAuditSink} appends each
 * {@code AuditEvent} to the {@code platform_audit} table, serialising the details map to JSON.
 *
 * <p>Ships its Flyway migration under {@code db/migration-platform-audit}; the audit autoconfigure
 * appends that location. Selected ahead of the log sink when a {@code DataSource} is present.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.audit.jdbc;
