/**
 * Auto-configuration for the storage capability: selects the {@code ObjectStore} provider by classpath
 * (S3 when the AWS SDK and an {@code S3Client} bean are present, otherwise the filesystem provider),
 * decorates it with checksum-on-put and observation, and reports the active provider as a
 * {@link ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor}.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.storage.autoconfigure;
