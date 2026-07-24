package ae.gov.dubaicustoms.example.extension;

import ae.gov.dubaicustoms.platform.storage.ObjectStore;
import ae.gov.dubaicustoms.platform.storage.fs.FsObjectStore;
import ae.gov.dubaicustoms.platform.tck.storage.ObjectStoreTck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/**
 * Certifies {@link EncryptingFsObjectStore} against the platform storage TCK: extending
 * {@link ObjectStoreTck} inherits every ObjectStore invariant, and a green run is the definition of
 * "platform-certified for storage". This is the acceptance for the extension example's provider —
 * a custom store that encrypts at rest still round-trips content, size, tags, and listings exactly.
 */
class EncryptingFsObjectStoreTckTest extends ObjectStoreTck {

    @Override
    protected ObjectStore store() {
        try {
            FsObjectStore backing = new FsObjectStore(Files.createTempDirectory("encrypting-tck-"));
            return new EncryptingFsObjectStore(backing, EncryptingFsObjectStore.generateKey());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create a temp backing store for the TCK", e);
        }
    }
}
