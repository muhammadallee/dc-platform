package ae.gov.dubaicustoms.platform.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class RequiresPermissionTest {

    @RequiresPermission("orders:read")
    void annotated() {
    }

    @Test
    void carriesThePermissionValue() throws NoSuchMethodException {
        Method method = RequiresPermissionTest.class.getDeclaredMethod("annotated");

        RequiresPermission annotation = method.getAnnotation(RequiresPermission.class);

        assertThat(annotation.value()).isEqualTo("orders:read");
    }

    @Test
    void isRuntimeRetainedForMethodAndType() {
        Annotation[] annotations = RequiresPermission.class.getAnnotations();

        assertThat(RequiresPermission.class.getAnnotation(java.lang.annotation.Retention.class).value())
                .isEqualTo(java.lang.annotation.RetentionPolicy.RUNTIME);
        assertThat(annotations).isNotEmpty();
    }
}
