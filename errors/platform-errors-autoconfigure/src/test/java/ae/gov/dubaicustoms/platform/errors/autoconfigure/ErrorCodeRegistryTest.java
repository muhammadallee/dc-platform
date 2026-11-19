package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The error-code registry gate (phase-04 spec): scans this module's classpath for {@code static final String}
 * constants holding a platform error code ({@code DC-<CAP>-<NNNN>}, D138), asserts their platform-wide uniqueness,
 * and writes {@code target/error-codes.csv} for the docs build. Constants are read from the class files, so no class
 * is loaded or initialized. {@link ErrorCodeSourceUniquenessTest} covers the modules this one does not depend on.
 */
class ErrorCodeRegistryTest {

    private static final Pattern FORMAT = Pattern.compile("^DC-[A-Z]{2,8}-\\d{4}$");

    private record Registration(String code, String declaredBy) {
    }

    @Test
    void errorCodeConstantsAreWellFormedUniqueAndExported() throws IOException {
        List<Registration> registrations = scanForErrorCodeConstants();

        // The scan must actually see this module's own constants - an empty result would mean
        // the gate silently stopped gating.
        assertThat(registrations)
                .extracting(Registration::code)
                .contains("DC-CORE-0500", "DC-CORE-0400", "DC-CORE-0410", "DC-CORE-0499",
                        "DC-DATA-0409", "DC-DATA-0410", "DC-DATA-0503");

        assertThat(registrations)
                .extracting(Registration::code)
                .as("error codes are contracts and must be unique platform-wide")
                .doesNotHaveDuplicates();

        Path csv = Path.of("target", "error-codes.csv");
        List<String> lines = new ArrayList<>();
        lines.add("code,declaredBy");
        registrations.stream()
                .sorted(Comparator.comparing(Registration::code))
                .forEach(registration -> lines.add(registration.code() + "," + registration.declaredBy()));
        Files.write(csv, lines);
        assertThat(csv).exists();
    }

    private static List<Registration> scanForErrorCodeConstants() {
        List<Registration> registrations = new ArrayList<>();
        try (ScanResult scan = new ClassGraph()
                .acceptPackages("ae.gov.dubaicustoms.platform")
                .rejectPackages("ae.gov.dubaicustoms.platform.build")
                .enableClassInfo()
                .enableFieldInfo()
                .enableStaticFinalFieldConstantInitializerValues()
                .ignoreClassVisibility()
                .ignoreFieldVisibility()
                .scan()) {
            for (ClassInfo classInfo : scan.getAllClasses()) {
                // Test fixtures in this module declare sample codes; only production classes count.
                if (classInfo.getClasspathElementFile() != null
                        && classInfo.getClasspathElementFile().toString().replace('\\', '/').contains("/test-classes")) {
                    continue;
                }
                for (FieldInfo fieldInfo : classInfo.getDeclaredFieldInfo()) {
                    if (fieldInfo.isStatic() && fieldInfo.getConstantInitializerValue() instanceof String value
                            && FORMAT.matcher(value).matches()) {
                        registrations.add(new Registration(value, classInfo.getName()));
                    }
                }
            }
        }
        return registrations;
    }
}
