package co.edu.unipamplona.canchatrack.scheduling.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.players.api.CategoryRoster;

/**
 * Avisos por correo de los entrenamientos y evaluaciones (RF-016, RF-018, RF-019, RF-020): al programar, al cancelar y como
 * recordatorio. Los destinatarios son los acudientes de los jugadores en actividad de la categoría y el entrenador
 * responsable. Se encolan en la transacción del llamador; un fallo de correo no deshace la programación (el procesador
 * reintenta). El aviso no nombra jugadores —cada acudiente vería los de otras familias— ni lleva datos de salud (RN-17).
 */
@Service
public class AgendaNotices {

    public static final String SCHEDULED = "EVENT_SCHEDULED";
    public static final String CANCELLED = "EVENT_CANCELLED";
    public static final String REMINDER = "EVENT_REMINDER";
    static final String RELATED_ENTITY = "sports_event";

    private final SchedulingStore store;
    private final CategoryRoster roster;
    private final GuardianContacts guardians;
    private final NotificationQueue queue;
    private final AgendaSettings settings;

    public AgendaNotices(SchedulingStore store, CategoryRoster roster, GuardianContacts guardians, NotificationQueue queue,
                         AgendaSettings settings) {
        this.store = store;
        this.roster = roster;
        this.guardians = guardians;
        this.queue = queue;
        this.settings = settings;
    }

    public void scheduled(EventView event) {
        enqueue(event, SCHEDULED, label(event) + " programado", "scheduled", null, null);
    }

    public void cancelled(EventView event) {
        enqueue(event, CANCELLED, "CANCELADO: " + lower(label(event)), "cancelled", event.cancellationReason(), null);
    }

    public void reminder(EventView event, int hoursBefore) {
        enqueue(event, REMINDER, "Recordatorio: " + lower(label(event)), "reminder:" + hoursBefore, null, hoursBefore);
    }

    private void enqueue(EventView event, String type, String subjectPrefix, String dedupScope, String reason,
                         Integer hoursBefore) {
        Set<UUID> recipients = new LinkedHashSet<>(
                guardians.guardianUserIdsOf(roster.activePlayerIds(event.categoryId())));
        if (event.headCoachId() != null) {
            recipients.add(event.headCoachId());
        }
        if (recipients.isEmpty()) {
            return;
        }
        EventContext context = store.contextOf(event);
        Map<String, String> payload = new LinkedHashMap<>();
        put(payload, "eventId", event.id().toString());
        put(payload, "eventType", event.eventType());
        put(payload, "categoryName", context.categoryName());
        put(payload, "venueName", event.venueName());
        put(payload, "venueAddress", context.venueAddress());
        put(payload, "coachName", context.coachName());
        put(payload, "startAt", event.startAt().toString());
        put(payload, "endAt", event.endAt().toString());
        put(payload, "meetingAt", event.meetingAt() == null ? null : event.meetingAt().toString());
        put(payload, "meetingPoint", event.meetingPoint());
        put(payload, "instructions", event.instructions());
        put(payload, "reason", reason);
        put(payload, "appUrl", settings.appUrl());
        put(payload, "stamp", store.now().toString());
        put(payload, "hoursBefore", hoursBefore == null ? null : hoursBefore.toString());
        String category = context.categoryName() == null ? "" : " · " + context.categoryName();
        queue.enqueue(type, recipients, subjectPrefix + category, payload, RELATED_ENTITY, event.id(), dedupScope);
    }

    private static void put(Map<String, String> payload, String key, String value) {
        if (value != null && !value.isBlank()) {
            payload.put(key, value);
        }
    }

    private static String label(EventView event) {
        return switch (event.eventType()) {
            case "TRAINING" -> "Entrenamiento";
            case "EVALUATION" -> "Sesión de evaluación";
            default -> "Evento";
        };
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
