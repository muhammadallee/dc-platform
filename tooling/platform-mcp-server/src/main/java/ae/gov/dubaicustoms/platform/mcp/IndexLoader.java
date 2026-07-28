package ae.gov.dubaicustoms.platform.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link PlatformIndex} from the bundled {@code /platform-index.json} classpath resource, or from
 * an explicit path (an override for running against a freshly generated index without rebuilding the jar).
 */
public final class IndexLoader {

    /** Classpath location of the index bundled into the jar at build time. */
    public static final String RESOURCE = "/platform-index.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IndexLoader() {
    }

    /** Loads the index bundled on the classpath. */
    public static PlatformIndex fromClasspath() {
        try (InputStream in = IndexLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("platform-index.json is not on the classpath ("
                        + RESOURCE + "); the jar was built without the index resource");
            }
            return MAPPER.readValue(in, PlatformIndex.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads the index from a filesystem path. */
    public static PlatformIndex fromPath(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return MAPPER.readValue(in, PlatformIndex.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
