package co.edu.unipamplona.canchatrack.notifications.application;

import java.util.Map;
import java.util.UUID;

/**
 * Aviso reclamado para enviar.
 *
 * @param attemptNumber número del intento que se está haciendo (1 es el primero)
 */
public record DueNotification(UUID id, String type, String recipientEmail, String subject, Map<String, String> payload,
                              int attemptNumber) {
}
