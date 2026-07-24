package ae.gov.dubaicustoms.platform.test.junit;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.messaging.testing.AutoConfigureTestTransport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ActiveProfiles;

/** Verifies the composed slice annotations carry the meta-annotations that define each slice. */
class PlatformSliceAnnotationsTest {

    @Test
    void platformTestBootsTheFullContextUnderTheTestProfile() {
        assertThat(PlatformTest.class.isAnnotationPresent(SpringBootTest.class)).isTrue();
        assertThat(profileOf(PlatformTest.class)).containsExactly("test");
    }

    @Test
    void messagingSliceWiresTheRecordingTransport() {
        assertThat(PlatformMessagingTest.class.isAnnotationPresent(AutoConfigureTestTransport.class)).isTrue();
        assertThat(PlatformMessagingTest.class.isAnnotationPresent(SpringBootTest.class)).isTrue();
    }

    @Test
    void dataSliceComposesDataJpaTest() {
        assertThat(PlatformDataTest.class.isAnnotationPresent(DataJpaTest.class)).isTrue();
    }

    @Test
    void webSliceAutoConfiguresMockMvc() {
        assertThat(PlatformWebTest.class.isAnnotationPresent(AutoConfigureMockMvc.class)).isTrue();
        assertThat(PlatformWebTest.class.isAnnotationPresent(SpringBootTest.class)).isTrue();
    }

    private static String[] profileOf(Class<?> annotationType) {
        ActiveProfiles profiles = AnnotatedElementUtils.getMergedAnnotation(annotationType, ActiveProfiles.class);
        return profiles == null ? new String[0] : profiles.value();
    }
}
