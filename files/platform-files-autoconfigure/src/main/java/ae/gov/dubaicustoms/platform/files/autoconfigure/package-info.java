/**
 * Auto-configuration for the files capability: the default magic-byte {@code ContentTypeValidator}, a
 * default {@code FileUploadPolicy} from {@code dc.platform.files.*}, servlet multipart size limits,
 * and the {@link ae.gov.dubaicustoms.platform.files.autoconfigure.StreamingDownloads} helper.
 *
 * <p>Every bean backs off on a user-supplied equivalent. {@code StreamingDownloads} lives here rather
 * than in the api because it bridges the storage capability and Spring MVC.
 *
 * @since 0.2.0
 */
package ae.gov.dubaicustoms.platform.files.autoconfigure;
