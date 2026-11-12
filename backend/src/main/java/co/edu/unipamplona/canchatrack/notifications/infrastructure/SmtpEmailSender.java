package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.notifications.application.EmailDeliveryException;
import co.edu.unipamplona.canchatrack.notifications.application.EmailSender;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/**
 * Entrega por SMTP con {@code JavaMailSender} (configurado con {@code spring.mail.*}). Texto plano, sin adjuntos. Un
 * mensaje que el servidor rechaza por su forma o dirección es definitivo; los fallos de conexión, de autenticación o del
 * servidor se reintentan. Solo se activa con {@code canchatrack.notifications.sender=smtp}: si falta
 * {@code spring.mail.host} la aplicación no arranca, a propósito.
 */
@Component
@ConditionalOnProperty(name = "canchatrack.notifications.sender", havingValue = "smtp")
class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mail;
    private final String from;

    SmtpEmailSender(JavaMailSender mail, NotificationProperties properties) {
        if (properties.from() == null || properties.from().isBlank()) {
            throw new IllegalStateException("canchatrack.notifications.from es obligatorio con sender=smtp");
        }
        this.mail = mail;
        this.from = properties.from();
    }

    @Override
    public String send(OutboundEmail email) throws EmailDeliveryException {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email.to());
        message.setSubject(email.subject());
        message.setText(email.body());
        try {
            mail.send(message);
        } catch (MailParseException e) {
            throw new EmailDeliveryException("Mensaje inválido: " + e.getClass().getSimpleName(), true, e);
        } catch (MailException e) {
            throw new EmailDeliveryException("Proveedor de correo: " + e.getClass().getSimpleName(), false, e);
        }
        return "smtp-" + UUID.randomUUID();
    }
}
