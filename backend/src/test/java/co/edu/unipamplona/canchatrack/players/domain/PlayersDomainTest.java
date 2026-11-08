package co.edu.unipamplona.canchatrack.players.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlayersDomainTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);
    private static final UUID EXCEPTION = UUID.randomUUID();

    private static CallInfo call(CallStatus status, boolean windowOpen) {
        return new CallInfo(UUID.randomUUID(), "C", status, Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, windowOpen);
    }

    // ---------- admisión (RN-01, RF-044) ----------

    @Test
    void anOpenPublishedCallAdmitsWithoutException() {
        Admission admission = Admission.decide(call(CallStatus.PUBLISHED, true), Optional.of(EXCEPTION));

        assertThat(admission.admitted()).isTrue();
        assertThat(admission.exceptionId()).isNull(); // si la convocatoria está abierta no se consume la habilitación
    }

    @Test
    void aClosedWindowNeedsAnActiveException() {
        assertThat(Admission.decide(call(CallStatus.PUBLISHED, false), Optional.empty()).admitted()).isFalse();
        Admission withException = Admission.decide(call(CallStatus.PUBLISHED, false), Optional.of(EXCEPTION));
        assertThat(withException.admitted()).isTrue();
        assertThat(withException.exceptionId()).isEqualTo(EXCEPTION);
    }

    @Test
    void unpublishedOrCancelledCallsNeverAdmit() {
        assertThat(Admission.decide(call(CallStatus.DRAFT, true), Optional.of(EXCEPTION)).admitted()).isFalse();
        assertThat(Admission.decide(call(CallStatus.CANCELLED, true), Optional.of(EXCEPTION)).admitted()).isFalse();
    }

    // ---------- estados ----------

    @Test
    void onlyEndedProcessesAllowANewEnrollment() {
        for (PlayerStatus status : PlayerStatus.values()) {
            boolean expected = status == PlayerStatus.REJECTED || status == PlayerStatus.SEASON_CLOSED;
            assertThat(status.allowsNewEnrollment()).as(status.name()).isEqualTo(expected);
        }
    }

    @Test
    void statusParsingIsExact() {
        assertThat(PlayerStatus.parse("ACTIVE")).contains(PlayerStatus.ACTIVE);
        assertThat(PlayerStatus.parse(" ACTIVE ")).contains(PlayerStatus.ACTIVE);
        assertThat(PlayerStatus.parse("active")).isEmpty();
        assertThat(PlayerStatus.parse(null)).isEmpty();
    }

    // ---------- datos del jugador ----------

    @Test
    void validDataHasNoViolations() {
        assertThat(PlayerDataRules.violations("María José", "O'Neill-Pérez", "ti", "TI-1001", LocalDate.of(2014, 3, 9), TODAY))
                .isEmpty();
    }

    @Test
    void namesMustStartWithALetterAndHaveNoDigitsOrSymbols() {
        assertThat(PlayerDataRules.violations("1Juan", "Pérez", "TI", "TI-1001", LocalDate.of(2014, 3, 9), TODAY))
                .extracting(FieldError::field).containsExactly("player.firstName");
        assertThat(PlayerDataRules.violations("Juan", "Pérez<script>", "TI", "TI-1001", LocalDate.of(2014, 3, 9), TODAY))
                .extracting(FieldError::field).containsExactly("player.lastName");
        assertThat(PlayerDataRules.violations(" ", null, "TI", "TI-1001", LocalDate.of(2014, 3, 9), TODAY))
                .extracting(FieldError::field).containsExactly("player.firstName", "player.lastName");
    }

    @Test
    void birthDateMustBePlausible() {
        assertThat(PlayerDataRules.violations("Juan", "Pérez", "TI", "TI-1001", null, TODAY)).hasSize(1);
        assertThat(PlayerDataRules.violations("Juan", "Pérez", "TI", "TI-1001", TODAY.plusDays(1), TODAY)).hasSize(1);
        assertThat(PlayerDataRules.violations("Juan", "Pérez", "TI", "TI-1001", TODAY.minusYears(101), TODAY)).hasSize(1);
        assertThat(PlayerDataRules.violations("Juan", "Pérez", "TI", "TI-1001", TODAY, TODAY)).isEmpty();
    }

    @Test
    void documentTypeAndNumberAreValidated() {
        assertThat(PlayerDataRules.violations("Juan", "Pérez", "XX", "abc", LocalDate.of(2014, 3, 9), TODAY))
                .extracting(FieldError::field).containsExactly("player.documentType", "player.documentNumber");
    }
}
