package co.edu.unipamplona.canchatrack.documents.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.documents.application.ExpiryAlertService;

/**
 * Revisa cada día los documentos por vencer (RF-010). Un fallo se registra y se reintenta al día siguiente; el servicio es
 * idempotente, así que repetir la corrida no duplica avisos. Se apaga con {@code canchatrack.documents.alerts.enabled=false}
 * (perfil de pruebas); {@code @EnableScheduling} lo declara el módulo de notificaciones.
 */
@Component
@ConditionalOnProperty(name = "canchatrack.documents.alerts.enabled", havingValue = "true", matchIfMissing = true)
class ExpiryAlertScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ExpiryAlertScheduler.class);

    private final ExpiryAlertService alerts;

    ExpiryAlertScheduler(ExpiryAlertService alerts) {
        this.alerts = alerts;
    }

    @Scheduled(cron = "${canchatrack.documents.alerts.cron:0 0 6 * * *}", zone = "${canchatrack.zone-id:America/Bogota}")
    void run() {
        try {
            int due = alerts.notifyExpiring();
            LOG.info("Alertas de vencimiento de documentos evaluadas: {}", due);
        } catch (RuntimeException e) {
            LOG.error("No fue posible generar las alertas de vencimiento de documentos", e);
        }
    }
}
