package co.edu.unipamplona.canchatrack.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BackupCodesTest {

    @Test
    void generatesDistinctCodesInTheDocumentedFormat() {
        List<String> codes = BackupCodes.generate(10);

        assertThat(codes).hasSize(10).doesNotHaveDuplicates()
                .allMatch(code -> code.matches("[A-HJKMNP-Z2-9]{5}-[A-HJKMNP-Z2-9]{5}"));
    }

    @Test
    void neverUsesCharactersThatAreEasyToConfuse() {
        for (int i = 0; i < 200; i++) {
            BackupCodes.generate(5).forEach(code -> assertThat(code.matches(".*[01ILO].*")).as(code).isFalse());
        }
    }

    @Test
    void normalizesCaseSpacesAndDashes() {
        assertThat(BackupCodes.normalize(" abcde-fghjk ")).isEqualTo("ABCDEFGHJK");
        assertThat(BackupCodes.normalize("ab cde fghjk")).isEqualTo("ABCDEFGHJK");
        assertThat(BackupCodes.normalize(null)).isEqualTo("");
    }

    @Test
    void recognizesTheShapeOfABackupCode() {
        assertThat(BackupCodes.looksLikeBackupCode("abcde-fghjk")).isTrue();
        assertThat(BackupCodes.looksLikeBackupCode("ABCDEFGHJK")).isTrue();
        assertThat(BackupCodes.looksLikeBackupCode("123456")).isFalse();
        assertThat(BackupCodes.looksLikeBackupCode("ABCDE-FGH0K")).as("el 0 no existe en el alfabeto").isFalse();
        assertThat(BackupCodes.looksLikeBackupCode("ABCDE-FGHJ")).isFalse();
        assertThat(BackupCodes.looksLikeBackupCode(null)).isFalse();
    }
}
