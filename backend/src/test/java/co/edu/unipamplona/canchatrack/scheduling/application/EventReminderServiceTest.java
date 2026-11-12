package co.edu.unipamplona.canchatrack.scheduling.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.FakeGuardians;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.FakeRoster;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.FakeStore;
import co.edu.unipamplona.canchatrack.scheduling.application.AgendaFakes.RecordingQueue;

/** El proceso periódico de recordatorios (RF-019): qué eventos tocan ahora y cuánto mira hacia adelante. */
class EventReminderServiceTest {

    private final UUID category = UUID.randomUUID();
    private final UUID coach = UUID.randomUUID();
    private final FakeStore store = new FakeStore();
    private final RecordingQueue queue = new RecordingQueue();
    private final FakeRoster roster = new FakeRoster();
    private final FakeGuardians guardians = new FakeGuardians();

    private EventReminderService service(List<Integer> hours) {
        AgendaSettings settings = new AgendaSettings("", hours);
        return new EventReminderService(store, new AgendaNotices(store, roster, guardians, queue, settings), settings);
    }

    private ReminderCandidate candidate(Duration untilStart, Duration createdBefore) {
        Instant start = AgendaFakes.NOW.plus(untilStart);
        return new ReminderCandidate(AgendaFakes.training(category, coach, start), start.minus(createdBefore));
    }

    @Test
    void remindsEventsInsideEachStageAndLooksAheadAsFarAsTheLargest() {
        store.upcoming.add(candidate(Duration.ofHours(20), Duration.ofDays(7)));
        store.upcoming.add(candidate(Duration.ofMinutes(90), Duration.ofDays(7)));
        store.upcoming.add(candidate(Duration.ofHours(30), Duration.ofDays(7)));

        int due = service(List.of(2, 24)).sendDue();

        assertThat(due).isEqualTo(2);
        assertThat(store.lastFrom).isEqualTo(AgendaFakes.NOW);
        assertThat(store.lastTo).isEqualTo(AgendaFakes.NOW.plus(Duration.ofHours(24)));
        assertThat(queue.sent.stream().map(e -> e.dedupScope()).sorted().toList()).containsExactly("reminder:2", "reminder:24");
    }

    @Test
    void anEventScheduledInsideTheStageGetsNoReminderForIt() {
        // programado hace 3 horas para dentro de 20: el aviso de programación ya cubre el tramo de 24 h
        store.upcoming.add(new ReminderCandidate(AgendaFakes.training(category, coach, AgendaFakes.NOW.plus(Duration.ofHours(20))),
                AgendaFakes.NOW.minus(Duration.ofHours(3))));
        // programado hace una semana para dentro de 20 horas: sí toca
        store.upcoming.add(candidate(Duration.ofHours(20), Duration.ofDays(7)));

        int due = service(List.of(2, 24)).sendDue();

        assertThat(due).isEqualTo(1);
        assertThat(queue.sent).hasSize(1);
    }

    @Test
    void withoutConfiguredHoursNothingRuns() {
        store.upcoming.add(candidate(Duration.ofHours(1), Duration.ofDays(7)));

        assertThat(service(List.of()).sendDue()).isZero();
        assertThat(store.lastFrom).isNull();
        assertThat(queue.sent).isEmpty();
    }
}
