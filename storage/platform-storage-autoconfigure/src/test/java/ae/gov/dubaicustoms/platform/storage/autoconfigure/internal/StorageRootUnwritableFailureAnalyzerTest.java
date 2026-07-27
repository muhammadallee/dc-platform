package ae.gov.dubaicustoms.platform.storage.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.storage.ObjectStoreException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

class StorageRootUnwritableFailureAnalyzerTest {

    private final StorageRootUnwritableFailureAnalyzer analyzer = new StorageRootUnwritableFailureAnalyzer();

    @Test
    void namesThePropertyAndS3AlternativeForRootFailure() {
        ObjectStoreException cause = new ObjectStoreException(
                ObjectStoreException.IO, "cannot create storage root /read-only/dc-storage", new IOException("denied"));

        FailureAnalysis analysis = analyzer.analyze(cause);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getAction())
                .contains("dc.platform.storage.fs.root")
                .contains("platform-starter-storage-s3");
    }

    @Test
    void staysSilentForOrdinaryIoFailures() {
        ObjectStoreException runtime = new ObjectStoreException(ObjectStoreException.IO, "failed to write object orders/42");
        assertThat(analyzer.analyze(runtime)).isNull();
    }

    @Test
    void staysSilentForInvalidKey() {
        ObjectStoreException invalidKey = new ObjectStoreException(ObjectStoreException.INVALID_KEY, "cannot create storage root x");
        assertThat(analyzer.analyze(invalidKey)).isNull();
    }
}
