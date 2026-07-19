package ae.gov.dubaicustoms.platform.gamma;

import com.tngtech.archunit.core.importer.ClassFileImporter;

// Fixture: an api-root type dragging in a third-party library (rule 2 violation).
public final class GammaDirty {
    public Object leakThirdParty() {
        return new ClassFileImporter();
    }
}
