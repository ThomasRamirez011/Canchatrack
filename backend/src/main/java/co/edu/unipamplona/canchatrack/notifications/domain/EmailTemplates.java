package co.edu.unipamplona.canchatrack.notifications.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
            default -> {
                return Optional.empty();
            }
        }
        String finalSubject = subject == null || subject.isBlank() ? defaultSubject : singleLine(subject);
        if (finalSubject.length() > MAX_SUBJECT) {
            finalSubject = finalSubject.substring(0, MAX_SUBJECT);
        }
        return Optional.of(new OutboundEmail(recipientEmail.trim(), finalSubject, body + SIGNATURE));
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
