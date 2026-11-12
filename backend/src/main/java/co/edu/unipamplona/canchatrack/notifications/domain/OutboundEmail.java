package co.edu.unipamplona.canchatrack.notifications.domain;

/**
 * Correo ya renderizado, listo para entregar al proveedor. Cuerpo en texto plano. Lleva, como mucho, una invitación de
 * calendario con datos del evento (RF-020); nunca documentos ni datos de salud (RN-17).
 *
 * @param calendar invitación {@code .ics} opcional
 */
public record OutboundEmail(String to, String subject, String body, CalendarInvite calendar) {

    public OutboundEmail(String to, String subject, String body) {
        this(to, subject, body, null);
    }
}
