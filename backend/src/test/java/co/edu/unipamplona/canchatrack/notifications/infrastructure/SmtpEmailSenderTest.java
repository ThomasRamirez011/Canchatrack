package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

import co.edu.unipamplona.canchatrack.notifications.domain.CalendarInvite;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/** El adjunto de calendario (RF-020) sin servidor SMTP: se captura el mensaje que se le entregaría. */
class SmtpEmailSenderTest {

    private final List<MimeMessage> mime = new ArrayList<>();
    private final List<SimpleMailMessage> simple = new ArrayList<>();

    private final JavaMailSenderImpl mail = new JavaMailSenderImpl() {
        @Override
        public void send(MimeMessage message) {
            mime.add(message);
        }

        @Override
        public void send(SimpleMailMessage message) {
            simple.add(message);
        }
    };

    private SmtpEmailSender sender() {
        return new SmtpEmailSender(mail, new NotificationProperties("smtp", "club@canchatrack.test", null, null));
    }

    @Test
    void anEmailWithoutCalendarGoesAsPlainText() throws Exception {
        sender().send(new OutboundEmail("madre@familia.test", "Aviso", "Hola"));

        assertThat(mime).isEmpty();
        assertThat(simple).hasSize(1);
        assertThat(simple.get(0).getText()).isEqualTo("Hola");
        assertThat(simple.get(0).getFrom()).isEqualTo("club@canchatrack.test");
    }

    @Test
    void anEmailWithCalendarCarriesTheInviteAsATextCalendarAttachment() throws Exception {
        String ics = "BEGIN:VCALENDAR\r\nMETHOD:REQUEST\r\nEND:VCALENDAR\r\n";
        sender().send(new OutboundEmail("madre@familia.test", "Entrenamiento programado", "Cuerpo del aviso",
                new CalendarInvite("REQUEST", ics)));

        assertThat(simple).isEmpty();
        assertThat(mime).hasSize(1);
        MimeMessage message = mime.get(0);
        assertThat(message.getSubject()).isEqualTo("Entrenamiento programado");
        message.saveChanges(); // lo que hace JavaMailSender antes de entregar: fija los encabezados de cada parte

        List<BodyPart> parts = new ArrayList<>();
        collect((Multipart) message.getContent(), parts);
        BodyPart attachment = parts.stream().filter(p -> "evento.ics".equals(fileName(p))).findFirst().orElseThrow();
        String contentType = attachment.getContentType().toLowerCase();
        assertThat(contentType.contains("text/calendar")).isTrue();
        assertThat(contentType.contains("method=request")).isTrue();
        assertThat(new String(attachment.getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(ics);
        assertThat(parts.stream().anyMatch(p -> isText(p, "Cuerpo del aviso"))).isTrue();
    }

    private static void collect(Multipart multipart, List<BodyPart> out) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.getContent() instanceof Multipart nested) {
                collect(nested, out);
            } else {
                out.add(part);
            }
        }
    }

    private static String fileName(BodyPart part) {
        try {
            return part.getFileName();
        } catch (MessagingException e) {
            return null;
        }
    }

    private static boolean isText(BodyPart part, String expected) {
        try {
            return !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) && expected.equals(part.getContent());
        } catch (MessagingException | IOException e) {
            return false;
        }
    }
}
