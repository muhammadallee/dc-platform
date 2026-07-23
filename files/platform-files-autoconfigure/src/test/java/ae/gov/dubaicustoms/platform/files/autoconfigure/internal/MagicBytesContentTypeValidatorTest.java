package ae.gov.dubaicustoms.platform.files.autoconfigure.internal;

import static org.assertj.core.api.Assertions.assertThat;

import ae.gov.dubaicustoms.platform.files.FileUploadPolicy;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Magic-byte sniffing for the v1 content-type set, plus the policy allow-check. */
class MagicBytesContentTypeValidatorTest {

    private final MagicBytesContentTypeValidator validator = new MagicBytesContentTypeValidator();

    @Test
    void sniffsPdf() {
        assertThat(validator.sniff("%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII)))
                .contains("application/pdf");
    }

    @Test
    void sniffsPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};
        assertThat(validator.sniff(png)).contains("image/png");
    }

    @Test
    void sniffsJpeg() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00};
        assertThat(validator.sniff(jpeg)).contains("image/jpeg");
    }

    @Test
    void sniffsZip() {
        byte[] zip = {'P', 'K', 0x03, 0x04, 0x14, 0x00};
        assertThat(validator.sniff(zip)).contains("application/zip");
    }

    @Test
    void sniffsCsvByCommaInFirstLine() {
        assertThat(validator.sniff("name,age\nalice,30\n".getBytes(StandardCharsets.UTF_8)))
                .contains("text/csv");
    }

    @Test
    void sniffsPlainTextWithoutCommas() {
        assertThat(validator.sniff("just some prose here\nsecond line".getBytes(StandardCharsets.UTF_8)))
                .contains("text/plain");
    }

    @Test
    void allowsUtf8MultiByteText() {
        assertThat(validator.sniff("café résumé".getBytes(StandardCharsets.UTF_8)))
                .contains("text/plain");
    }

    @Test
    void unknownBinaryIsEmpty() {
        byte[] binary = {0x00, 0x01, 0x02, 0x03};
        assertThat(validator.sniff(binary)).isEmpty();
    }

    @Test
    void nullOrEmptyIsEmpty() {
        assertThat(validator.sniff(null)).isEmpty();
        assertThat(validator.sniff(new byte[0])).isEmpty();
    }

    @Test
    void permitsOnlyWhenSniffedTypeIsOnPolicyAllowList() {
        byte[] pdf = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        FileUploadPolicy allowsPdf = new FileUploadPolicy(1024, Set.of("application/pdf"));
        FileUploadPolicy allowsPngOnly = new FileUploadPolicy(1024, Set.of("image/png"));

        assertThat(validator.permits(pdf, allowsPdf)).isTrue();
        assertThat(validator.permits(pdf, allowsPngOnly)).isFalse();
    }
}
