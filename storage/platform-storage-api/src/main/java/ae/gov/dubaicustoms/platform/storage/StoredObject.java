package ae.gov.dubaicustoms.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * An object retrieved from an {@link ObjectStore}: its {@link ObjectMetadata} plus an open content
 * stream the caller must consume and close.
 *
 * <p>Implements {@link AutoCloseable} so it can be used in a try-with-resources block; {@link #close()}
 * closes the underlying {@link #content()} stream. Reading the content after closing is undefined.
 *
 * <pre>{@code
 * try (StoredObject obj = store.get("invoices", key).orElseThrow()) {
 *     obj.content().transferTo(outputStream);
 * }
 * }</pre>
 *
 * <p>Not thread-safe: the content stream is a single-consumer resource; confine it to one thread.
 *
 * @param metadata the object's metadata; never {@code null}
 * @param content an open stream over the object's bytes; owned and closed by the caller; never {@code null}
 * @since 0.2.0
 */
public record StoredObject(ObjectMetadata metadata, InputStream content) implements AutoCloseable {

    /**
     * Validates the components.
     *
     * @throws NullPointerException if {@code metadata} or {@code content} is null
     */
    public StoredObject {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    /**
     * Closes the underlying content stream.
     *
     * @throws IOException if closing the content stream fails
     */
    @Override
    public void close() throws IOException {
        content.close();
    }
}
