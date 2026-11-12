package co.edu.unipamplona.canchatrack.notifications.application;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.notifications.domain.EmailTemplates;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/**
 * Procesador de la cola de avisos (DDS 11, DAD 8.2): toma los avisos {@code PENDING} o {@code RETRYING} vencidos, los
 * renderiza, los entrega y deja evidencia de cada intento. Entrega al menos una vez: si el proceso muere entre el envío y
 * el registro, el arriendo vence y el aviso se reintenta (un correo repetido es preferible a uno perdido).
 *
 * <p>No es transaccional a propósito: el envío no debe mantener abierta una transacción de base de datos. Cada paso de
 * registro es atómico por su cuenta. Un aviso con falla no detiene a los demás.
 */
@Service
public class NotificationProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationProcessor.class);
    private static final int MAX_ERROR = 500;

    private final NotificationOutbox outbox;
    private final EmailSender sender;
    private final NotificationSettings settings;

    public NotificationProcessor(NotificationOutbox outbox, EmailSender sender, NotificationSettings settings) {
        this.outbox = outbox;
        this.sender = sender;
        this.settings = settings;
    }

    /** Procesa una tanda y devuelve cuántos avisos reclamó (0 cuando no hay nada vencido). */
    public int processDue() {
        List<DueNotification> due = outbox.claimDue(settings.batchSize(), settings.lease());
        for (DueNotification notification : due) {
            try {
                deliver(notification);
            } catch (RuntimeException e) {
                // El registro falló (p. ej. la base no responde): el arriendo vence y el aviso se reintenta solo.
                LOG.error("No se pudo registrar el resultado del aviso {} ({}): {}", notification.id(), notification.type(),
                        e.getClass().getSimpleName());
            }
        }
        return due.size();
    }

    private void deliver(DueNotification n) {
        Optional<OutboundEmail> email = EmailTemplates.render(n.type(), n.recipientEmail(), n.subject(), n.payload(),
                settings.zone());
        if (email.isEmpty()) {
            LOG.warn("Aviso {} sin plantilla o sin destinatario válido (tipo {}): fallo definitivo", n.id(), n.type());
            outbox.markFailed(n.id(), n.attemptNumber(), "NO_TEMPLATE: el tipo '" + n.type() + "' no tiene plantilla", null);
            return;
        }
        String response;
        try {
            response = sender.send(email.get());
        } catch (EmailDeliveryException e) {
            fail(n, e.permanent(), describe(e));
            return;
        } catch (RuntimeException e) {
            fail(n, false, e.getClass().getSimpleName());
            return;
        }
        outbox.markSent(n.id(), n.attemptNumber(), response);
    }

    private void fail(DueNotification n, boolean permanent, String error) {
        Duration retryAfter = permanent ? null : settings.retry().delayAfterFailure(n.attemptNumber()).orElse(null);
        if (retryAfter == null) {
            LOG.warn("Aviso {} ({}) fallido de forma definitiva en el intento {}", n.id(), n.type(), n.attemptNumber());
        }
        outbox.markFailed(n.id(), n.attemptNumber(), error, retryAfter);
    }

    private static String describe(EmailDeliveryException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message;
    }
}
