package co.edu.unipamplona.canchatrack.notifications.domain;

/** Correo ya renderizado, listo para entregar al proveedor. Solo texto plano: sin adjuntos ni datos de salud (RN-17). */
public record OutboundEmail(String to, String subject, String body) {
}
