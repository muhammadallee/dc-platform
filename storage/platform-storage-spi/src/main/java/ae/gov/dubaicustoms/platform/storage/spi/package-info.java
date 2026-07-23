/**
 * The storage provider-support layer. Unusually for a platform capability there is <b>no</b> provider
 * interface here: an {@code ObjectStore} provider simply contributes an
 * {@link ae.gov.dubaicustoms.platform.storage.ObjectStore} bean, so the only shared contract worth an
 * SPI module is {@link ae.gov.dubaicustoms.platform.storage.spi.KeyValidator}, the path-traversal guard
 * every provider must apply. Kept minimal on purpose (spec §A).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.storage.spi;
