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
import co.edu.unipamplona.canchatrack.scheduling.api.MatchAudience;

/**
 * Avisos por correo de la agenda (RF-016, RF-017, RF-018, RF-019, RF-020): al programar, al cancelar, al reprogramar y como
 * recordatorio. En un entrenamiento o una evaluación los destinatarios son los acudientes de los jugadores en actividad de la
 * categoría y el entrenador responsable. En un <strong>partido</strong> las familias reciben el aviso de la convocatoria (de
 * {@code competitions}), así que aquí el entrenador es el único destinatario de programación, cancelación y reprogramación,
 * y el recordatorio va además a los acudientes de los convocados confirmados. Se encolan en la transacción del llamador; un fallo de correo no deshace la programación (el procesador
 * reintenta). El aviso no nombra jugadores —cada acudiente vería los de otras familias— ni lleva datos de salud (RN-17).
 */
@Service
public class AgendaNotices {

    public static final String SCHEDULED = "EVENT_SCHEDULED";
    public static final String CANCELLED = "EVENT_CANCELLED";
    public static final String REMINDER = "EVENT_REMINDER";
    public static final String RESCHEDULED = "EVENT_RESCHEDULED";
    static final String RELATED_ENTITY = "sports_event";

    private final SchedulingStore store;
    private final CategoryRoster roster;
    private final GuardianContacts guardians;
    private final NotificationQueue queue;
    private final AgendaSettings settings;
    private final MatchAudience matchAudience;

    public AgendaNotices(SchedulingStore store, CategoryRoster roster, GuardianContacts guardians, NotificationQueue queue,
                         AgendaSettings settings, MatchAudience matchAudience) {
        this.matchAudience = matchAudience;
        this.store = store;
        this.roster = roster;
        this.guardians = guardians;
        this.queue = queue;
        this.settings = settings;
    }

    public void scheduled(EventView event) {
        enqueue(event, SCHEDULED, label(event) + " programado", "scheduled", Set.of(), Map.of());
    }

    /** La invitación de cancelación lleva la revisión del evento como {@code SEQUENCE}, para que el calendario la acepte. */
    public void cancelled(EventView event) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("reason", event.cancellationReason());
        extra.put("sequence", Integer.toString(store.revisionOf(event.id())));
        enqueue(event, CANCELLED, "CANCELADO: " + lower(label(event)), "cancelled", Set.of(), extra);
    }

    /**
     * Avisa de un cambio de fecha, lugar o entrenador. El entrenador anterior también lo recibe: el evento sale de su
     * agenda. Cada revisión tiene su propio ámbito de deduplicación.
     */
    public void rescheduled(EventView before, EventView after, String reason) {
        int revision = store.revisionOf(after.id());
        Set<UUID> extraRecipients = new LinkedHashSet<>();
        if (before.headCoachId() != null) {
            extraRecipients.add(before.headCoachId());
        }
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("reason", reason);
        extra.put("previousStartAt", before.startAt().toString());
        extra.put("previousEndAt", before.endAt().toString());
        extra.put("sequence", Integer.toString(revision));
        enqueue(after, RESCHEDULED, "Reprogramado: " + lower(label(after)), "rescheduled:" + revision, extraRecipients, extra);
    }

    /** Tras una reprogramación los recordatorios vuelven a salir: a partir de la revisión 1 el ámbito la incluye. */
    public void reminder(EventView event, int hoursBefore) {
        int revision = store.revisionOf(event.id());
        String scope = revision == 0 ? "reminder:" + hoursBefore : "reminder:" + hoursBefore + ":r" + revision;
        enqueue(event, REMINDER, "Recordatorio: " + lower(label(event)), scope, Set.of(), Map.of("hoursBefore", Integer.toString(hoursBefore)));
    }

    private void enqueue(EventView event, String type, String subjectPrefix, String dedupScope, Set<UUID> extraRecipients,
                         Map<String, String> extra) {
        Set<UUID> recipients = new LinkedHashSet<>();
        if (!isMatch(event)) {
            recipients.addAll(guardians.guardianUserIdsOf(roster.activePlayerIds(event.categoryId())));
        } else if (REMINDER.equals(type)) {
            recipients.addAll(matchAudience.confirmedGuardianUserIds(event.id()));
        }
        if (event.headCoachId() != null) {
            recipients.add(event.headCoachId());
        }
        recipients.addAll(extraRecipients);
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
        put(payload, "opponent", context.opponent());
        put(payload, "startAt", event.startAt().toString());
        put(payload, "endAt", event.endAt().toString());
        put(payload, "meetingAt", event.meetingAt() == null ? null : event.meetingAt().toString());
        put(payload, "meetingPoint", event.meetingPoint());
        put(payload, "instructions", event.instructions());
        put(payload, "appUrl", settings.appUrl());
        put(payload, "stamp", store.now().toString());
        extra.forEach((key, value) -> put(payload, key, value));
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
            case "FRIENDLY_MATCH" -> "Partido amistoso";
            case "TOURNAMENT_MATCH" -> "Partido de torneo";
            default -> "Evento";
        };
    }

    private static boolean isMatch(EventView event) {
        return "FRIENDLY_MATCH".equals(event.eventType()) || "TOURNAMENT_MATCH".equals(event.eventType());
    }

    private static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
