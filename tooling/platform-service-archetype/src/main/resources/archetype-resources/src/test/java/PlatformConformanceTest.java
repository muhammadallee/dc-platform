package ${package};

import ae.gov.dubaicustoms.platform.test.arch.PlatformUsageRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Enforces the platform's consumer conformance rules over this service's production classes: no direct
 * broker templates/listener containers, no hand-rolled {@code @RestControllerAdvice}, no
 * {@code System.getenv}. Each failure message names the platform API to use instead.
 *
 * <p>This is a deletable escape hatch: a team with a documented reason may remove this test
 * (discouraged — the rules are how coding agents learn the platform way).
 */
class PlatformConformanceTest {

    private static final JavaClasses PRODUCTION_CLASSES = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("${package}");

    @Test
    void followsPlatformUsageRules() {
        for (ArchRule rule : PlatformUsageRules.all()) {
            rule.check(PRODUCTION_CLASSES);
        }
    }
}
