package ae.gov.dubaicustoms.platform.storage.autoconfigure.internal;

import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// The filesystem store fails to start when its root directory cannot be created or written
// (FsObjectStore throws ObjectStoreException(IO, "cannot create storage root ...")). This analyzer
// (phase-16 A.2) turns that into an Action naming the property and the S3 alternative.
public class StorageRootUnwritableFailureAnalyzer extends AbstractFailureAnalyzer<ObjectStoreException> {

    private static final String ROOT_FAILURE_PREFIX = "cannot create storage root";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, ObjectStoreException cause) {
        if (!ObjectStoreException.IO.equals(cause.code())
                || cause.getMessage() == null || !cause.getMessage().startsWith(ROOT_FAILURE_PREFIX)) {
            return null; // an ordinary runtime IO failure, not a startup root-directory problem
        }
        String description = "The filesystem storage provider could not create or write its root directory: "
                + cause.getMessage();
        String action = "Point dc.platform.storage.fs.root at a directory the service can create and write "
                + "(it defaults to ${java.io.tmpdir}/dc-storage), or switch to the S3 provider by using "
                + "platform-starter-storage-s3 instead. See docs/modules/storage.md.";
        return new FailureAnalysis(description, action, cause);
    }
}
