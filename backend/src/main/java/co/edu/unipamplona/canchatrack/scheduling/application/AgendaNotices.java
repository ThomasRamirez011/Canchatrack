package co.edu.unipamplona.canchatrack.scheduling.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
    public static final String SERIES_SCHEDULED = "EVENT_SERIES_SCHEDULED";
    public static final String SERIES_CANCELLED = "EVENT_SERIES_CANCELLED";
    static final String RELATED_ENTITY = "sports_event";
    static final String SERIES_ENTITY = "event_series";

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

    /**
     * Un solo correo por serie (RF-014, RF-016): lista las sesiones y adjunta una invitación con una cita por sesión. Cada cita
     * conserva el identificador de su evento, así que reprogramar o cancelar una sesión suelta actualiza esa misma cita.
     * Los recordatorios de 24 h y 2 h siguen saliendo por sesión.
     */
    public void seriesScheduled(SeriesView view) {
        List<EventView> sessions = view.events().stream().filter(e -> "CONFIRMED".equals(e.status())).toList();
        enqueueSeries(view.series(), sessions, SERIES_SCHEDULED, "scheduled", Map.of(), 0);
    }

    /** Un solo correo con la lista de las sesiones canceladas y una invitación de cancelación (una cita por sesión). */
    public void seriesCancelled(SeriesInfo series, List<EventView> cancelled, String reason) {
        enqueueSeries(series, cancelled, SERIES_CANCELLED, "cancelled:" + cancelled.get(0).id(), Map.of("reason", reason), -1);
    }

    /** @param fixedSequence {@code SEQUENCE} de todas las citas; -1 usa la revisión de cada evento (la cancelación la sube) */
    private void enqueueSeries(SeriesInfo series, List<EventView> sessions, String type, String dedupScope,
                               Map<String, String> extra, int fixedSequence) {
        if (sessions.isEmpty()) {
            return;
        }
        EventView first = sessions.get(0);
        Set<UUID> recipients = new LinkedHashSet<>(guardians.guardianUserIdsOf(roster.activePlayerIds(series.categoryId())));
        if (series.headCoachId() != null) {
            recipients.add(series.headCoachId());
        }
        if (recipients.isEmpty()) {
            return;
        }
        EventContext context = store.contextOf(first);
        // idEvento|inicio|fin|secuencia separados por «;»: es el formato que lee la plantilla de notifications.
        List<String> items = new ArrayList<>();
        for (EventView e : sessions) {
            int sequence = fixedSequence >= 0 ? fixedSequence : store.revisionOf(e.id());
            items.add(e.id() + "|" + e.startAt() + "|" + e.endAt() + "|" + sequence);
        }
        Map<String, String> payload = new LinkedHashMap<>();
        put(payload, "seriesId", series.id().toString());
        put(payload, "eventType", series.eventType());
        put(payload, "categoryName", context.categoryName());
        put(payload, "venueName", first.venueName());
        put(payload, "venueAddress", context.venueAddress());
        put(payload, "coachName", context.coachName());
        put(payload, "meetingPoint", series.meetingPoint());
        put(payload, "instructions", series.instructions());
        put(payload, "appUrl", settings.appUrl());
        put(payload, "stamp", store.now().toString());
        put(payload, "count", Integer.toString(sessions.size()));
        put(payload, "occurrences", String.join(";", items));
        extra.forEach((key, value) -> put(payload, key, value));
        String category = context.categoryName() == null ? "" : " · " + context.categoryName();
        String plural = "EVALUATION".equals(series.eventType()) ? "sesiones de evaluación" : "entrenamientos";
        String subject = SERIES_CANCELLED.equals(type) ? "CANCELADO: serie de " + plural : "Serie de " + plural + " programada";
        queue.enqueue(type, recipients, subject + category, payload, SERIES_ENTITY, series.id(), dedupScope);
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
