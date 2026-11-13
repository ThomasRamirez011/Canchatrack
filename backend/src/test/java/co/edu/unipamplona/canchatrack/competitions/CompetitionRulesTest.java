package co.edu.unipamplona.canchatrack.competitions;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.competitions.domain.CompetitionRules;
import co.edu.unipamplona.canchatrack.competitions.domain.Eligibility;
import co.edu.unipamplona.canchatrack.competitions.domain.FieldError;
import co.edu.unipamplona.canchatrack.competitions.domain.PlayerResultLine;

class CompetitionRulesTest {

    private static List<String> fields(List<FieldError> errors) {
        return errors.stream().map(FieldError::field).toList();
    }

    // ---------- elegibilidad (RN-06, RN-07) ----------

    @Test
    void aPlayerMeetingEveryRequirementIsEligible() {
        Eligibility eligibility = new Eligibility(true, true, true, true);
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.causes()).isEmpty();
        assertThat(eligibility.primaryCause()).isNull();
    }

    @Test
    void eachMissingRequirementMakesThePlayerIneligibleWithItsOwnCause() {
        assertThat(new Eligibility(false, true, true, true).causes()).containsExactly(Eligibility.NOT_ACTIVE);
        assertThat(new Eligibility(true, false, true, true).causes()).containsExactly(Eligibility.MEDICAL_CERTIFICATE_INVALID);
        assertThat(new Eligibility(true, true, false, true).causes()).containsExactly(Eligibility.AUTHORIZATION_INVALID);
        assertThat(new Eligibility(true, true, true, false).causes()).containsExactly(Eligibility.ARREARS);
    }

    @Test
    void severalFailuresAreListedInPriorityOrderAndThePrimaryCauseIsTheFirst() {
        Eligibility eligibility = new Eligibility(true, false, false, false);
        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.causes()).containsExactly(Eligibility.MEDICAL_CERTIFICATE_INVALID,
                Eligibility.AUTHORIZATION_INVALID, Eligibility.ARREARS);
        assertThat(eligibility.primaryCause()).isEqualTo(Eligibility.MEDICAL_CERTIFICATE_INVALID);
    }

    @Test
    void checksListEveryRequirementAndNeverHealthContent() {
        assertThat(new Eligibility(true, false, true, true).checks()).containsExactly(
                java.util.Map.entry("active", true), java.util.Map.entry("medicalCertificate", false),
                java.util.Map.entry("participationAuthorization", true), java.util.Map.entry("arrearsClear", true));
    }

    // ---------- torneos (RF-031) ----------

    @Test
    void tournamentNeedsANameAndCoherentDates() {
        assertThat(CompetitionRules.tournamentViolations("Liga Juvenil", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 1), null)).isEmpty();
        assertThat(CompetitionRules.tournamentViolations("Liga", null, null, null)).isEmpty();
        assertThat(fields(CompetitionRules.tournamentViolations("  ", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 3, 1), null)))
                .containsExactlyInAnyOrder("name", "endDate");
        assertThat(fields(CompetitionRules.tournamentViolations("x".repeat(151), null, null, "r".repeat(2001))))
                .containsExactlyInAnyOrder("name", "rules");
    }

    @Test
    void matchNeedsAnOpponent() {
        assertThat(CompetitionRules.opponentViolations("Club Rival")).isEmpty();
        assertThat(fields(CompetitionRules.opponentViolations(" "))).containsExactly("opponent");
        assertThat(fields(CompetitionRules.opponentViolations(null))).containsExactly("opponent");
        assertThat(fields(CompetitionRules.opponentViolations("x".repeat(151)))).containsExactly("opponent");
    }

    @Test
    void tournamentMatchMustFitTheTournamentCategoryAndDates() {
        UUID category = UUID.randomUUID();
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 6, 1);
        assertThat(CompetitionRules.tournamentFitViolations(category, category, LocalDate.of(2026, 4, 1), start, end)).isEmpty();
        assertThat(CompetitionRules.tournamentFitViolations(category, null, LocalDate.of(2026, 4, 1), null, null)).isEmpty();
        assertThat(fields(CompetitionRules.tournamentFitViolations(category, UUID.randomUUID(), LocalDate.of(2026, 4, 1), start, end)))
                .containsExactly("categoryId");
        assertThat(fields(CompetitionRules.tournamentFitViolations(category, category, LocalDate.of(2026, 2, 28), start, end)))
                .containsExactly("startAt");
        assertThat(fields(CompetitionRules.tournamentFitViolations(category, category, LocalDate.of(2026, 6, 2), start, end)))
                .containsExactly("startAt");
    }

    // ---------- convocatoria (RF-033) ----------

    @Test
    void squadMustHavePlayersWithoutNullsOrDuplicates() {
        UUID a = UUID.randomUUID();
        assertThat(CompetitionRules.squadViolations(List.of(a, UUID.randomUUID()))).isEmpty();
        assertThat(fields(CompetitionRules.squadViolations(null))).containsExactly("playerIds");
        assertThat(fields(CompetitionRules.squadViolations(List.of()))).containsExactly("playerIds");
        assertThat(fields(CompetitionRules.squadViolations(List.of(a, a)))).containsExactly("playerIds[1]");
        List<UUID> withNull = new ArrayList<>();
        withNull.add(a);
        withNull.add(null);
        assertThat(fields(CompetitionRules.squadViolations(withNull))).containsExactly("playerIds[1]");
    }

    @Test
    void squadHasAnUpperBound() {
        List<UUID> tooMany = new ArrayList<>();
        for (int i = 0; i <= CompetitionRules.MAX_SQUAD; i++) {
            tooMany.add(UUID.randomUUID());
        }
        assertThat(fields(CompetitionRules.squadViolations(tooMany))).containsExactly("playerIds");
    }

    @Test
    void callupsAreOnlyOpenBeforeKickoffAndResultsOnlyAfter() {
        Instant start = Instant.parse("2026-06-01T15:00:00Z");
        assertThat(CompetitionRules.callupWindowOpen(start, start.minusSeconds(1))).isTrue();
        assertThat(CompetitionRules.callupWindowOpen(start, start)).isFalse();
        assertThat(CompetitionRules.resultWindowOpen(start, start.minusSeconds(1))).isFalse();
        assertThat(CompetitionRules.resultWindowOpen(start, start)).isTrue();
    }

    // ---------- resultado (RF-036) ----------

    @Test
    void validResultWithPlayersPasses() {
        UUID player = UUID.randomUUID();
        assertThat(CompetitionRules.resultViolations(3, 1, "Buen partido",
                List.of(new PlayerResultLine(player, 90, 2, "Doblete")), false, null)).isEmpty();
        assertThat(CompetitionRules.resultViolations(0, 0, null, List.of(), false, null)).isEmpty();
    }

    @Test
    void basketballScoresAboveOneHundredAreAccepted() {
        UUID player = UUID.randomUUID();
        assertThat(CompetitionRules.resultViolations(112, 98, null,
                List.of(new PlayerResultLine(player, 32, 41, null)), false, null)).isEmpty();
    }

    @Test
    void scoreIsRequiredAndBounded() {
        assertThat(fields(CompetitionRules.resultViolations(null, null, null, List.of(), false, null)))
                .containsExactlyInAnyOrder("scoreFor", "scoreAgainst");
        assertThat(fields(CompetitionRules.resultViolations(-1, 200, null, List.of(), false, null)))
                .containsExactlyInAnyOrder("scoreFor", "scoreAgainst");
    }

    @Test
    void minutesGoalsAndObservationsAreBoundedPerPlayer() {
        UUID player = UUID.randomUUID();
        assertThat(fields(CompetitionRules.resultViolations(5, 0, null, List.of(
                new PlayerResultLine(player, 151, 200, "o".repeat(1001))), false, null)))
                .containsExactlyInAnyOrder("players[0].minutesPlayed", "players[0].goals", "players[0].observations");
        assertThat(fields(CompetitionRules.resultViolations(5, 0, null, List.of(
                new PlayerResultLine(player, -1, -1, null)), false, null)))
                .containsExactlyInAnyOrder("players[0].minutesPlayed", "players[0].goals");
    }

    @Test
    void individualGoalsCannotExceedTheTeamGoals() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(CompetitionRules.resultViolations(3, 0, null, List.of(
                new PlayerResultLine(a, 90, 2, null), new PlayerResultLine(b, 45, 1, null)), false, null)).isEmpty();
        assertThat(fields(CompetitionRules.resultViolations(2, 0, null, List.of(
                new PlayerResultLine(a, 90, 2, null), new PlayerResultLine(b, 45, 1, null)), false, null)))
                .containsExactly("players");
    }

    @Test
    void playersCannotBeRepeatedOrMissing() {
        UUID a = UUID.randomUUID();
        assertThat(fields(CompetitionRules.resultViolations(1, 0, null, List.of(
                new PlayerResultLine(a, 90, 0, null), new PlayerResultLine(a, 10, 0, null)), false, null)))
                .containsExactly("players[1].playerId");
        assertThat(fields(CompetitionRules.resultViolations(1, 0, null, List.of(
                new PlayerResultLine(null, 90, 0, null)), false, null))).containsExactly("players[0].playerId");
    }

    @Test
    void correctingARecordedResultRequiresAReason() {
        assertThat(fields(CompetitionRules.resultViolations(2, 1, null, List.of(), true, " "))).containsExactly("reason");
        assertThat(fields(CompetitionRules.resultViolations(2, 1, null, List.of(), true, "r".repeat(2001)))).containsExactly("reason");
        assertThat(CompetitionRules.resultViolations(2, 1, null, List.of(), true, "Error al transcribir el acta")).isEmpty();
        assertThat(CompetitionRules.resultViolations(2, 1, null, List.of(), false, null)).isEmpty();
    }
}
