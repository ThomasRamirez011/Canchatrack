package co.edu.unipamplona.canchatrack.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.documents.domain.DocumentRules;
import co.edu.unipamplona.canchatrack.documents.domain.DocumentRules.Status;
import co.edu.unipamplona.canchatrack.documents.domain.FieldError;

class DocumentRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);
    private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
    private static final List<String> SUPPORTED = List.of("application/pdf", "image/png", "image/jpeg");
    private static final long SYSTEM_MAX = 10L * 1024 * 1024;

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    // ---------- estado y alertas ----------

    @Test
    void statusTellsMissingValidExpiringAndExpired() {
        assertThat(DocumentRules.statusOf(false, null, TODAY)).isEqualTo(Status.MISSING);
        assertThat(DocumentRules.statusOf(true, null, TODAY)).isEqualTo(Status.VALID);
        assertThat(DocumentRules.statusOf(true, TODAY.plusDays(31), TODAY)).isEqualTo(Status.VALID);
        assertThat(DocumentRules.statusOf(true, TODAY.plusDays(30), TODAY)).isEqualTo(Status.EXPIRING_SOON);
        assertThat(DocumentRules.statusOf(true, TODAY, TODAY)).isEqualTo(Status.EXPIRING_SOON);
        assertThat(DocumentRules.statusOf(true, TODAY.minusDays(1), TODAY)).isEqualTo(Status.EXPIRED);
    }

    @Test
    void alertStagesAre30And7DaysAndNothingOutsideTheWindow() {
        assertThat(DocumentRules.alertStage(TODAY.plusDays(31), TODAY)).isEqualTo(OptionalInt.empty());
        assertThat(DocumentRules.alertStage(TODAY.plusDays(30), TODAY)).isEqualTo(OptionalInt.of(30));
        assertThat(DocumentRules.alertStage(TODAY.plusDays(8), TODAY)).isEqualTo(OptionalInt.of(30));
        assertThat(DocumentRules.alertStage(TODAY.plusDays(7), TODAY)).isEqualTo(OptionalInt.of(7));
        assertThat(DocumentRules.alertStage(TODAY, TODAY)).isEqualTo(OptionalInt.of(7));
        assertThat(DocumentRules.alertStage(TODAY.minusDays(1), TODAY)).isEqualTo(OptionalInt.empty());
        assertThat(DocumentRules.alertStage(null, TODAY)).isEqualTo(OptionalInt.empty());
    }

    // ---------- quién puede cargar (RN-05) ----------

    @Test
    void theFirstVersionIsAlwaysAllowedAndLaterOnesNeedAWindowOrAnExpiry() {
        assertThat(DocumentRules.guardianMayUpload(false, null, false, TODAY)).isTrue();
        assertThat(DocumentRules.guardianMayUpload(true, TODAY.plusDays(200), false, TODAY)).isFalse();
        assertThat(DocumentRules.guardianMayUpload(true, null, false, TODAY)).isFalse();
        assertThat(DocumentRules.guardianMayUpload(true, TODAY.plusDays(200), true, TODAY)).isTrue();
        assertThat(DocumentRules.guardianMayUpload(true, TODAY.plusDays(30), false, TODAY)).isTrue();
        assertThat(DocumentRules.guardianMayUpload(true, TODAY.plusDays(31), false, TODAY)).isFalse();
        assertThat(DocumentRules.guardianMayUpload(true, TODAY.minusDays(3), false, TODAY)).isTrue();
    }

    // ---------- versiones ----------

    @Test
    void expiryComesFromTheGivenDateOrFromTheTypeValidity() {
        assertThat(DocumentRules.resolveExpiry(true, 365, TODAY, TODAY.plusDays(10))).isEqualTo(TODAY.plusDays(10));
        assertThat(DocumentRules.resolveExpiry(true, 365, TODAY, null)).isEqualTo(TODAY.plusDays(365));
        assertThat(DocumentRules.resolveExpiry(true, 365, null, null)).isNull();
        assertThat(DocumentRules.resolveExpiry(false, 365, TODAY, null)).isNull();
        assertThat(DocumentRules.resolveExpiry(true, null, TODAY, null)).isNull();
    }

    @Test
    void aVersionOfADocumentThatExpiresNeedsAFutureExpiryAndNeverPrecedesTheIssueDate() {
        assertThat(DocumentRules.versionViolations(true, TODAY, TODAY.plusDays(100), null, TODAY)).isEmpty();
        assertThat(fields(DocumentRules.versionViolations(true, TODAY, null, null, TODAY))).containsExactly("expiresOn");
        assertThat(fields(DocumentRules.versionViolations(true, TODAY, TODAY.minusDays(1), null, TODAY))).containsExactly("expiresOn");
        assertThat(fields(DocumentRules.versionViolations(true, TODAY.minusDays(10), TODAY.minusDays(20), null, TODAY)))
                .containsExactly("expiresOn");
        assertThat(fields(DocumentRules.versionViolations(false, TODAY.plusDays(1), null, null, TODAY))).containsExactly("issuedOn");
        assertThat(DocumentRules.versionViolations(false, null, null, null, TODAY)).isEmpty();
        assertThat(fields(DocumentRules.versionViolations(false, null, null, "x".repeat(501), TODAY))).containsExactly("notes");
    }

    // ---------- catálogo y ventanas ----------

    @Test
    void typeCodesAreUpperSnakeCase() {
        assertThat(DocumentRules.codeViolations("EPS_CARD")).isEmpty();
        assertThat(DocumentRules.codeViolations("eps")).hasSize(1);
        assertThat(DocumentRules.codeViolations("1EPS")).hasSize(1);
        assertThat(DocumentRules.codeViolations("A")).hasSize(1);
        assertThat(DocumentRules.codeViolations(" ")).hasSize(1);
        assertThat(DocumentRules.codeViolations(null)).hasSize(1);
    }

    @Test
    void typeSettingsAreValidatedAgainstWhatTheSystemSupports() {
        assertThat(DocumentRules.typeViolations("Carné EPS", true, 365, 1024L, List.of("application/pdf"), SUPPORTED, SYSTEM_MAX)).isEmpty();
        assertThat(DocumentRules.typeViolations("Carné EPS", false, null, null, null, SUPPORTED, SYSTEM_MAX)).isEmpty();
        assertThat(fields(DocumentRules.typeViolations(" ", false, null, null, null, SUPPORTED, SYSTEM_MAX))).containsExactly("name");
        assertThat(fields(DocumentRules.typeViolations("X", false, 30, null, null, SUPPORTED, SYSTEM_MAX))).containsExactly("validityDays");
        assertThat(fields(DocumentRules.typeViolations("X", true, 0, null, null, SUPPORTED, SYSTEM_MAX))).containsExactly("validityDays");
        assertThat(fields(DocumentRules.typeViolations("X", true, 3651, null, null, SUPPORTED, SYSTEM_MAX))).containsExactly("validityDays");
        assertThat(fields(DocumentRules.typeViolations("X", false, null, SYSTEM_MAX + 1, null, SUPPORTED, SYSTEM_MAX)))
                .containsExactly("maxSizeBytes");
        assertThat(fields(DocumentRules.typeViolations("X", false, null, null, List.of("application/zip"), SUPPORTED, SYSTEM_MAX)))
                .containsExactly("allowedContentTypes");
        assertThat(fields(DocumentRules.typeViolations("X", false, null, null, List.of(), SUPPORTED, SYSTEM_MAX)))
                .containsExactly("allowedContentTypes");
    }

    @Test
    void aWindowMustHaveACoherentRangeThatHasNotAlreadyEnded() {
        assertThat(DocumentRules.windowViolations(NOW, NOW.plusSeconds(3600), "Renovación anual", NOW)).isEmpty();
        assertThat(fields(DocumentRules.windowViolations(null, null, null, NOW))).containsExactly("opensAt", "closesAt");
        assertThat(fields(DocumentRules.windowViolations(NOW.plusSeconds(60), NOW, null, NOW))).containsExactly("closesAt");
        assertThat(fields(DocumentRules.windowViolations(NOW.minusSeconds(7200), NOW.minusSeconds(3600), null, NOW)))
                .containsExactly("closesAt");
        assertThat(fields(DocumentRules.windowViolations(NOW, NOW.plusSeconds(60), "x".repeat(501), NOW))).containsExactly("reason");
    }

    // ---------- archivo frente al tipo ----------

    @Test
    void aFileWithinTheTypeLimitAndFormatHasNoViolations() {
        assertThat(DocumentRules.fileViolations("file", 1_000, "application/pdf", 2_000L, SYSTEM_MAX, List.of("application/pdf")))
                .isEmpty();
        assertThat(DocumentRules.fileViolations("file", 1_000, "image/png", null, SYSTEM_MAX, List.of())).isEmpty();
    }

    @Test
    void theLimitIsTheSmallerOfTheTypeAndTheSystemOne() {
        List<FieldError> overType = DocumentRules.fileViolations("fileId", 3_000, "application/pdf", 2_000L, SYSTEM_MAX, List.of());
        assertThat(fields(overType)).containsExactly("fileId");
        assertThat(overType.get(0).message()).contains("2000 bytes");

        // Un tipo que declara más que el sistema no puede superar al sistema.
        List<FieldError> overSystem = DocumentRules.fileViolations("fileId", SYSTEM_MAX + 1, "application/pdf", SYSTEM_MAX * 2, SYSTEM_MAX,
                List.of());
        assertThat(overSystem.get(0).message()).contains(SYSTEM_MAX + " bytes");
    }

    @Test
    void aFormatOutsideTheTypeListIsRejectedAndTheContentTypeIsNormalized() {
        List<FieldError> errors = DocumentRules.fileViolations("fileId", 10, "image/jpeg", null, SYSTEM_MAX, List.of("image/png"));
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("fileId");
            assertThat(e.message()).contains("image/png");
        });
        // Mayúsculas y parámetros del tipo declarado no cuentan.
        assertThat(DocumentRules.fileViolations("fileId", 10, "IMAGE/PNG; charset=binary", null, SYSTEM_MAX, List.of("image/png")))
                .isEmpty();
    }
}
