package ae.gov.dubaicustoms.platform.files;

/**
 * Sanitises a client-supplied filename into one safe to use as a storage key or on disk. Strips any
 * directory component (defeating path traversal such as {@code ../../etc/passwd}), removes null bytes
 * and control characters, replaces anything outside {@code [A-Za-z0-9._-]} with {@code _}, drops
 * leading dots (hidden/relative names), and bounds the length.
 *
 * <pre>{@code
 * SafeFilename.sanitize("../../etc/passwd");   // "passwd"
 * SafeFilename.sanitize("my report (v2).pdf"); // "my_report__v2_.pdf"
 * SafeFilename.sanitize(null);                 // "unnamed"
 * }</pre>
 *
 * <p>Never returns {@code null} or a blank string; a name that sanitises to nothing becomes
 * {@code "unnamed"}. Stateless and thread-safe.
 *
 * @since 0.2.0
 */
public final class SafeFilename {

    private static final String FALLBACK = "unnamed";
    private static final int MAX_LENGTH = 255;

    private SafeFilename() {
    }

    /**
     * Sanitises {@code raw} into a safe filename.
     *
     * @param raw the client-supplied name; may be {@code null}
     * @return a safe, non-blank filename
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return FALLBACK;
        }
        // Keep only the final path segment, treating both separators as directory boundaries.
        String name = raw.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        // Drop control characters (incl. NUL) first; a name that is only whitespace/control is empty.
        name = name.replaceAll("[\\x00-\\x1f\\x7f]", "");
        if (name.isBlank()) {
            return FALLBACK;
        }
        // Collapse anything unsafe to an underscore.
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        // Strip leading dots so the result cannot be "", ".", ".." or a hidden dotfile.
        name = name.replaceAll("^\\.+", "");
        if (name.isBlank()) {
            return FALLBACK;
        }
        if (name.length() > MAX_LENGTH) {
            name = name.substring(name.length() - MAX_LENGTH);
        }
        return name;
    }
}
