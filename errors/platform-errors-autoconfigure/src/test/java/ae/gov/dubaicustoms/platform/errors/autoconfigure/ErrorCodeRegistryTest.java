package ae.gov.dubaicustoms.platform.errors.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.core.ErrorCode;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.ScanResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The error-code registry gate (phase-04 spec): scans the classpath for {@code ErrorCode}
 * constants, asserts format and platform-wide uniqueness, and writes
 * {@code target/error-codes.csv} for the phase-14 docs build.
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
                .contains("DC-CORE-0500", "DC-CORE-0400");

        assertThat(registrations)
                .allSatisfy(registration -> assertThat(registration.code()).matches(FORMAT));

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
                .ignoreClassVisibility()
                .ignoreFieldVisibility()
                .scan()) {
            for (ClassInfo classInfo : scan.getAllClasses()) {
                for (FieldInfo fieldInfo : classInfo.getDeclaredFieldInfo()) {
                    if (!fieldInfo.isStatic()
                            || !ErrorCode.class.getName()
                                    .equals(fieldInfo.getTypeSignatureOrTypeDescriptor().toString())) {
                        continue;
                    }
                    ErrorCode value = readConstant(classInfo.loadClass(), fieldInfo.getName());
                    if (value != null) {
                        registrations.add(new Registration(value.value(), classInfo.getName()));
                    }
                }
            }
        }
        return registrations;
    }

    private static ErrorCode readConstant(Class<?> declaringClass, String fieldName) {
        try {
            Field field = declaringClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (ErrorCode) field.get(null);
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new AssertionError(
                    "cannot read ErrorCode constant " + declaringClass.getName() + "." + fieldName, e);
        }
    }
}
