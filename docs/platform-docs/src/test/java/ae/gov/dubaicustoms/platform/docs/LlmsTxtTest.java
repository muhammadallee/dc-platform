package ae.gov.dubaicustoms.platform.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies llms.txt / llms-full.txt are generated from the nav with Diátaxis labels and real content. */
class LlmsTxtTest {

    @Test
    void generatesLlmsTxtAndFullText() throws IOException {
        Path llms = LlmsTxtGenerator.generate();
        assertThat(llms).exists();

        String index = Files.readString(llms);
        assertThat(index)
                .contains("# DC Platform")
                .contains("> Internal Spring Boot chassis")
                .contains("(how-to + reference)")       // Capabilities Diátaxis label
                .contains("(explanation)")               // Concepts Diátaxis label
                .contains("[Messaging](modules/messaging.md)");

        Path full = llms.getParent().resolve("llms-full.txt");
        assertThat(full).exists();
        String fullText = Files.readString(full);
        assertThat(fullText)
                .contains("full documentation")
                .contains("<!-- source: modules/messaging.md -->")
                .contains("EventPublisher"); // real page content was inlined
    }
}
