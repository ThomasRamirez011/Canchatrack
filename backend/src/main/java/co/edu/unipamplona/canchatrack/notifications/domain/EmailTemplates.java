package co.edu.unipamplona.canchatrack.notifications.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;

/**
 * Plantillas de los avisos por correo (texto plano, en español). Se alimentan solo del {@code payload} de plantilla que
 * guarda la cola, que nunca contiene datos de salud (RN-17). Un tipo sin plantilla no se renderiza ({@code Optional}
 * vacío) y el procesador lo marca como fallo definitivo en lugar de enviar algo improvisado.
 */
public final class EmailTemplates {

    public static final String MATCH_CALLUP = "MATCH_CALLUP";
    public static final String MATCH_CALLUP_REMOVED = "MATCH_CALLUP_REMOVED";
    public static final String MATCH_CANCELLED = "MATCH_CANCELLED";
    public static final String DOCUMENT_EXPIRY = "DOCUMENT_EXPIRY";
    public static final String EVENT_SCHEDULED = "EVENT_SCHEDULED";
    public static final String EVENT_CANCELLED = "EVENT_CANCELLED";
    public static final String EVENT_REMINDER = "EVENT_REMINDER";
    public static final String EVENT_RESCHEDULED = "EVENT_RESCHEDULED";
    public static final String MATCH_RESCHEDULED = "MATCH_RESCHEDULED";

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final int MAX_VALUE = 200;
    private static final int MAX_SUBJECT = 150;
    private static final String SIGNATURE = "\n\nClub CanchaTrack\nEste es un aviso automático; no responda a este correo.";

    private EmailTemplates() {
    }

    public static Optional<OutboundEmail> render(String type, String recipientEmail, String subject,
                                                 Map<String, String> payload, ZoneId zone) {
        if (type == null || recipientEmail == null || recipientEmail.isBlank()) {
            return Optional.empty();
        }
        Map<String, String> data = payload == null ? Map.of() : payload;
        String player = valueOr(data.get("playerName"), "su jugador");
        String opponent = valueOr(data.get("opponent"), "el rival por confirmar");
        String start = when(data.get("startAt"), zone);

        String body;
        String defaultSubject;
        CalendarInvite calendar = null;
        switch (type) {
            case MATCH_CALLUP -> {
                defaultSubject = "Convocatoria a partido";
                StringBuilder sb = new StringBuilder("Hola,\n\n")
                        .append(player).append(" fue convocado al partido contra ").append(opponent)
                        .append(", programado para el ").append(start).append('.');
                if (data.get("meetingAt") != null) {
                    sb.append("\nPunto de encuentro: ").append(when(data.get("meetingAt"), zone));
                    if (data.get("meetingPoint") != null) {
                        sb.append(" en ").append(singleLine(data.get("meetingPoint")));
                    }
                    sb.append('.');
                } else if (data.get("meetingPoint") != null) {
                    sb.append("\nPunto de encuentro: ").append(singleLine(data.get("meetingPoint"))).append('.');
                }
                sb.append("\n\nPara que pueda jugar, el acudiente debe autorizar su participación en el sistema.");
                body = sb.toString();
            }
            case MATCH_CALLUP_REMOVED -> {
                defaultSubject = "Cambio en la convocatoria";
                body = "Hola,\n\n" + player + " ya no hace parte de la convocatoria al partido contra " + opponent
                        + " del " + start + ".";
            }
            case MATCH_CANCELLED -> {
                defaultSubject = "Partido cancelado";
                StringBuilder sb = new StringBuilder("Hola,\n\nEl partido contra ").append(opponent)
                        .append(" del ").append(start).append(" fue cancelado.");
                if (data.get("reason") != null) {
                    sb.append("\nMotivo: ").append(singleLine(data.get("reason")));
                }
                body = sb.toString();
            }
            case DOCUMENT_EXPIRY -> {
                defaultSubject = "Documento por vencer";
                String document = valueOr(data.get("documentName"), "Un documento");
                StringBuilder sb = new StringBuilder("Hola,\n\nEl documento «").append(document).append("» de ")
                        .append(player).append(" vence el ").append(date(data.get("expiresOn")));
                String days = data.get("daysLeft");
                if (days != null && days.trim().matches("\\d{1,3}")) {
                    String n = days.trim();
                    sb.append(n.equals("0") ? " (es hoy)" : " (faltan " + n + (n.equals("1") ? " día)" : " días)"));
                }
                sb.append(".\n\nCárguelo actualizado en el sistema antes de esa fecha: con el documento vencido el jugador "
                        + "no puede ser convocado a partidos.");
                body = sb.toString();
            }
            case EVENT_SCHEDULED -> {
                String label = eventLabel(data.get("eventType"));
                String category = valueOr(data.get("categoryName"), "su categoría");
                defaultSubject = capitalize(label) + " programado · " + category;
                body = "Hola,\n\nSe programó un " + label + " de la categoría " + category + ".\n" + eventDetails(data, zone);
                calendar = invite(IcsCalendar.REQUEST, 0, data, capitalize(label) + " · " + category);
            }
            case EVENT_CANCELLED -> {
                String label = eventLabel(data.get("eventType"));
                String category = valueOr(data.get("categoryName"), "su categoría");
                defaultSubject = "CANCELADO: " + label + " · " + category;
                StringBuilder sb = new StringBuilder("Hola,\n\nCANCELACIÓN: el ").append(label).append(" de la categoría ")
                        .append(category).append(" del ").append(start).append(" fue cancelado.");
                if (data.get("reason") != null) {
                    sb.append("\nMotivo: ").append(singleLine(data.get("reason")));
                }
                sb.append("\n\n").append(link(data));
                body = sb.toString().stripTrailing();
                calendar = invite(IcsCalendar.CANCEL, sequenceOf(data, 1), data, "CANCELADO: " + capitalize(label) + " · " + category);
            }
            case EVENT_RESCHEDULED -> {
                String label = eventLabel(data.get("eventType"));
                String category = valueOr(data.get("categoryName"), "su categoría");
                defaultSubject = "REPROGRAMADO: " + label + " · " + category;
                StringBuilder sb = new StringBuilder("Hola,\n\nREPROGRAMACIÓN: el ").append(label).append(" de la categoría ")
                        .append(category);
                if (data.get("previousStartAt") != null) {
                    sb.append(" que estaba para el ").append(when(data.get("previousStartAt"), zone));
                }
                sb.append(" cambió de horario o lugar.");
                if (data.get("reason") != null) {
                    sb.append("\nMotivo: ").append(singleLine(data.get("reason")));
                }
                sb.append("\n\nNueva programación:\n").append(eventDetails(data, zone));
                body = sb.toString();
                calendar = invite(IcsCalendar.REQUEST, Math.max(1, sequenceOf(data, 1)), data,
                        capitalize(label) + " · " + category);
            }
            case MATCH_RESCHEDULED -> {
                defaultSubject = "Partido reprogramado";
                StringBuilder sb = new StringBuilder("Hola,\n\nEl partido de ").append(player).append(" contra ").append(opponent);
                if (data.get("previousStartAt") != null) {
                    sb.append(" que estaba para el ").append(when(data.get("previousStartAt"), zone));
                }
                sb.append(" se reprogramó para el ").append(start).append('.');
                if (data.get("meetingAt") != null) {
                    sb.append("\nPunto de encuentro: ").append(when(data.get("meetingAt"), zone));
                    if (data.get("meetingPoint") != null) {
                        sb.append(" en ").append(singleLine(data.get("meetingPoint")));
                    }
                    sb.append('.');
                } else if (data.get("meetingPoint") != null) {
                    sb.append("\nPunto de encuentro: ").append(singleLine(data.get("meetingPoint"))).append('.');
                }
                if (data.get("reason") != null) {
                    sb.append("\nMotivo: ").append(singleLine(data.get("reason")));
                }
                sb.append("\n\nSi la nueva fecha no le sirve, comuníquese con el club.");
                body = sb.toString();
            }
            case EVENT_REMINDER -> {
                String label = eventLabel(data.get("eventType"));
                String category = valueOr(data.get("categoryName"), "su categoría");
                defaultSubject = "Recordatorio: " + label + " · " + category;
                String hours = data.get("hoursBefore");
                String lead = hours != null && hours.trim().matches("\\d{1,3}")
                        ? "Faltan " + hours.trim() + (hours.trim().equals("1") ? " hora" : " horas") + " para el " + label
                        : "Se acerca el " + label;
                body = "Hola,\n\n" + lead + " de la categoría " + category + ".\n" + eventDetails(data, zone);
            }
            default -> {
                return Optional.empty();
            }
        }
        String finalSubject = subject == null || subject.isBlank() ? defaultSubject : singleLine(subject);
        if (finalSubject.length() > MAX_SUBJECT) {
            finalSubject = finalSubject.substring(0, MAX_SUBJECT);
        }
        return Optional.of(new OutboundEmail(recipientEmail.trim(), finalSubject, body + SIGNATURE, calendar));
    }

    // ---------- eventos de la agenda (RF-016, RF-018, RF-019, RF-020) ----------

    /** SEQUENCE del .ics: lo fija quien avisa (revisión del evento); un valor ausente o inválido usa el predeterminado. */
    private static int sequenceOf(Map<String, String> data, int fallback) {
        String raw = data.get("sequence");
        return raw != null && raw.trim().matches("\\d{1,4}") ? Integer.parseInt(raw.trim()) : fallback;
    }

    private static String eventLabel(String eventType) {
        if (eventType == null) {
            return "evento";
        }
        return switch (eventType.trim()) {
            case "TRAINING" -> "entrenamiento";
            case "EVALUATION" -> "sesión de evaluación";
            default -> "evento";
        };
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Fecha, escenario, entrenador, concentración, indicaciones y enlace; sin jugadores ni datos de salud (RN-17). */
    private static String eventDetails(Map<String, String> data, ZoneId zone) {
        StringBuilder sb = new StringBuilder("Fecha: ").append(range(data.get("startAt"), data.get("endAt"), zone)).append('.');
        if (data.get("venueName") != null) {
            sb.append("\nEscenario: ").append(singleLine(data.get("venueName")));
            if (data.get("venueAddress") != null) {
                sb.append(" (").append(singleLine(data.get("venueAddress"))).append(')');
            }
            sb.append('.');
        }
        if (data.get("coachName") != null) {
            sb.append("\nEntrenador: ").append(singleLine(data.get("coachName"))).append('.');
        }
        if (data.get("meetingAt") != null) {
            sb.append("\nConcentración: ").append(when(data.get("meetingAt"), zone));
            if (data.get("meetingPoint") != null) {
                sb.append(" en ").append(singleLine(data.get("meetingPoint")));
            }
            sb.append('.');
        } else if (data.get("meetingPoint") != null) {
            sb.append("\nPunto de encuentro: ").append(singleLine(data.get("meetingPoint"))).append('.');
        }
        if (data.get("instructions") != null) {
            sb.append("\nIndicaciones: ").append(singleLine(data.get("instructions")));
        }
        String link = link(data);
        return link.isEmpty() ? sb.toString() : sb + "\n\n" + link;
    }

    private static String link(Map<String, String> data) {
        String url = data.get("appUrl");
        return url == null || url.isBlank() ? "" : "Consulte el detalle en el sistema: " + singleLine(url);
    }

    private static String range(String startIso, String endIso, ZoneId zone) {
        String start = when(startIso, zone);
        try {
            var from = Instant.parse(startIso.trim()).atZone(zone);
            var to = Instant.parse(endIso.trim()).atZone(zone);
            if (from.toLocalDate().equals(to.toLocalDate())) {
                return start + " a " + DateTimeFormatter.ofPattern("HH:mm").format(to);
            }
            return start + " a " + WHEN.format(to);
        } catch (RuntimeException e) {
            return start;
        }
    }

    /** Invitación .ics solo si el aviso trae lo mínimo para armarla; si no, el correo sale sin ella. */
    private static CalendarInvite invite(String method, int sequence, Map<String, String> data, String summary) {
        try {
            Instant start = Instant.parse(data.get("startAt").trim());
            Instant end = Instant.parse(data.get("endAt").trim());
            Instant stamp = Instant.parse(data.get("stamp").trim()).truncatedTo(ChronoUnit.SECONDS);
            String eventId = data.get("eventId");
            if (eventId == null || eventId.isBlank() || !end.isAfter(start)) {
                return null;
            }
            String venue = data.get("venueName") == null ? null : singleLine(data.get("venueName"));
            String address = data.get("venueAddress") == null ? null : singleLine(data.get("venueAddress"));
            String location = venue == null ? address : address == null ? venue : venue + ", " + address;
            StringBuilder description = new StringBuilder();
            if (data.get("coachName") != null) {
                description.append("Entrenador: ").append(singleLine(data.get("coachName")));
            }
            if (data.get("instructions") != null) {
                description.append(description.length() == 0 ? "" : "\n").append(singleLine(data.get("instructions")));
            }
            if (data.get("reason") != null && IcsCalendar.CANCEL.equals(method)) {
                description.append(description.length() == 0 ? "" : "\n").append("Motivo: ").append(singleLine(data.get("reason")));
            }
            return IcsCalendar.event(method, eventId.trim() + "@canchatrack", sequence, stamp, start, end, summary, location,
                    description.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Una sola línea, sin saltos ni tabulaciones: evita inyectar encabezados o líneas falsas en el correo. */
    static String singleLine(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return cleaned.length() > MAX_VALUE ? cleaned.substring(0, MAX_VALUE) : cleaned;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : singleLine(value);
    }

    private static String date(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return "la fecha por confirmar";
        }
        try {
            return DATE.format(java.time.LocalDate.parse(isoDate.trim()));
        } catch (DateTimeParseException e) {
            return singleLine(isoDate);
        }
    }

    private static String when(String instant, ZoneId zone) {
        if (instant == null || instant.isBlank()) {
            return "fecha por confirmar";
        }
        try {
            return WHEN.format(Instant.parse(instant.trim()).atZone(zone));
        } catch (DateTimeParseException e) {
            return singleLine(instant);
        }
    }
}
