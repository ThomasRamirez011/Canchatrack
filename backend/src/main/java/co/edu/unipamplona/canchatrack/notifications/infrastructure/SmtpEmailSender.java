package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

import co.edu.unipamplona.canchatrack.notifications.application.EmailDeliveryException;
import co.edu.unipamplona.canchatrack.notifications.application.EmailSender;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/**
 * Entrega por SMTP con {@code JavaMailSender} (configurado con {@code spring.mail.*}). Texto plano; solo los avisos de la
 * agenda llevan una invitación {@code .ics} como adjunto {@code text/calendar} (RF-020), nunca documentos. Un
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
        try {
            if (email.calendar() == null) {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setFrom(from);
                message.setTo(email.to());
                message.setSubject(email.subject());
                message.setText(email.body());
                mail.send(message);
            } else {
                mail.send(withCalendar(email));
            }
        } catch (MailParseException e) {
            throw new EmailDeliveryException("Mensaje inválido: " + e.getClass().getSimpleName(), true, e);
        } catch (MailException e) {
            throw new EmailDeliveryException("Proveedor de correo: " + e.getClass().getSimpleName(), false, e);
        } catch (MessagingException e) {
            throw new EmailDeliveryException("Mensaje inválido: " + e.getClass().getSimpleName(), true, e);
        }
        return "smtp-" + UUID.randomUUID();
    }

    /** Correo con el texto y la invitación de calendario como adjunto {@code text/calendar; method=...}. */
    MimeMessage withCalendar(OutboundEmail email) throws MessagingException {
        MimeMessage message = mail.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from);
        helper.setTo(email.to());
        helper.setSubject(email.subject());
        helper.setText(email.body());
        helper.addAttachment("evento.ics",
                new ByteArrayResource(email.calendar().content().getBytes(StandardCharsets.UTF_8)),
                "text/calendar; charset=UTF-8; method=" + email.calendar().method());
        return message;
    }
}
