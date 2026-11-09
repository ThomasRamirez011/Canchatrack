package co.edu.unipamplona.canchatrack.documents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.documents.domain.FileRules;

class FileRulesTest {

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D};

    @Test
    void aPdfWithTheRightSignaturePasses() {
        assertThat(FileRules.violations("recibo.pdf", "application/pdf", 100, 1000, PDF)).isEmpty();
    }

    @Test
    void contentThatDoesNotMatchTheDeclaredTypeIsRejected() {
        assertThat(FileRules.violations("recibo.pdf", "application/pdf", 100, 1000, PNG)).isNotEmpty();
    }

    @Test
    void unsupportedTypesEmptyAndOversizedFilesAreRejected() {
        assertThat(FileRules.violations("a.exe", "application/x-msdownload", 100, 1000, PDF)).isNotEmpty();
        assertThat(FileRules.violations("a.pdf", "application/pdf", 0, 1000, new byte[0])).isNotEmpty();
        assertThat(FileRules.violations("a.pdf", "application/pdf", 2000, 1000, PDF)).isNotEmpty();
    }

    @Test
    void namesAreSanitizedAndContentTypeParametersDropped() {
        assertThat(FileRules.sanitizeName("../../etc/pass wd.pdf")).doesNotContain("/").doesNotContain("..");
        assertThat(FileRules.normalizeContentType("Application/PDF; charset=binary")).isEqualTo("application/pdf");
    }
}
