package co.edu.unipamplona.canchatrack.payments.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.payments.application.ArrearsNoticeService;

/**
 * Revisa cada día los cargos vencidos (RF-041). Un fallo se registra y se reintenta al día siguiente; el servicio es
 * idempotente. Se apaga con {@code canchatrack.payments.arrears-notices.enabled=false} (perfil de pruebas).
 */
@Component
@ConditionalOnProperty(name = "canchatrack.payments.arrears-notices.enabled", havingValue = "true", matchIfMissing = true)
class ArrearsNoticeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ArrearsNoticeScheduler.class);

    private final ArrearsNoticeService notices;

    ArrearsNoticeScheduler(ArrearsNoticeService notices) {
        this.notices = notices;
    }

    @Scheduled(cron = "${canchatrack.payments.arrears-notices.cron:0 0 8 * * *}", zone = "${canchatrack.zone-id:America/Bogota}")
    void run() {
        try {
            int due = notices.notifyOverdue();
            LOG.info("Avisos de mora evaluados: {}", due);
        } catch (RuntimeException e) {
            LOG.error("No fue posible generar los avisos de mora", e);
        }
    }
}
