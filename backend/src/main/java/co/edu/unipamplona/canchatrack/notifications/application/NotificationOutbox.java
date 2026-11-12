package co.edu.unipamplona.canchatrack.notifications.application;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Puerto de salida sobre la cola persistente de avisos (tablas {@code notifications} y {@code delivery_attempts}). */
public interface NotificationOutbox {

    /**
     * Reclama hasta {@code limit} avisos vencidos, sin bloquear a otros procesadores: cada aviso reclamado queda
     * arrendado durante {@code lease} (no se vuelve a reclamar hasta que el arriendo venza), así que el envío se hace fuera
     * de la transacción de la base y dos instancias nunca toman el mismo aviso.
     */
    List<DueNotification> claimDue(int limit, Duration lease);

    /** Registra el intento exitoso y pasa el aviso a {@code SENT}. Ignora un aviso que ya no está pendiente. */
    void markSent(UUID id, int attemptNumber, String providerResponse);

    /**
     * Registra el intento fallido. Con {@code retryAfter} el aviso queda {@code RETRYING} para esa espera; sin él, el
     * fallo es definitivo y queda {@code FAILED}.
     */
    void markFailed(UUID id, int attemptNumber, String error, Duration retryAfter);
}
