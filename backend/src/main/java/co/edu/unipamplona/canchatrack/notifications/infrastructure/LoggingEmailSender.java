package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.notifications.application.EmailSender;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/**
 * Remitente simulado para desarrollo y pruebas: no envía nada, solo deja el asunto en el log (el destinatario se enmascara
 * y el cuerpo solo aparece a nivel DEBUG). Es el valor por defecto; producción debe configurar {@code sender=smtp}.
 */
@Component
@ConditionalOnProperty(name = "canchatrack.notifications.sender", havingValue = "log", matchIfMissing = true)
class LoggingEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public String send(OutboundEmail email) {
        String reference = "log-" + UUID.randomUUID();
        LOG.info("[correo simulado {}] a {} · asunto: {}", reference, mask(email.to()), email.subject());
        LOG.debug("[correo simulado {}] cuerpo:\n{}", reference, email.body());
        return reference;
    }

    static String mask(String address) {
        int at = address.indexOf('@');
        if (at <= 1) {
            return "***" + (at < 0 ? "" : address.substring(at));
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
