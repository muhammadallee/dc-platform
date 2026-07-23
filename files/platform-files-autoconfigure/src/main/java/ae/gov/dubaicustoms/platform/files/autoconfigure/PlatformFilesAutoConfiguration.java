package ae.gov.dubaicustoms.platform.files.autoconfigure;

import ae.gov.dubaicustoms.platform.core.report.CapabilityDescriptor;
import ae.gov.dubaicustoms.platform.files.ContentTypeValidator;
import ae.gov.dubaicustoms.platform.files.FileUploadPolicy;
import ae.gov.dubaicustoms.platform.files.autoconfigure.internal.MagicBytesContentTypeValidator;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * Activates when: FileUploadPolicy is on the classpath and dc.platform.files.enabled != false.
 * Backs off when: the user defines their own ContentTypeValidator / FileUploadPolicy / MultipartConfigElement.
 * Beans: contentTypeValidator — the default magic-byte sniffer (pdf/png/jpg/zip/csv/txt);
 *        defaultFileUploadPolicy — a FileUploadPolicy from dc.platform.files.* (size + allowed types);
 *        filesCapabilityDescriptor — one line in the startup capability banner;
 *        multipartConfigElement — (servlet app) the servlet multipart size limits.
 * Order: before Boot's MultipartAutoConfiguration so the platform's multipart limits win by default.
 */
@AutoConfiguration(
        beforeName = "org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration")
@ConditionalOnClass(FileUploadPolicy.class)
@ConditionalOnProperty(prefix = "dc.platform.files", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FilesProperties.class)
public class PlatformFilesAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ContentTypeValidator contentTypeValidator() {
        return new MagicBytesContentTypeValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    FileUploadPolicy defaultFileUploadPolicy(FilesProperties properties) {
        return new FileUploadPolicy(properties.maxFileSize().toBytes(), properties.allowedTypes());
    }

    @Bean
    CapabilityDescriptor filesCapabilityDescriptor() {
        return new CapabilityDescriptor("files", "ACTIVE", "magic-bytes");
    }

    /*
     * Servlet multipart size limits: only in a servlet web app. Contributed ahead of Boot's own
     * MultipartConfigElement (via beforeName above) so the platform defaults apply unless the
     * application defines its own element.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MultipartConfigElement.class)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class MultipartConfiguration {

        @Bean
        @ConditionalOnMissingBean
        MultipartConfigElement multipartConfigElement(FilesProperties properties) {
            return new MultipartConfigElement(
                    "", properties.maxFileSize().toBytes(), properties.maxRequestSize().toBytes(), 0);
        }
    }
}
