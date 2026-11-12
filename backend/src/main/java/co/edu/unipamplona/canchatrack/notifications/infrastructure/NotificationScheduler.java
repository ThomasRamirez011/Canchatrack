package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.notifications.application.NotificationProcessor;

/**
 * Dispara el procesador de la cola a intervalo fijo. {@code fixedDelay} espera a que termine la pasada anterior, así que
 * en una instancia nunca se solapan; entre instancias lo evita el arriendo de la cola.
 */
@Component
@ConditionalOnProperty(name = "canchatrack.notifications.processor.enabled", havingValue = "true", matchIfMissing = true)
class NotificationScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationProcessor processor;

    NotificationScheduler(NotificationProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${canchatrack.notifications.processor.interval:PT10S}",
            initialDelayString = "${canchatrack.notifications.processor.interval:PT10S}")
    void run() {
        try {
            int handled = processor.processDue();
            if (handled > 0) {
                LOG.debug("Procesados {} avisos", handled);
            }
        } catch (RuntimeException e) {
            // Una pasada fallida (p. ej. la base no responde) no debe matar la tarea periódica.
            LOG.error("Falló la pasada del procesador de notificaciones: {}", e.getClass().getSimpleName());
        }
    }
}
