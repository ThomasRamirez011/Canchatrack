package co.edu.unipamplona.canchatrack.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.identity.application.SecurityMaintenanceService;

/**
 * Limpia cada madrugada los tokens y contadores inservibles. Un fallo se registra y se reintenta al día siguiente; la
 * limpieza es idempotente. Se apaga con {@code canchatrack.identity.maintenance.enabled=false} (perfil de pruebas);
 * {@code @EnableScheduling} lo declara el módulo de notificaciones.
 */
@Component
@ConditionalOnProperty(name = "canchatrack.identity.maintenance.enabled", havingValue = "true", matchIfMissing = true)
class SecurityMaintenanceScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityMaintenanceScheduler.class);

    private final SecurityMaintenanceService maintenance;

    SecurityMaintenanceScheduler(SecurityMaintenanceService maintenance) {
        this.maintenance = maintenance;
    }

    @Scheduled(cron = "${canchatrack.identity.maintenance.cron:0 30 3 * * *}", zone = "${canchatrack.zone-id:America/Bogota}")
    void run() {
        try {
            SecurityMaintenanceService.Result result = maintenance.purge();
            LOG.info("Limpieza de seguridad: {} refresh tokens, {} tokens de cuenta, {} eventos de límite de tasa",
                    result.refreshTokens(), result.accountTokens(), result.rateLimitEvents());
        } catch (RuntimeException e) {
            LOG.error("No fue posible limpiar los datos de seguridad vencidos", e);
        }
    }
}
