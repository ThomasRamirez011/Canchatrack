package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.reports.application.SeasonReportFacts;
import co.edu.unipamplona.canchatrack.reports.domain.ReportRules;

/** Reglas puras del informe de temporada por jugador y del cierre del expediente (CU-10, CU-11). */
class SeasonReportRulesTest {

    @Test
    void reportNumberIsPaddedAndKeepsGrowingPastSixDigits() {
        assertThat(ReportRules.reportNumber(1)).isEqualTo("IT-000001");
        assertThat(ReportRules.reportNumber(12)).isEqualTo("IT-000012");
        assertThat(ReportRules.reportNumber(1234567)).isEqualTo("IT-1234567");
    }

    @Test
    void verificationCodeHasThreeGroupsOfFourWithoutConfusableCharacters() {
        AtomicInteger next = new AtomicInteger();
        String code = ReportRules.verificationCode(bound -> next.getAndAdd(5) % bound);
        assertThat(code).matches("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}");
        assertThat(code).doesNotContain("0", "O", "1", "I");
    }

    @Test
    void verificationCodeUsesTheWholeAlphabetRange() {
        // La fuente de azar recibe el tamaño del alfabeto (32) y devuelve el último símbolo: «9».
        assertThat(ReportRules.verificationCode(bound -> bound - 1)).isEqualTo("9999-9999-9999");
        assertThat(ReportRules.verificationCode(bound -> 0)).isEqualTo("AAAA-AAAA-AAAA");
    }

    @Test
    void normalizeAcceptsHandwrittenCodesAndRejectsAnythingElse() {
        assertThat(ReportRules.normalizeVerificationCode("abcd-efgh-jklm")).isEqualTo("ABCD-EFGH-JKLM");
        assertThat(ReportRules.normalizeVerificationCode(" abcd efgh jklm ")).isEqualTo("ABCD-EFGH-JKLM");
        assertThat(ReportRules.normalizeVerificationCode("ABCDEFGHJKLM")).isEqualTo("ABCD-EFGH-JKLM");
        assertThat(ReportRules.normalizeVerificationCode("ABCD-EFGH-JKL")).isNull();       // corto
        assertThat(ReportRules.normalizeVerificationCode("ABCD-EFGH-JKL0")).isNull();      // 0 no existe en el alfabeto
        assertThat(ReportRules.normalizeVerificationCode("ABCD-EFGH-JKLMN")).isNull();     // largo
        assertThat(ReportRules.normalizeVerificationCode("' OR 1=1 --")).isNull();
        assertThat(ReportRules.normalizeVerificationCode(null)).isNull();
    }

    @Test
    void periodEndIsTodayWhileTheSeasonIsRunningAndTheSeasonEndAfterwards() {
        Instant end = Instant.parse("2026-12-15T05:00:00Z");
        assertThat(ReportRules.periodEnd(end, Instant.parse("2026-11-12T20:00:00Z"))).isEqualTo(Instant.parse("2026-11-12T20:00:00Z"));
        assertThat(ReportRules.periodEnd(end, Instant.parse("2027-02-01T00:00:00Z"))).isEqualTo(end);
        assertThat(ReportRules.periodEnd(end, end)).isEqualTo(end);
    }

    @Test
    void onlyPlayersWithATrackRecordCanGetAReport() {
        for (String status : List.of("ACTIVE", "IN_EVALUATION", "PROMOTION_APPROVED", "SUSPENDED", "WITHDRAWN", "SEASON_CLOSED")) {
            assertThat(ReportRules.isReportablePlayerState(status)).as(status).isTrue();
        }
        for (String status : List.of("SUBMITTED", "COMMITTEE_PENDING", "CATEGORY_ASSIGNED", "PENDING_REGISTRATION", "REJECTED", "", "x")) {
            assertThat(ReportRules.isReportablePlayerState(status)).as(status).isFalse();
        }
    }

    @Test
    void reopenConfirmationIsThePlayerCodeIgnoringCaseAndSpaces() {
        assertThat(ReportRules.confirmationMatches("JG-000012", "jg-000012")).isTrue();
        assertThat(ReportRules.confirmationMatches("JG-000012", "  JG-000012 ")).isTrue();
        assertThat(ReportRules.confirmationMatches("JG-000012", "JG-000013")).isFalse();
        assertThat(ReportRules.confirmationMatches("JG-000012", "")).isFalse();
        assertThat(ReportRules.confirmationMatches("JG-000012", null)).isFalse();
        assertThat(ReportRules.confirmationMatches(null, "JG-000012")).isFalse();
    }

    @Test
    void requiredTextProblemRejectsBlankAndOverlongText() {
        assertThat(ReportRules.requiredTextProblem("Motivo válido", 20)).isNull();
        assertThat(ReportRules.requiredTextProblem(null, 20)).isNotNull();
        assertThat(ReportRules.requiredTextProblem("   ", 20)).isNotNull();
        assertThat(ReportRules.requiredTextProblem("x".repeat(21), 20)).contains("20");
        assertThat(ReportRules.requiredTextProblem("  " + "x".repeat(20) + "  ", 20)).isNull(); // los espacios no cuentan
    }

    @Test
    void factsTotalTheParticipationAndFreezeANullFreeSnapshot() {
        var season = new SeasonReportFacts.Season(UUID.randomUUID(), "2030", "ACTIVE",
                Instant.parse("2030-01-01T05:00:00Z"), Instant.parse("2030-12-01T05:00:00Z"));
        var player = new SeasonReportFacts.Player(UUID.randomUUID(), "JG-000001", "Ana Gómez", LocalDate.of(2014, 3, 2),
                "ACTIVE", "Sub-12", null);
        var matches = List.of(
                new SeasonReportFacts.MatchLine(Instant.parse("2030-03-01T15:00:00Z"), "Rival A", null, 2, 1, 60, 1),
                new SeasonReportFacts.MatchLine(Instant.parse("2030-03-08T15:00:00Z"), "Rival B", "Copa", 0, 0, null, null),
                new SeasonReportFacts.MatchLine(Instant.parse("2030-03-15T15:00:00Z"), "Rival C", "Copa", 1, 3, 45, 2));
        var facts = SeasonReportFacts.of(season, player,
                new SeasonReportFacts.Period(season.startAt(), season.endAt(), false),
                new SeasonReportFacts.Attendance(10, 7, 1, 1, 0, 1, new BigDecimal("80.0")), List.of(), matches);

        assertThat(facts.participation()).isEqualTo(new SeasonReportFacts.Participation(3, 105, 3));

        Map<String, Object> snapshot = facts.toSnapshot();
        assertThat(snapshot.keySet()).containsExactly("season", "player", "period", "attendance", "evaluations", "matches",
                "participation");
        @SuppressWarnings("unchecked")
        Map<String, Object> savedPlayer = (Map<String, Object>) snapshot.get("player");
        assertThat(savedPlayer).containsEntry("code", "JG-000001").containsEntry("birthDate", "2014-03-02")
                .doesNotContainKey("headCoach") // los nulos no se guardan
                .doesNotContainKey("documentNumber");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedMatches = (List<Map<String, Object>>) snapshot.get("matches");
        assertThat(savedMatches).hasSize(3);
        assertThat(savedMatches.get(1)).doesNotContainKeys("minutes", "goals");
    }
}
