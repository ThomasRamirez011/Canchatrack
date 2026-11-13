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
    public static final String EVENT_SERIES_SCHEDULED = "EVENT_SERIES_SCHEDULED";
    public static final String EVENT_SERIES_CANCELLED = "EVENT_SERIES_CANCELLED";
    public static final String MATCH_RESCHEDULED = "MATCH_RESCHEDULED";
    public static final String COMMITTEE_ASSIGNED = "COMMITTEE_ASSIGNED";
    public static final String COMMITTEE_REJECTED = "COMMITTEE_REJECTED";
    public static final String ARREARS_NOTICE = "ARREARS_NOTICE";
    public static final String PASSWORD_RESET = "PASSWORD_RESET";
    public static final String EMAIL_VERIFICATION = "EMAIL_VERIFICATION";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

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
                calendar = invite(IcsCalendar.REQUEST, 0, data, summary(label, category, data));
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
                calendar = invite(IcsCalendar.CANCEL, sequenceOf(data, 1), data, "CANCELADO: " + summary(label, category, data));
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
                        summary(label, category, data));
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
            case COMMITTEE_ASSIGNED -> {
                defaultSubject = "Categoría asignada";
                String category = valueOr(data.get("categoryName"), "una categoría del club");
                body = "Hola,\n\nEl Comité Técnico revisó la inscripción de " + player + " y le asignó la categoría "
                        + category + ".\n\nEl siguiente paso es la matrícula: el club le indicará el valor y la fecha "
                        + "de pago. Cuando esté saldada y los documentos obligatorios estén al día, el jugador queda activo.";
            }
            case COMMITTEE_REJECTED -> {
                defaultSubject = "Resultado de la inscripción";
                body = "Hola,\n\nEl Comité Técnico revisó la inscripción de " + player + " y no pudo asignarle una "
                        + "categoría en esta oportunidad.\n\nPara conocer el detalle de la decisión y las opciones que "
                        + "tiene, comuníquese directamente con el club.";
            }
            case ARREARS_NOTICE -> {
                defaultSubject = "Saldo pendiente vencido";
                String concept = valueOr(data.get("concept"), "Un cargo");
                StringBuilder sb = new StringBuilder("Hola,\n\nEl cargo «").append(concept).append("» de ").append(player);
                if (data.get("period") != null) {
                    sb.append(" (periodo ").append(monthOf(data.get("period"))).append(')');
                }
                sb.append(" venció el ").append(date(data.get("dueDate"))).append(" y tiene un saldo pendiente de ")
                        .append(money(data.get("outstanding"), data.get("currency"))).append('.');
                String days = data.get("daysOverdue");
                if (days != null && days.trim().matches("\\d{1,4}")) {
                    sb.append("\nLlevan ").append(days.trim()).append(days.trim().equals("1") ? " día" : " días")
                            .append(" de retraso.");
                }
                sb.append("\n\nSi ya realizó el pago, comuníquese con el club para que lo registre. Mientras haya un saldo "
                        + "vencido, el jugador no puede ser convocado a partidos.");
                String link = link(data);
                if (!link.isEmpty()) {
                    sb.append("\n\n").append(link);
                }
                body = sb.toString();
            }
            case EVENT_SERIES_SCHEDULED -> {
                String plural = eventPlural(data.get("eventType"));
                String category = valueOr(data.get("categoryName"), "su categoría");
                java.util.List<IcsCalendar.Occurrence> sessions = occurrencesOf(data);
                defaultSubject = "Serie de " + plural + " programada · " + category;
                body = "Hola,\n\nSe programó una serie de " + sessions.size() + " " + plural + " de la categoría " + category
                        + ":\n" + sessionLines(sessions, zone) + "\n" + seriesDetails(data);
                calendar = seriesInvite(IcsCalendar.REQUEST, data, sessions, capitalize(plural) + " · " + category);
            }
            case EVENT_SERIES_CANCELLED -> {
                String plural = eventPlural(data.get("eventType"));
                String category = valueOr(data.get("categoryName"), "su categoría");
                java.util.List<IcsCalendar.Occurrence> sessions = occurrencesOf(data);
                defaultSubject = "CANCELADO: serie de " + plural + " · " + category;
                StringBuilder sb = new StringBuilder("Hola,\n\nCANCELACIÓN: se cancelaron " + sessions.size() + " " + plural
                        + " de la categoría " + category + ":\n").append(sessionLines(sessions, zone));
                if (data.get("reason") != null) {
                    sb.append("\nMotivo: ").append(singleLine(data.get("reason")));
                }
                String link = link(data);
                if (!link.isEmpty()) {
                    sb.append("\n\n").append(link);
                }
                body = sb.toString();
                calendar = seriesInvite(IcsCalendar.CANCEL, data, sessions, "CANCELADO: " + capitalize(plural) + " · " + category);
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
            case PASSWORD_RESET -> {
                defaultSubject = "Restablecer su contraseña";
                body = "Hola,\n\nRecibimos una solicitud para restablecer la contraseña de su cuenta en CanchaTrack.\n\n"
                        + secretAction(data, "elegir una nueva contraseña", "restablecimiento", "validityMinutes", "minutos")
                        + "\n\nSi usted no la solicitó, ignore este correo: su contraseña no cambiará.";
            }
            case EMAIL_VERIFICATION -> {
                defaultSubject = "Confirme su correo";
                body = "Hola,\n\nCreó una cuenta en CanchaTrack con este correo. Para poder iniciar sesión, confírmelo.\n\n"
                        + secretAction(data, "confirmar su correo", "confirmación", "validityHours", "horas")
                        + "\n\nSi usted no creó la cuenta, ignore este correo: sin confirmar, la cuenta no podrá usarse.";
            }
            case PASSWORD_CHANGED -> {
                defaultSubject = "Su contraseña fue cambiada";
                body = "Hola,\n\nLa contraseña de su cuenta en CanchaTrack acaba de cambiar y se cerraron sus sesiones abiertas."
                        + "\n\nSi no fue usted, restablezca la contraseña desde la pantalla de inicio de sesión y avise al club.";
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
            case "FRIENDLY_MATCH" -> "partido amistoso";
            case "TOURNAMENT_MATCH" -> "partido de torneo";
            default -> "evento";
        };
    }

    /** Título de la cita del calendario; en un partido nombra al rival. */
    private static String summary(String label, String category, Map<String, String> data) {
        String rival = data.get("opponent") == null ? "" : " vs " + singleLine(data.get("opponent"));
        return capitalize(label) + " · " + category + rival;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Fecha, escenario, entrenador, concentración, indicaciones y enlace; sin jugadores ni datos de salud (RN-17). */
    private static String eventDetails(Map<String, String> data, ZoneId zone) {
        StringBuilder sb = new StringBuilder("Fecha: ").append(range(data.get("startAt"), data.get("endAt"), zone)).append('.');
        if (data.get("opponent") != null) {
            sb.append("\nRival: ").append(singleLine(data.get("opponent"))).append('.');
        }
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

    // ---------- series de sesiones (RF-014) ----------

    private static final String[] DAYS = {"lun", "mar", "mié", "jue", "vie", "sáb", "dom"};
    private static final int MAX_SESSIONS = 60;

    private static String eventPlural(String eventType) {
        if (eventType == null) {
            return "sesiones";
        }
        return switch (eventType.trim()) {
            case "TRAINING" -> "entrenamientos";
            case "EVALUATION" -> "sesiones de evaluación";
            default -> "sesiones";
        };
    }

    /**
     * Sesiones de una serie desde el payload: {@code idEvento|inicio|fin|secuencia} separadas por «;» (formato que arma
     * {@code AgendaNotices}). Una sesión ilegible se omite en vez de impedir el aviso.
     */
    static java.util.List<IcsCalendar.Occurrence> occurrencesOf(Map<String, String> data) {
        java.util.List<IcsCalendar.Occurrence> sessions = new java.util.ArrayList<>();
        String raw = data.get("occurrences");
        if (raw == null || raw.isBlank()) {
            return sessions;
        }
        for (String item : raw.split(";")) {
            String[] parts = item.trim().split("\\|");
            if (parts.length < 3 || parts[0].isBlank() || sessions.size() >= MAX_SESSIONS) {
                continue;
            }
            try {
                Instant start = Instant.parse(parts[1].trim());
                Instant end = Instant.parse(parts[2].trim());
                int sequence = parts.length > 3 && parts[3].trim().matches("\\d{1,4}") ? Integer.parseInt(parts[3].trim()) : 0;
                if (end.isAfter(start)) {
                    sessions.add(new IcsCalendar.Occurrence(parts[0].trim() + "@canchatrack", sequence, start, end));
                }
            } catch (DateTimeParseException e) {
                // sesión ilegible: se omite
            }
        }
        return sessions;
    }

    /** Una línea por sesión: «- lun 02/03/2026 17:00 a 19:00». */
    private static String sessionLines(java.util.List<IcsCalendar.Occurrence> sessions, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        for (IcsCalendar.Occurrence o : sessions) {
            var from = o.start().atZone(zone);
            sb.append("- ").append(DAYS[from.getDayOfWeek().getValue() - 1]).append(' ')
                    .append(range(o.start().toString(), o.end().toString(), zone)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Escenario, entrenador, punto de encuentro, indicaciones y enlace; sin jugadores ni datos de salud (RN-17). */
    private static String seriesDetails(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
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
        if (data.get("meetingPoint") != null) {
            sb.append("\nPunto de encuentro: ").append(singleLine(data.get("meetingPoint"))).append('.');
        }
        if (data.get("instructions") != null) {
            sb.append("\nIndicaciones: ").append(singleLine(data.get("instructions")));
        }
        String link = link(data);
        if (!link.isEmpty()) {
            sb.append("\n\n").append(link);
        }
        return sb.toString().stripLeading();
    }

    /** Una invitación con un VEVENT por sesión; sin sesiones legibles o sin sello, el correo sale sin ella. */
    private static CalendarInvite seriesInvite(String method, Map<String, String> data,
                                               java.util.List<IcsCalendar.Occurrence> sessions, String summary) {
        try {
            if (sessions.isEmpty()) {
                return null;
            }
            Instant stamp = Instant.parse(data.get("stamp").trim()).truncatedTo(ChronoUnit.SECONDS);
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
            return IcsCalendar.series(method, stamp, sessions, summary, location, description.toString());
        } catch (RuntimeException e) {
            return null;
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
            if (data.get("opponent") != null) {
                description.append("Rival: ").append(singleLine(data.get("opponent")));
            }
            if (data.get("coachName") != null) {
                description.append(description.length() == 0 ? "" : "\n");
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


    /**
     * Instrucción con un secreto de un solo uso: el enlace si el club configuró la dirección del front; si no, el código
     * para pegarlo en la pantalla correspondiente. La vigencia se muestra solo si viene como entero corto.
     */
    private static String secretAction(Map<String, String> data, String action, String screen, String validityKey,
                                       String unit) {
        String url = data.get("url");
        StringBuilder sb = new StringBuilder();
        if (url != null && !url.isBlank()) {
            sb.append("Abra este enlace para ").append(action).append(":\n").append(url.trim());
        } else {
            sb.append("Use este código en la pantalla de ").append(screen).append(":\n")
                    .append(valueOr(data.get("token"), "(no disponible)"));
        }
        String validity = data.get(validityKey);
        if (validity != null && validity.trim().matches("\\d{1,4}")) {
            sb.append("\n\nVale ").append(validity.trim()).append(' ').append(unit).append(" y solo se puede usar una vez.");
        }
        return sb.toString();
    }

    /** Una sola línea, sin saltos ni tabulaciones: evita inyectar encabezados o líneas falsas en el correo. */
    static String singleLine(String value) {
        String cleaned = value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return cleaned.length() > MAX_VALUE ? cleaned.substring(0, MAX_VALUE) : cleaned;
    }

    /** Valor en pesos con separador de miles «.» y decimales «,» solo si los hay; un monto ilegible se muestra tal cual. */
    private static String money(String amount, String currency) {
        String unit = currency == null || currency.isBlank() ? "COP" : singleLine(currency);
        if (amount == null || amount.isBlank()) {
            return "un valor por confirmar";
        }
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(amount.trim()).setScale(2, java.math.RoundingMode.HALF_UP);
            String[] parts = value.abs().toPlainString().split("\\.");
            String grouped = parts[0].replaceAll("\\B(?=(\\d{3})+(?!\\d))", ".");
            String decimals = parts[1].equals("00") ? "" : "," + parts[1];
            return (value.signum() < 0 ? "-" : "") + "$" + grouped + decimals + " " + unit;
        } catch (NumberFormatException e) {
            return singleLine(amount) + " " + unit;
        }
    }

    private static String monthOf(String isoDate) {
        try {
            java.time.LocalDate day = java.time.LocalDate.parse(isoDate.trim());
            return DateTimeFormatter.ofPattern("MM/yyyy").format(day);
        } catch (DateTimeParseException e) {
            return singleLine(isoDate);
        }
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
