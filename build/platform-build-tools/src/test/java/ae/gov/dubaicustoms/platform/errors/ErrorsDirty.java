package ae.gov.dubaicustoms.platform.errors;

import com.tngtech.archunit.core.importer.ClassFileImporter;

// Fixture: the errors whitelist admits org.springframework.http ONLY - other libs still fail.
public final class ErrorsDirty {
    public Object leakThirdParty() {
        return new ClassFileImporter();
    }
}
