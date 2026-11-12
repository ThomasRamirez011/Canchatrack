package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.scheduling.domain.ReminderPolicy;

/**
 * Recordatorios de entrenamientos y evaluaciones (RF-019), por defecto 24 y 2 horas antes. Idempotente: el ámbito de
 * deduplicación incluye el tramo, así que correr el proceso muchas veces dentro de la misma ventana no repite el correo.
 * Un evento programado dentro de un tramo no recibe ese recordatorio (el aviso de programación ya cubre la fecha).
 */
@Service
public class EventReminderService {

    private final SchedulingStore store;
    private final AgendaNotices notices;
    private final AgendaSettings settings;

    public EventReminderService(SchedulingStore store, AgendaNotices notices, AgendaSettings settings) {
        this.store = store;
        this.notices = notices;
        this.settings = settings;
    }

    /** @return cuántos eventos tenían un recordatorio que corresponde ahora */
    @Transactional
    public int sendDue() {
        List<Integer> hours = settings.reminderHours();
        if (hours.isEmpty()) {
            return 0;
        }
        Instant now = store.now();
        Instant horizon = now.plus(Duration.ofHours(hours.get(hours.size() - 1)));
        int due = 0;
        for (ReminderCandidate candidate : store.findUpcomingForReminders(now, horizon)) {
            OptionalInt stage = ReminderPolicy.stage(now, candidate.event().startAt(), candidate.createdAt(), hours);
            if (stage.isPresent()) {
                notices.reminder(candidate.event(), stage.getAsInt());
                due++;
            }
        }
        return due;
    }
}
