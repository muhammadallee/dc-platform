package ae.gov.dubaicustoms.platform.files;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Guards the sanitiser against path traversal, null bytes, and hostile names. */
class SafeFilenameTest {

    @Test
    void stripsDirectoryComponentsDefeatingTraversal() {
        assertThat(SafeFilename.sanitize("../../etc/passwd")).isEqualTo("passwd");
        assertThat(SafeFilename.sanitize("..\\..\\windows\\system32\\cmd.exe")).isEqualTo("cmd.exe");
        assertThat(SafeFilename.sanitize("/absolute/path/file.txt")).isEqualTo("file.txt");
    }

    @Test
    void removesNullBytesAndControlCharacters() {
        String withNullByte = "evil" + (char) 0 + ".txt";
        String withTab = "tab" + (char) 9 + "tab.csv";

        assertThat(SafeFilename.sanitize(withNullByte)).isEqualTo("evil.txt");
        assertThat(SafeFilename.sanitize(withTab)).isEqualTo("tabtab.csv");
    }

    @Test
    void replacesUnsafeCharactersWithUnderscore() {
        assertThat(SafeFilename.sanitize("my report (v2).pdf")).isEqualTo("my_report__v2_.pdf");
    }

    @Test
    void dropsLeadingDotsSoResultIsNeverDotOrDotDot() {
        assertThat(SafeFilename.sanitize("..")).isEqualTo("unnamed");
        assertThat(SafeFilename.sanitize(".")).isEqualTo("unnamed");
        assertThat(SafeFilename.sanitize(".bashrc")).isEqualTo("bashrc");
    }

    @Test
    void nullOrBlankBecomesFallback() {
        assertThat(SafeFilename.sanitize(null)).isEqualTo("unnamed");
        assertThat(SafeFilename.sanitize("   ")).isEqualTo("unnamed");
        assertThat(SafeFilename.sanitize("")).isEqualTo("unnamed");
    }

    @Test
    void boundsLength() {
        String longName = "a".repeat(500) + ".txt";

        String sanitized = SafeFilename.sanitize(longName);

        assertThat(sanitized).hasSize(255);
        assertThat(sanitized).endsWith(".txt");
    }
}
