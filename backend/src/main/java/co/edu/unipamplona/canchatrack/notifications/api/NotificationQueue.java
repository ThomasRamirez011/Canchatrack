package co.edu.unipamplona.canchatrack.notifications.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Fachada pública: encola avisos por correo en la misma transacción del negocio que los origina. El envío lo hace el
 * procesador de notificaciones (reintentos y evidencia de entrega); aquí solo se deja el aviso pendiente.
 *
 * <p>El {@code payload} es solo datos de plantilla: <b>nunca</b> datos de salud ni adjuntos (RN-17).
 */
public interface NotificationQueue {

    /**
     * Encola un aviso para cada cuenta activa indicada.
     *
     * @param dedupScope parte de la clave de deduplicación: el mismo tipo, entidad, cuenta y ámbito se encola una sola vez
     *                   (un reintento no duplica el aviso; un aviso nuevo sobre la misma entidad usa otro ámbito)
     */
    void enqueue(String type, Collection<UUID> recipientUserIds, String subject, Map<String, String> payload,
                 String relatedEntityType, UUID relatedEntityId, String dedupScope);
}
