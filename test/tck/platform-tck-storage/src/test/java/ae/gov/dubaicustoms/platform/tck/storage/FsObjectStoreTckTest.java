package ae.gov.dubaicustoms.platform.tck.storage;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.fs.FsObjectStore;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;

/**
 * Certifies the filesystem reference store against {@link ObjectStoreTck}, docker-free — each test
 * gets a fresh temporary root.
 */
class FsObjectStoreTckTest extends ObjectStoreTck {

    @TempDir
    Path root;

    @Override
    protected ObjectStore store() {
        return new FsObjectStore(root);
    }
}
