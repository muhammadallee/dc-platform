package ae.gov.dubaicustoms.platform.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Guards the FileUploadPolicy value contract and its allow-checks. */
class FileUploadPolicyTest {

    @Test
    void rejectsNonPositiveMaxSize() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FileUploadPolicy(0, Set.of("application/pdf")));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new FileUploadPolicy(-1, Set.of("application/pdf")));
    }

    @Test
    void allowedTypesAreDefensivelyCopiedAndImmutable() {
        Set<String> source = new HashSet<>(Set.of("application/pdf"));
        FileUploadPolicy policy = new FileUploadPolicy(1024, source);

        source.add("image/png");

        assertThat(policy.allowedTypes()).containsExactly("application/pdf");
        assertThatThrownBy(() -> policy.allowedTypes().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allowsSizeWithinBoundOnly() {
        FileUploadPolicy policy = new FileUploadPolicy(1024, Set.of("text/csv"));

        assertThat(policy.allowsSize(0)).isTrue();
        assertThat(policy.allowsSize(1024)).isTrue();
        assertThat(policy.allowsSize(1025)).isFalse();
        assertThat(policy.allowsSize(-1)).isFalse();
    }

    @Test
    void allowsOnlyListedTypes() {
        FileUploadPolicy policy = new FileUploadPolicy(1024, Set.of("application/pdf"));

        assertThat(policy.allowsType("application/pdf")).isTrue();
        assertThat(policy.allowsType("image/png")).isFalse();
    }
}
