/**
 * Safe file-handling primitives: {@link ae.gov.dubaicustoms.platform.files.FileUploadPolicy} (size +
 * content-type rules), {@link ae.gov.dubaicustoms.platform.files.SafeFilename} (path-traversal-safe
 * name sanitisation), and {@link ae.gov.dubaicustoms.platform.files.ContentTypeValidator} (magic-byte
 * content sniffing).
 *
 * <p>Applications depend only on this module; {@code platform-files-autoconfigure} supplies the
 * default validator, multipart limits, and the {@code StreamingDownloads} helper (which bridges the
 * storage capability to a streaming HTTP response and therefore cannot live in this dependency-poor
 * api module).
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.files;
