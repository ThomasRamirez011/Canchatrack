package co.edu.unipamplona.canchatrack.scheduling.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.scheduling.application.EventReminderService;

/**
 * Revisa cada pocos minutos los eventos próximos para encolar sus recordatorios (RF-019). Un fallo se registra y se reintenta
 * en la siguiente pasada; el servicio es idempotente. Se apaga con {@code canchatrack.scheduling.reminders.enabled=false}
 * (perfil de pruebas); {@code @EnableScheduling} lo declara el módulo de notificaciones.
 */
@Component
@ConditionalOnProperty(name = "canchatrack.scheduling.reminders.enabled", havingValue = "true", matchIfMissing = true)
class EventReminderScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(EventReminderScheduler.class);

    private final EventReminderService reminders;

    EventReminderScheduler(EventReminderService reminders) {
        this.reminders = reminders;
    }

    @Scheduled(fixedDelayString = "${canchatrack.scheduling.reminders.interval:PT5M}",
            initialDelayString = "${canchatrack.scheduling.reminders.initial-delay:PT1M}")
    void run() {
        try {
            int due = reminders.sendDue();
            if (due > 0) {
                LOG.info("Recordatorios de eventos evaluados: {}", due);
            }
        } catch (RuntimeException e) {
            LOG.error("No fue posible generar los recordatorios de eventos", e);
        }
    }
}
