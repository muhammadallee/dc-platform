package ae.gov.dubaicustoms.platform.storage.fs.internal;

import ae.gov.dubaicustoms.platform.storage.ObjectMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads and writes the JSON metadata sidecar that sits next to each stored object file. Exists so the
 * filesystem provider can round-trip {@link ObjectMetadata} (content type + arbitrary user tags) that
 * a plain byte file cannot carry, with correct escaping for arbitrary tag values.
 */
public final class MetadataSidecar {

    /** Suffix of the sidecar file for an object; excluded from listings. */
    public static final String SUFFIX = ".meta.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MetadataSidecar() {
    }

    /** Sidecar path for the given object file. */
    public static Path sidecarOf(Path objectFile) {
        return objectFile.resolveSibling(objectFile.getFileName() + SUFFIX);
    }

    /** Whether the given file is a sidecar (so listings can skip it). */
    public static boolean isSidecar(Path file) {
        return file.getFileName().toString().endsWith(SUFFIX);
    }

    /**
     * Writes {@code metadata} to the sidecar for {@code objectFile}.
     *
     * @throws UncheckedIOException if the write fails (mapped to ObjectStoreException by the provider)
     */
    public static void write(Path objectFile, ObjectMetadata metadata) {
        try {
            MAPPER.writeValue(sidecarOf(objectFile).toFile(), metadata);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write metadata sidecar for " + objectFile, e);
        }
    }

    /**
     * Reads the sidecar for {@code objectFile}, or synthesises minimal metadata (octet-stream, file
     * length, no tags) when no sidecar exists — so an object written out-of-band is still readable.
     *
     * @throws UncheckedIOException if the read fails
     */
    public static ObjectMetadata read(Path objectFile) {
        Path sidecar = sidecarOf(objectFile);
        try {
            if (!Files.exists(sidecar)) {
                return new ObjectMetadata("application/octet-stream", Files.size(objectFile), java.util.Map.of());
            }
            return MAPPER.readValue(sidecar.toFile(), ObjectMetadata.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read metadata sidecar for " + objectFile, e);
        }
    }

    /** Deletes the sidecar for {@code objectFile} if present. */
    public static void delete(Path objectFile) {
        try {
            Files.deleteIfExists(sidecarOf(objectFile));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to delete metadata sidecar for " + objectFile, e);
        }
    }
}
