/**
 * The filesystem storage provider: {@link ae.gov.dubaicustoms.platform.storage.fs.FsObjectStore} stores
 * each object as a file under a root directory, with a JSON metadata sidecar. The default provider —
 * fully local and Docker-free — selected by {@code platform-storage-autoconfigure} when no other
 * provider is present.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.storage.fs;
