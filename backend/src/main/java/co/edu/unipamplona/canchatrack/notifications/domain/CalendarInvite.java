package co.edu.unipamplona.canchatrack.notifications.domain;

/**
 * Invitación de calendario (iCalendar, RFC 5545) que acompaña al correo de un evento (RF-020).
 *
 * @param method  {@code REQUEST} (invitar o actualizar) o {@code CANCEL}; también va dentro del contenido
 * @param content texto {@code text/calendar} completo, con saltos de línea CRLF
 */
public record CalendarInvite(String method, String content) {
}
