package co.edu.unipamplona.canchatrack.notifications.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Generador mínimo de invitaciones iCalendar (RFC 5545) para un solo evento. Función pura y determinista: el sello de
 * creación se recibe como argumento. Las horas van en UTC ({@code Z}), así cualquier cliente las muestra en su zona.
 *
 * <p>Los valores de texto se escapan (barra, punto y coma, coma, saltos de línea) y las líneas se pliegan a 75 octetos,
 * como exige el estándar; los caracteres de control se eliminan para que un dato de usuario no pueda inyectar propiedades.
 */
public final class IcsCalendar {

    public static final String REQUEST = "REQUEST";
    public static final String CANCEL = "CANCEL";

    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final String CRLF = "\r\n";
    private static final int MAX_OCTETS = 75;

    private IcsCalendar() {
    }

    /**
     * @param method   {@link #REQUEST} o {@link #CANCEL}
     * @param uid      identificador estable del evento (el mismo en la invitación, sus cambios y la cancelación)
     * @param sequence 0 para la invitación inicial; se incrementa en cada cambio y en la cancelación
     */
    public static CalendarInvite event(String method, String uid, int sequence, Instant stamp, Instant start, Instant end,
                                       String summary, String location, String description) {
        boolean cancel = CANCEL.equals(method);
        StringBuilder out = new StringBuilder();
        line(out, "BEGIN:VCALENDAR");
        line(out, "VERSION:2.0");
        line(out, "PRODID:-//CanchaTrack//Agenda//ES");
        line(out, "CALSCALE:GREGORIAN");
        line(out, "METHOD:" + (cancel ? CANCEL : REQUEST));
        line(out, "BEGIN:VEVENT");
        line(out, "UID:" + token(uid));
        line(out, "DTSTAMP:" + UTC.format(stamp));
        line(out, "SEQUENCE:" + Math.max(sequence, 0));
        line(out, "DTSTART:" + UTC.format(start));
        line(out, "DTEND:" + UTC.format(end));
        line(out, "SUMMARY:" + escape(summary));
        if (location != null && !location.isBlank()) {
            line(out, "LOCATION:" + escape(location));
        }
        if (description != null && !description.isBlank()) {
            line(out, "DESCRIPTION:" + escape(description));
        }
        line(out, "STATUS:" + (cancel ? "CANCELLED" : "CONFIRMED"));
        line(out, "END:VEVENT");
        line(out, "END:VCALENDAR");
        return new CalendarInvite(cancel ? CANCEL : REQUEST, out.toString());
    }

    /** Escapa un valor de tipo TEXT (RFC 5545, 3.3.11) y descarta caracteres de control. */
    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case ';' -> sb.append("\\;");
                case ',' -> sb.append("\\,");
                case '\n' -> sb.append("\\n");
                case '\r', '\t' -> sb.append(' ');
                default -> {
                    if (!Character.isISOControl(c)) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** UID: solo caracteres inocuos. */
    private static String token(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9@._-]", "");
    }

    private static void line(StringBuilder out, String content) {
        // Pliegue a 75 octetos UTF-8 sin partir un carácter; las continuaciones empiezan con un espacio.
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_OCTETS) {
            out.append(content).append(CRLF);
            return;
        }
        StringBuilder chunk = new StringBuilder();
        int used = 0;
        boolean first = true;
        int limit = MAX_OCTETS;
        for (int i = 0; i < content.length(); ) {
            int cp = content.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int size = ch.getBytes(StandardCharsets.UTF_8).length;
            if (used + size > limit) {
                out.append(first ? "" : " ").append(chunk).append(CRLF);
                chunk.setLength(0);
                used = 0;
                first = false;
                limit = MAX_OCTETS - 1; // el espacio inicial cuenta
            }
            chunk.append(ch);
            used += size;
            i += Character.charCount(cp);
        }
        if (chunk.length() > 0) {
            out.append(first ? "" : " ").append(chunk).append(CRLF);
        }
    }
}
