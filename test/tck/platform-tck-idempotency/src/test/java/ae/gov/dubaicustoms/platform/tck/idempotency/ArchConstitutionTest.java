package ae.gov.dubaicustoms.platform.tck.idempotency;

import ae.gov.dubaicustoms.platform.build.arch.PlatformArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/** Runs the platform architecture constitution over this module's production classes. */
class ArchConstitutionTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeJars())
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("ae.gov.dubaicustoms.platform");

    @Test
    void obeysThePlatformConstitution() {
        for (ArchRule rule : PlatformArchRules.all()) {
            rule.check(CLASSES);
        }
    }
}
