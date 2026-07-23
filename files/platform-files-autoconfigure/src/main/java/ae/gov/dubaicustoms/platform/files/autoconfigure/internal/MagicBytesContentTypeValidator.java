package ae.gov.dubaicustoms.platform.files.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.files.ContentTypeValidator;
import java.util.Optional;

/**
 * Default {@link ContentTypeValidator}: recognises a small, deliberately-scoped v1 set by magic
 * number — PDF, PNG, JPEG, ZIP — and falls back to a text sniff (CSV vs plain text) for content that
 * is valid printable UTF-8. Anything else is unrecognised (empty), so a strict allow-list rejects it.
 *
 * <p><strong>Scope (decision D55):</strong> this is intentionally not a full content-detection library
 * (no Tika). It covers the document/image/archive/text types the platform's forms actually accept; a
 * service needing more should supply its own {@code ContentTypeValidator} bean.
 *
 * <p>Stateless and thread-safe.
 *
 * @since 0.2.0
 */
public final class MagicBytesContentTypeValidator implements ContentTypeValidator {

    private static final byte[] PDF = {'%', 'P', 'D', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};

    @Override
    public Optional<String> sniff(byte[] content) {
        if (content == null || content.length == 0) {
            return Optional.empty();
        }
        if (startsWith(content, PDF)) {
            return Optional.of("application/pdf");
        }
        if (startsWith(content, PNG)) {
            return Optional.of("image/png");
        }
        if (startsWith(content, JPEG)) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(content, ZIP)) {
            return Optional.of("application/zip");
        }
        return sniffText(content);
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    // Text has no magic number: treat content as text only if its prefix carries no NUL or stray
    // control bytes (anything below 0x20 other than tab/newline/carriage-return). High bytes (>= 0x80)
    // are allowed so UTF-8 multi-byte text passes. A comma before the first newline marks CSV.
    private static Optional<String> sniffText(byte[] content) {
        int limit = Math.min(content.length, 4096);
        boolean sawComma = false;
        boolean beforeFirstNewline = true;
        for (int i = 0; i < limit; i++) {
            int b = content[i] & 0xFF;
            boolean printable = b >= 0x20 || b == '\t' || b == '\n' || b == '\r';
            if (!printable) {
                return Optional.empty();
            }
            if (beforeFirstNewline) {
                if (b == '\n') {
                    beforeFirstNewline = false;
                } else if (b == ',') {
                    sawComma = true;
                }
            }
        }
        return Optional.of(sawComma ? "text/csv" : "text/plain");
    }
}
