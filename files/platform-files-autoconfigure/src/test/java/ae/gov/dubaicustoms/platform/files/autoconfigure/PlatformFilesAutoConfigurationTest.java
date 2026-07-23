package ae.gov.dubaicustoms.platform.files.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.files.ContentTypeValidator;
import ae.gov.dubaicustoms.platform.files.FileUploadPolicy;
import ae.gov.dubaicustoms.platform.files.autoconfigure.internal.MagicBytesContentTypeValidator;
import jakarta.servlet.MultipartConfigElement;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/** ContextRunner matrix plus default policy, validator selection, and servlet multipart wiring. */
class PlatformFilesAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformFilesAutoConfiguration.class));

    @Test
    void activeByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ContentTypeValidator.class);
            assertThat(ctx.getBean(ContentTypeValidator.class)).isInstanceOf(MagicBytesContentTypeValidator.class);
            assertThat(ctx).hasSingleBean(FileUploadPolicy.class);
            assertThat(ctx.getBean(FileUploadPolicy.class).maxSizeBytes()).isEqualTo(10L * 1024 * 1024);
            assertThat(ctx).hasBean("filesCapabilityDescriptor");
        });
    }

    @Test
    void killSwitchDisables() {
        runner.withPropertyValues("dc.platform.files.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ContentTypeValidator.class);
            assertThat(ctx).doesNotHaveBean(FileUploadPolicy.class);
        });
    }

    @Test
    void backsOffWhenUserValidatorPresent() {
        ContentTypeValidator mine = content -> Optional.of("application/x-custom");
        runner.withBean("mine", ContentTypeValidator.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(ContentTypeValidator.class)).isSameAs(mine));
    }

    @Test
    void backsOffWhenUserPolicyPresent() {
        FileUploadPolicy mine = new FileUploadPolicy(42, Set.of("image/png"));
        runner.withBean("mine", FileUploadPolicy.class, () -> mine)
                .run(ctx -> assertThat(ctx.getBean(FileUploadPolicy.class)).isSameAs(mine));
    }

    @Test
    void inactiveWhenApiClassMissing() {
        runner.withClassLoader(new FilteredClassLoader(FileUploadPolicy.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(PlatformFilesAutoConfiguration.class));
    }

    @Test
    void customPropertiesDriveThePolicyAndTypes() {
        runner.withPropertyValues(
                        "dc.platform.files.max-file-size=1MB",
                        "dc.platform.files.allowed-types=application/pdf")
                .run(ctx -> {
                    FileUploadPolicy policy = ctx.getBean(FileUploadPolicy.class);
                    assertThat(policy.maxSizeBytes()).isEqualTo(1024L * 1024);
                    assertThat(policy.allowedTypes()).containsExactly("application/pdf");
                });
    }

    @Test
    void multipartConfigContributedInServletAppWithConfiguredSizes() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformFilesAutoConfiguration.class))
                .withPropertyValues("dc.platform.files.max-file-size=3MB",
                        "dc.platform.files.max-request-size=7MB")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MultipartConfigElement.class);
                    MultipartConfigElement element = ctx.getBean(MultipartConfigElement.class);
                    assertThat(element.getMaxFileSize()).isEqualTo(3L * 1024 * 1024);
                    assertThat(element.getMaxRequestSize()).isEqualTo(7L * 1024 * 1024);
                });
    }

    @Test
    void multipartConfigAbsentInNonServletApp() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(MultipartConfigElement.class));
    }
}
