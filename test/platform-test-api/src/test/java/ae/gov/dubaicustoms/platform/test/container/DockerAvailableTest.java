package ae.gov.dubaicustoms.platform.test.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

class DockerAvailableTest {

    @Test
    void checkNeverThrowsAndIsStable() {
        assertThatCode(DockerAvailable::check).doesNotThrowAnyException();
        // Whatever the environment reports, the cached result must be consistent across calls.
        assertThat(DockerAvailable.check()).isEqualTo(DockerAvailable.check());
    }
}
