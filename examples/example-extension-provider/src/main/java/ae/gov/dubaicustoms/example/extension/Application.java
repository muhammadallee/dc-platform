package ae.gov.dubaicustoms.example.extension;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A service that adds a custom storage provider without touching the platform. On startup the
 * {@code ObjectStore} bean is the {@link EncryptingFsObjectStore}, and the platform's filesystem
 * default has backed off — verified by {@code StorageBackOffTest}.
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
