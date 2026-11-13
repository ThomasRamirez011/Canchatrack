package co.edu.unipamplona.canchatrack.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.Enqueued;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.FakeAudience;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.FakeGuardians;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.FakeRoster;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.FakeStore;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.RecordingQueue;

/** A quién y con qué datos se avisa cuando se programa, cancela o se acerca un entrenamiento (RF-016 a RF-019). */
class AgendaNoticesTest {

    private final UUID category = UUID.randomUUID();
    private final UUID coach = UUID.randomUUID();
    private final UUID player1 = UUID.randomUUID();
    private final UUID player2 = UUID.randomUUID();
    private final UUID guardianOfBoth = UUID.randomUUID();
    private final UUID guardianOfOne = UUID.randomUUID();

    private final FakeStore store = new FakeStore();
    private final FakeRoster roster = new FakeRoster();
    private final FakeGuardians guardians = new FakeGuardians();
    private final RecordingQueue queue = new RecordingQueue();
    private final FakeAudience audience = new FakeAudience();

    private AgendaNotices notices(String appUrl) {
        roster.byCategory.put(category, List.of(player1, player2));
        guardians.guardianByPlayer.put(player1, guardianOfBoth);
        guardians.guardianByPlayer.put(player2, guardianOfOne);
        return new AgendaNotices(store, roster, guardians, queue, new AgendaSettings(appUrl, List.of(2, 24)), audience);
    }

    private EventView event() {
        return AgendaFakes.training(category, coach, Instant.parse("2026-12-01T22:00:00Z"));
    }

    @Test
    void schedulingNotifiesEachGuardianOnceAndTheHeadCoach() {
        EventView event = event();

        notices("https://app.club.example").scheduled(event);

        assertThat(queue.sent).hasSize(1);
        Enqueued notice = queue.sent.get(0);
        assertThat(notice.type()).isEqualTo("EVENT_SCHEDULED");
        assertThat(notice.recipients()).containsExactly(guardianOfBoth, guardianOfOne, coach);
        assertThat(notice.relatedEntityType()).isEqualTo("sports_event");
        assertThat(notice.relatedEntityId()).isEqualTo(event.id());
        assertThat(notice.dedupScope()).isEqualTo("scheduled");
        assertThat(notice.subject()).isEqualTo("Entrenamiento programado · Sub-12");
    }

    @Test
    void thePayloadDescribesTheEventWithoutAnyPlayerData() {
        EventView event = event();

        notices("https://app.club.example").scheduled(event);

        var payload = queue.sent.get(0).payload();
        assertThat(payload.get("eventId")).isEqualTo(event.id().toString());
        assertThat(payload.get("categoryName")).isEqualTo("Sub-12");
        assertThat(payload.get("venueName")).isEqualTo("Cancha Municipal");
        assertThat(payload.get("venueAddress")).isEqualTo("Calle 5 # 3-20");
        assertThat(payload.get("coachName")).isEqualTo("Carlos Ruiz");
        assertThat(payload.get("startAt")).isEqualTo("2026-12-01T22:00:00Z");
        assertThat(payload.get("endAt")).isEqualTo("2026-12-01T23:30:00Z");
        assertThat(payload.get("instructions")).isEqualTo("Traer uniforme");
        assertThat(payload.get("appUrl")).isEqualTo("https://app.club.example");
        assertThat(payload.get("stamp")).isEqualTo(AgendaFakes.NOW.toString());
        assertThat(payload.containsKey("playerName")).isFalse();
        assertThat(payload.containsKey("meetingAt")).isFalse();
        assertThat(payload.containsKey("reason")).isFalse();
    }

    @Test
    void withoutAConfiguredUrlThePayloadHasNoLink() {
        notices("").scheduled(event());

        assertThat(queue.sent.get(0).payload().containsKey("appUrl")).isFalse();
    }

    @Test
    void cancellingCarriesTheReasonAndItsOwnDedupScope() {
        EventView cancelled = new EventView(event().id(), "TRAINING", category, UUID.randomUUID(), "Cancha Municipal", coach,
                Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-01T23:30:00Z"), null, null, null, "CANCELLED", "Lluvia");

        notices("").cancelled(cancelled);

        Enqueued notice = queue.sent.get(0);
        assertThat(notice.type()).isEqualTo("EVENT_CANCELLED");
        assertThat(notice.dedupScope()).isEqualTo("cancelled");
        assertThat(notice.payload().get("reason")).isEqualTo("Lluvia");
        assertThat(notice.subject()).isEqualTo("CANCELADO: entrenamiento · Sub-12");
    }

    @Test
    void cancellingCarriesTheEventRevisionAsTheCalendarSequence() {
        EventView cancelled = new EventView(event().id(), "TRAINING", category, UUID.randomUUID(), "Cancha Municipal", coach,
                Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-01T23:30:00Z"), null, null, null, "CANCELLED", "Lluvia");
        store.revisions.put(cancelled.id(), 3);

        notices("").cancelled(cancelled);

        assertThat(queue.sent.get(0).payload().get("sequence")).isEqualTo("3");
    }

    @Test
    void reschedulingAlsoNotifiesThePreviousCoachAndKeepsTheOldScheduleInThePayload() {
        UUID previousCoach = UUID.randomUUID();
        UUID newCoach = UUID.randomUUID();
        Instant oldStart = Instant.parse("2026-12-01T22:00:00Z");
        EventView before = new EventView(UUID.randomUUID(), "TRAINING", category, UUID.randomUUID(), "Cancha Municipal", previousCoach,
                oldStart, oldStart.plusSeconds(5400), null, null, "Traer uniforme", "CONFIRMED", null);
        Instant newStart = Instant.parse("2026-12-03T22:00:00Z");
        EventView after = new EventView(before.id(), "TRAINING", category, before.venueId(), "Cancha Municipal", newCoach,
                newStart, newStart.plusSeconds(5400), null, null, "Traer uniforme", "CONFIRMED", null);
        store.revisions.put(before.id(), 1);

        notices("https://app.club.example").rescheduled(before, after, "Cancha en mantenimiento");

        assertThat(queue.sent).hasSize(1);
        Enqueued notice = queue.sent.get(0);
        assertThat(notice.type()).isEqualTo("EVENT_RESCHEDULED");
        assertThat(notice.recipients()).containsExactly(guardianOfBoth, guardianOfOne, newCoach, previousCoach);
        assertThat(notice.dedupScope()).isEqualTo("rescheduled:1");
        assertThat(notice.subject()).isEqualTo("Reprogramado: entrenamiento · Sub-12");
        assertThat(notice.payload().get("startAt")).isEqualTo(newStart.toString());
        assertThat(notice.payload().get("previousStartAt")).isEqualTo(oldStart.toString());
        assertThat(notice.payload().get("previousEndAt")).isEqualTo(oldStart.plusSeconds(5400).toString());
        assertThat(notice.payload().get("reason")).isEqualTo("Cancha en mantenimiento");
        assertThat(notice.payload().get("sequence")).isEqualTo("1");
    }

    @Test
    void eachRescheduleHasItsOwnDedupScopeAndTheSameCoachIsNotNotifiedTwice() {
        EventView event = event();
        store.revisions.put(event.id(), 1);
        notices("").rescheduled(event, event, "Primero");
        store.revisions.put(event.id(), 2);
        notices("").rescheduled(event, event, "Segundo");

        assertThat(queue.sent.get(0).dedupScope()).isEqualTo("rescheduled:1");
        assertThat(queue.sent.get(1).dedupScope()).isEqualTo("rescheduled:2");
        assertThat(queue.sent.get(1).recipients()).containsExactly(guardianOfBoth, guardianOfOne, coach);
    }

    @Test
    void afterARescheduleTheReminderGetsANewScopeSoItGoesOutAgain() {
        EventView event = event();
        notices("").reminder(event, 24);
        store.revisions.put(event.id(), 2);
        notices("").reminder(event, 24);

        assertThat(queue.sent.get(0).dedupScope()).isEqualTo("reminder:24");
        assertThat(queue.sent.get(1).dedupScope()).isEqualTo("reminder:24:r2");
    }

    @Test
    void theReminderRecordsItsStageInTheDedupScopeAndPayload() {
        notices("").reminder(event(), 24);
        notices("").reminder(event(), 2);

        assertThat(queue.sent).hasSize(2);
        assertThat(queue.sent.get(0).dedupScope()).isEqualTo("reminder:24");
        assertThat(queue.sent.get(0).payload().get("hoursBefore")).isEqualTo("24");
        assertThat(queue.sent.get(1).dedupScope()).isEqualTo("reminder:2");
        assertThat(queue.sent.get(0).type()).isEqualTo("EVENT_REMINDER");
    }

    @Test
    void aCategoryWithoutPlayersStillNotifiesTheCoach() {
        AgendaNotices empty = new AgendaNotices(store, roster, guardians, queue, new AgendaSettings("", List.of(2, 24)), audience);

        empty.scheduled(event());

        assertThat(queue.sent.get(0).recipients()).containsExactly(coach);
    }

    @Test
    void noRecipientsMeansNothingIsQueued() {
        AgendaNotices empty = new AgendaNotices(store, roster, guardians, queue, new AgendaSettings("", List.of(2, 24)), audience);
        EventView withoutCoach = new EventView(UUID.randomUUID(), "TRAINING", category, UUID.randomUUID(), "Cancha", null,
                Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-01T23:30:00Z"), null, null, null, "CONFIRMED", null);

        empty.scheduled(withoutCoach);

        assertThat(queue.sent).isEmpty();
    }

    // ---------- partidos ----------

    private EventView aMatch() {
        store.context = new EventContext("Sub-12", "Calle 5 # 3-20", "Carlos Ruiz", "Club Rival");
        return AgendaFakes.match(category, coach, Instant.parse("2026-12-05T20:00:00Z"));
    }

    @Test
    void schedulingAMatchNotifiesOnlyTheCoachBecauseFamiliesHearAboutItFromTheCallup() {
        EventView match = aMatch();

        notices("").scheduled(match);

        assertThat(queue.sent).hasSize(1);
        Enqueued notice = queue.sent.get(0);
        assertThat(notice.type()).isEqualTo("EVENT_SCHEDULED");
        assertThat(notice.recipients()).containsExactly(coach);
        assertThat(notice.subject()).isEqualTo("Partido amistoso programado · Sub-12");
        assertThat(notice.payload().get("opponent")).isEqualTo("Club Rival");
    }

    @Test
    void cancellingAMatchNotifiesOnlyTheCoach() {
        EventView match = aMatch();

        notices("").cancelled(match);

        assertThat(queue.sent.get(0).recipients()).containsExactly(coach);
        assertThat(queue.sent.get(0).type()).isEqualTo("EVENT_CANCELLED");
    }

    @Test
    void reschedulingAMatchNotifiesBothCoachesAndNoFamily() {
        UUID previousCoach = UUID.randomUUID();
        EventView before = AgendaFakes.match(category, previousCoach, Instant.parse("2026-12-05T20:00:00Z"));
        EventView after = new EventView(before.id(), "FRIENDLY_MATCH", category, before.venueId(), "Cancha Municipal", coach,
                Instant.parse("2026-12-06T20:00:00Z"), Instant.parse("2026-12-06T21:30:00Z"), null, null, null, "CONFIRMED", null);
        store.revisions.put(before.id(), 1);

        notices("").rescheduled(before, after, "Cambio de cancha");

        assertThat(queue.sent.get(0).recipients()).containsExactly(coach, previousCoach);
    }

    @Test
    void aMatchReminderGoesToTheGuardiansOfTheConfirmedPlayersAndTheCoachOnly() {
        EventView match = aMatch();
        audience.confirmedGuardians.put(match.id(), java.util.Set.of(guardianOfOne));

        notices("").reminder(match, 48);

        Enqueued notice = queue.sent.get(0);
        assertThat(notice.type()).isEqualTo("EVENT_REMINDER");
        assertThat(notice.recipients()).containsExactly(guardianOfOne, coach);
        assertThat(notice.dedupScope()).isEqualTo("reminder:48");
        assertThat(notice.payload().get("opponent")).isEqualTo("Club Rival");
    }

    @Test
    void aMatchWithoutConfirmedPlayersStillRemindsTheCoach() {
        notices("").reminder(aMatch(), 2);

        assertThat(queue.sent.get(0).recipients()).containsExactly(coach);
    }

    @Test
    void anEvaluationHasItsOwnLabel() {
        EventView evaluation = new EventView(UUID.randomUUID(), "EVALUATION", category, UUID.randomUUID(), "Cancha", coach,
                Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-01T23:30:00Z"), null, null, null, "CONFIRMED", null);

        notices("").scheduled(evaluation);

        assertThat(queue.sent.get(0).subject()).isEqualTo("Sesión de evaluación programado · Sub-12");
    }

    // ---------- series (RF-014, RF-016) ----------

    private SeriesInfo series() {
        return new SeriesInfo(UUID.randomUUID(), "TRAINING", category, UUID.randomUUID(), coach,
                List.of(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.WEDNESDAY), java.time.LocalDate.of(2026, 12, 7),
                java.time.LocalDate.of(2026, 12, 9), java.time.LocalTime.of(17, 0), java.time.LocalTime.of(19, 0),
                "America/Bogota", "Puerta principal", "Traer uniforme", 2);
    }

    private SeriesView seriesView(SeriesInfo series) {
        return new SeriesView(series, List.of(
                AgendaFakes.training(category, coach, Instant.parse("2026-12-07T22:00:00Z")),
                AgendaFakes.training(category, coach, Instant.parse("2026-12-09T22:00:00Z"))));
    }

    @Test
    void aSeriesIsOneNoticeForTheWholeSeriesToEachGuardianOnceAndTheCoach() {
        SeriesInfo series = series();
        SeriesView view = seriesView(series);

        notices("https://app.club.example").seriesScheduled(view);

        assertThat(queue.sent).hasSize(1);
        Enqueued notice = queue.sent.get(0);
        assertThat(notice.type()).isEqualTo("EVENT_SERIES_SCHEDULED");
        assertThat(notice.recipients()).containsExactly(guardianOfBoth, guardianOfOne, coach);
        assertThat(notice.relatedEntityType()).isEqualTo("event_series");
        assertThat(notice.relatedEntityId()).isEqualTo(series.id());
        assertThat(notice.dedupScope()).isEqualTo("scheduled");
        assertThat(notice.subject()).isEqualTo("Serie de entrenamientos programada · Sub-12");
    }

    @Test
    void theSeriesPayloadListsEverySessionWithItsOwnEventIdAndNoPlayerData() {
        SeriesView view = seriesView(series());

        notices("https://app.club.example").seriesScheduled(view);

        var payload = queue.sent.get(0).payload();
        String first = view.events().get(0).id() + "|2026-12-07T22:00:00Z|2026-12-07T23:30:00Z|0";
        String second = view.events().get(1).id() + "|2026-12-09T22:00:00Z|2026-12-09T23:30:00Z|0";
        assertThat(payload.get("occurrences")).isEqualTo(first + ";" + second);
        assertThat(payload.get("count")).isEqualTo("2");
        assertThat(payload.get("seriesId")).isEqualTo(view.series().id().toString());
        assertThat(payload.get("categoryName")).isEqualTo("Sub-12");
        assertThat(payload.get("meetingPoint")).isEqualTo("Puerta principal");
        assertThat(payload.get("instructions")).isEqualTo("Traer uniforme");
        assertThat(payload.get("appUrl")).isEqualTo("https://app.club.example");
        assertThat(payload.get("stamp")).isEqualTo(AgendaFakes.NOW.toString());
        assertThat(payload.values().stream().anyMatch(v -> v.contains("Jugadorcito") || v.contains("Ana"))).isFalse();
    }

    @Test
    void onlyConfirmedSessionsAreAnnounced() {
        SeriesInfo series = series();
        EventView confirmed = AgendaFakes.training(category, coach, Instant.parse("2026-12-07T22:00:00Z"));
        EventView cancelled = new EventView(UUID.randomUUID(), "TRAINING", category, UUID.randomUUID(), "Cancha", coach,
                Instant.parse("2026-12-09T22:00:00Z"), Instant.parse("2026-12-09T23:30:00Z"), null, null, null, "CANCELLED", "Lluvia");

        notices("").seriesScheduled(new SeriesView(series, List.of(confirmed, cancelled)));

        assertThat(queue.sent.get(0).payload().get("count")).isEqualTo("1");
        assertThat(queue.sent.get(0).payload().get("occurrences")).startsWith(confirmed.id().toString());
    }

    @Test
    void aSeriesWithoutRecipientsOrSessionsQueuesNothing() {
        AgendaNotices empty = new AgendaNotices(store, roster, guardians, queue, new AgendaSettings("", List.of(2, 24)), audience);
        SeriesInfo noCoach = new SeriesInfo(UUID.randomUUID(), "TRAINING", UUID.randomUUID(), UUID.randomUUID(), null,
                List.of(java.time.DayOfWeek.MONDAY), java.time.LocalDate.of(2026, 12, 7), java.time.LocalDate.of(2026, 12, 7),
                java.time.LocalTime.of(17, 0), java.time.LocalTime.of(19, 0), "America/Bogota", null, null, 1);

        empty.seriesScheduled(new SeriesView(noCoach, List.of(AgendaFakes.training(noCoach.categoryId(), null, Instant.parse("2026-12-07T22:00:00Z")))));
        empty.seriesScheduled(new SeriesView(noCoach, List.of()));

        assertThat(queue.sent).isEmpty();
    }

    @Test
    void cancellingASeriesIsOneNoticeWithTheReasonAndEachSessionsRevisionAsSequence() {
        SeriesInfo series = series();
        SeriesView view = seriesView(series);
        store.revisions.put(view.events().get(0).id(), 1);
        store.revisions.put(view.events().get(1).id(), 2);

        notices("").seriesCancelled(series, view.events(), "Cancha cerrada por obras");

        assertThat(queue.sent).hasSize(1);
        Enqueued notice = queue.sent.get(0);
        assertThat(notice.type()).isEqualTo("EVENT_SERIES_CANCELLED");
        assertThat(notice.recipients()).containsExactly(guardianOfBoth, guardianOfOne, coach);
        assertThat(notice.payload().get("reason")).isEqualTo("Cancha cerrada por obras");
        assertThat(notice.payload().get("occurrences")).contains("|1;").endsWith("|2");
        assertThat(notice.subject()).isEqualTo("CANCELADO: serie de entrenamientos · Sub-12");
        assertThat(notice.dedupScope()).startsWith("cancelled:");
    }
}
