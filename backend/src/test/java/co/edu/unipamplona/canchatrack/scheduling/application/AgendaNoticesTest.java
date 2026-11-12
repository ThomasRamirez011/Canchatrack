package co.edu.unipamplona.canchatrack.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.Enqueued;
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

    private AgendaNotices notices(String appUrl) {
        roster.byCategory.put(category, List.of(player1, player2));
        guardians.guardianByPlayer.put(player1, guardianOfBoth);
        guardians.guardianByPlayer.put(player2, guardianOfOne);
        return new AgendaNotices(store, roster, guardians, queue, new AgendaSettings(appUrl, List.of(2, 24)));
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
        AgendaNotices empty = new AgendaNotices(store, roster, guardians, queue, new AgendaSettings("", List.of(2, 24)));

        empty.scheduled(event());

        assertThat(queue.sent.get(0).recipients()).containsExactly(coach);
    }

    @Test
    void noRecipientsMeansNothingIsQueued() {
        AgendaNotices empty = new AgendaNotices(store, roster, guardians, queue, new AgendaSettings("", List.of(2, 24)));
        EventView withoutCoach = new EventView(UUID.randomUUID(), "TRAINING", category, UUID.randomUUID(), "Cancha", null,
                Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-01T23:30:00Z"), null, null, null, "CONFIRMED", null);

        empty.scheduled(withoutCoach);

        assertThat(queue.sent).isEmpty();
    }

    @Test
    void anEvaluationHasItsOwnLabel() {
        EventView evaluation = new EventView(UUID.randomUUID(), "EVALUATION", category, UUID.randomUUID(), "Cancha", coach,
                Instant.parse("2026-12-01T22:00:00Z"), Instant.parse("2026-12-01T23:30:00Z"), null, null, null, "CONFIRMED", null);

        notices("").scheduled(evaluation);

        assertThat(queue.sent.get(0).subject()).isEqualTo("Sesión de evaluación programado · Sub-12");
    }
}
