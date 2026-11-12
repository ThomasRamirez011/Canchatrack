package co.edu.unipamplona.canchatrack.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.notifications.application.DueNotification;
import co.edu.unipamplona.canchatrack.notifications.application.EmailDeliveryException;
import co.edu.unipamplona.canchatrack.notifications.application.EmailSender;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationOutbox;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationProcessor;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationSettings;
import co.edu.unipamplona.canchatrack.notifications.domain.EmailTemplates;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;
import co.edu.unipamplona.canchatrack.notifications.domain.RetryPolicy;

/** Reglas puras del módulo notifications: política de reintentos, plantillas y decisiones del procesador. */
class NotificationRulesTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final RetryPolicy POLICY = new RetryPolicy(5, Duration.ofMinutes(1), Duration.ofHours(6));

    // --- RetryPolicy ---------------------------------------------------------------------------------------------

    @Test
    void retryDelaysGrowByFourAndStopAtTheLastAttempt() {
        assertThat(POLICY.delayAfterFailure(1)).contains(Duration.ofMinutes(1));
        assertThat(POLICY.delayAfterFailure(2)).contains(Duration.ofMinutes(4));
        assertThat(POLICY.delayAfterFailure(3)).contains(Duration.ofMinutes(16));
        assertThat(POLICY.delayAfterFailure(4)).contains(Duration.ofMinutes(64));
        assertThat(POLICY.delayAfterFailure(5)).as("el quinto intento es el último").isEmpty();
        assertThat(POLICY.delayAfterFailure(9)).isEmpty();
    }

    @Test
    void retryDelayNeverExceedsTheCap() {
        RetryPolicy capped = new RetryPolicy(10, Duration.ofMinutes(1), Duration.ofMinutes(30));
        assertThat(capped.delayAfterFailure(3)).contains(Duration.ofMinutes(16));
        assertThat(capped.delayAfterFailure(4)).contains(Duration.ofMinutes(30));
        assertThat(capped.delayAfterFailure(9)).contains(Duration.ofMinutes(30));
    }

    @Test
    void aSingleAttemptPolicyNeverRetries() {
        assertThat(new RetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)).delayAfterFailure(1)).isEmpty();
    }

    @Test
    void invalidPoliciesAreRejected() {
        assertThatThrownBy(() -> new RetryPolicy(0, Duration.ofMinutes(1), Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ZERO, Duration.ofHours(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, Duration.ofMinutes(5), Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> POLICY.delayAfterFailure(0)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- EmailTemplates ------------------------------------------------------------------------------------------

    @Test
    void callupEmailShowsTheKickoffInTheClubTimeZone() {
        Map<String, String> payload = payload("playerName", "Ana Pérez", "opponent", "Club Rival",
                "startAt", "2026-12-05T20:00:00Z", "meetingAt", "2026-12-05T18:30:00Z", "meetingPoint", "Cancha 2");

        OutboundEmail email = EmailTemplates.render("MATCH_CALLUP", "padre@example.com", "Convocatoria a partido", payload, BOGOTA)
                .orElseThrow();

        assertThat(email.to()).isEqualTo("padre@example.com");
        assertThat(email.subject()).isEqualTo("Convocatoria a partido");
        assertThat(email.body()).contains("Ana Pérez", "Club Rival", "05/12/2026 15:00", "05/12/2026 13:30", "Cancha 2",
                "autorizar su participación");
    }

    @Test
    void callupWithoutOptionalDataUsesNeutralWording() {
        OutboundEmail email = EmailTemplates.render("MATCH_CALLUP", "a@b.co", null, Map.of(), BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("Convocatoria a partido");
        assertThat(email.body()).contains("su jugador", "el rival por confirmar", "fecha por confirmar")
                .doesNotContain("null");
    }

    @Test
    void removedAndCancelledEmailsCarryTheirOwnContent() {
        Map<String, String> payload = payload("playerName", "Ana Pérez", "opponent", "Club Rival",
                "startAt", "2026-12-05T20:00:00Z", "reason", "Lluvia intensa");

        OutboundEmail removed = EmailTemplates.render("MATCH_CALLUP_REMOVED", "a@b.co", "Cambio en la convocatoria", payload, BOGOTA)
                .orElseThrow();
        OutboundEmail cancelled = EmailTemplates.render("MATCH_CANCELLED", "a@b.co", "Partido cancelado", payload, BOGOTA)
                .orElseThrow();

        assertThat(removed.body()).contains("Ana Pérez", "ya no hace parte", "Club Rival").doesNotContain("Lluvia");
        assertThat(cancelled.body()).contains("fue cancelado", "Motivo: Lluvia intensa", "05/12/2026 15:00");
    }

    @Test
    void lineBreaksInDataCannotInjectHeadersOrFakeLines() {
        Map<String, String> payload = payload("opponent", "Rival\r\nBcc: robo@example.com", "reason", "Motivo\n\nFalso aviso",
                "startAt", "2026-12-05T20:00:00Z");

        OutboundEmail email = EmailTemplates.render("MATCH_CANCELLED", "a@b.co", "Asunto\r\nBcc: robo@example.com", payload, BOGOTA)
                .orElseThrow();

        assertThat(email.subject()).doesNotContain("\r").doesNotContain("\n");
        assertThat(email.body()).contains("Rival Bcc: robo@example.com").contains("Motivo: Motivo Falso aviso");
    }

    @Test
    void anUnparseableDateIsShownAsIsAndLongValuesAreCut() {
        Map<String, String> payload = payload("opponent", "x".repeat(500), "startAt", "pronto");

        OutboundEmail email = EmailTemplates.render("MATCH_CALLUP_REMOVED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.body()).contains("pronto").doesNotContain("x".repeat(201));
    }

    @Test
    void documentExpiryNoticeNamesTheDocumentThePlayerAndTheDateButNothingClinical() {
        Map<String, String> payload = Map.of("playerName", "Ana Gómez", "documentName", "Certificado médico",
                "expiresOn", "2026-12-01", "daysLeft", "7");

        OutboundEmail email = EmailTemplates.render("DOCUMENT_EXPIRY", "padre@example.com", "Documento por vencer", payload, BOGOTA)
                .orElseThrow();

        assertThat(email.subject()).isEqualTo("Documento por vencer");
        assertThat(email.body()).contains("Certificado médico", "Ana Gómez", "01/12/2026", "faltan 7 días");
        assertThat(EmailTemplates.render("DOCUMENT_EXPIRY", "a@b.co", null, Map.of("daysLeft", "1", "expiresOn", "2026-12-01"), BOGOTA)
                .orElseThrow().body()).contains("faltan 1 día)");
        assertThat(EmailTemplates.render("DOCUMENT_EXPIRY", "a@b.co", null, Map.of("daysLeft", "0"), BOGOTA)
                .orElseThrow().body()).contains("es hoy");
        // Un valor que no es un número de días no se copia al correo tal cual.
        assertThat(EmailTemplates.render("DOCUMENT_EXPIRY", "a@b.co", null, Map.of("daysLeft", "7\nBcc: x@y.z"), BOGOTA)
                .orElseThrow().body().contains("Bcc")).isFalse();
    }

    private static Map<String, String> eventPayload() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("eventId", "7b1e1c4a-6a4b-4b5e-9b64-0a5d9f1f2c10");
        payload.put("eventType", "TRAINING");
        payload.put("categoryName", "Sub-12");
        payload.put("venueName", "Cancha Municipal");
        payload.put("venueAddress", "Calle 5 # 3-20");
        payload.put("coachName", "Carlos Ruiz");
        payload.put("startAt", "2026-12-01T22:00:00Z");   // 17:00 en Bogotá
        payload.put("endAt", "2026-12-01T23:30:00Z");     // 18:30
        payload.put("meetingAt", "2026-12-01T21:30:00Z");
        payload.put("meetingPoint", "Puerta principal");
        payload.put("instructions", "Traer uniforme de color azul");
        payload.put("appUrl", "https://app.club.example");
        payload.put("stamp", "2026-11-12T15:00:00Z");
        return payload;
    }

    @Test
    void scheduledEventEmailCarriesTheDetailsAndACalendarInvitation() {
        OutboundEmail email = EmailTemplates.render("EVENT_SCHEDULED", "padre@example.com",
                "Entrenamiento programado · Sub-12", eventPayload(), BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("Entrenamiento programado · Sub-12");
        assertThat(email.body()).contains("entrenamiento de la categoría Sub-12", "01/12/2026 17:00 a 18:30",
                "Cancha Municipal (Calle 5 # 3-20)", "Entrenador: Carlos Ruiz", "Concentración: 01/12/2026 16:30 en Puerta principal",
                "Indicaciones: Traer uniforme de color azul", "https://app.club.example");
        assertThat(email.calendar().method()).isEqualTo("REQUEST");
        assertThat(email.calendar().content()).contains("METHOD:REQUEST", "DTSTART:20261201T220000Z", "DTEND:20261201T233000Z",
                "UID:7b1e1c4a-6a4b-4b5e-9b64-0a5d9f1f2c10@canchatrack", "SEQUENCE:0", "STATUS:CONFIRMED");
    }

    @Test
    void cancelledEventEmailIsHighlightedAndCancelsTheInvitation() {
        Map<String, String> payload = eventPayload();
        payload.put("reason", "Lluvia\nBcc: x@y.z");

        OutboundEmail email = EmailTemplates.render("EVENT_CANCELLED", "padre@example.com", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).startsWith("CANCELADO:");
        assertThat(email.body()).contains("CANCELACIÓN", "fue cancelado", "Motivo: Lluvia Bcc: x@y.z");
        assertThat(email.body().contains("\nBcc")).isFalse();
        assertThat(email.calendar().method()).isEqualTo("CANCEL");
        assertThat(email.calendar().content()).contains("METHOD:CANCEL", "STATUS:CANCELLED", "SEQUENCE:1");
    }

    @Test
    void reminderEmailSaysHowMuchTimeIsLeftAndHasNoInvitation() {
        Map<String, String> payload = eventPayload();
        payload.put("hoursBefore", "2");

        OutboundEmail email = EmailTemplates.render("EVENT_REMINDER", "padre@example.com", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).startsWith("Recordatorio:");
        assertThat(email.body()).contains("Faltan 2 horas para el entrenamiento", "Sub-12");
        assertThat(email.calendar()).isNull();
        payload.put("hoursBefore", "1");
        assertThat(EmailTemplates.render("EVENT_REMINDER", "a@b.co", null, payload, BOGOTA).orElseThrow().body())
                .contains("Faltan 1 hora para");
        payload.put("hoursBefore", "2\nBcc: x@y.z");
        assertThat(EmailTemplates.render("EVENT_REMINDER", "a@b.co", null, payload, BOGOTA).orElseThrow().body().contains("Bcc")).isFalse();
    }

    @Test
    void eventEmailsNeverNameOrDescribeAnyPlayer() {
        for (String type : List.of("EVENT_SCHEDULED", "EVENT_CANCELLED", "EVENT_REMINDER")) {
            String body = EmailTemplates.render(type, "a@b.co", null, eventPayload(), BOGOTA).orElseThrow().body();
            assertThat(body.toLowerCase().contains("jugador")).isFalse();
            assertThat(body.toLowerCase().contains("salud")).isFalse();
        }
    }

    @Test
    void anEventEmailWithoutTheMinimumToBuildAnInvitationStillGoesOut() {
        Map<String, String> payload = eventPayload();
        payload.remove("stamp");

        OutboundEmail email = EmailTemplates.render("EVENT_SCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.calendar()).isNull();
        assertThat(email.body()).contains("Sub-12");
        assertThat(EmailTemplates.render("EVENT_SCHEDULED", "a@b.co", null, Map.of(), BOGOTA).orElseThrow().body())
                .contains("fecha por confirmar");
    }

    @Test
    void anEventSpanningTwoDaysShowsBothDates() {
        Map<String, String> payload = eventPayload();
        payload.put("endAt", "2026-12-02T14:00:00Z");

        assertThat(EmailTemplates.render("EVENT_SCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow().body())
                .contains("01/12/2026 17:00 a 02/12/2026 09:00");
    }

    @Test
    void unknownTypesAndMissingRecipientsAreNotRendered() {
        assertThat(EmailTemplates.render("NO_SUCH_TYPE", "a@b.co", "s", Map.of(), BOGOTA)).isEmpty();
        assertThat(EmailTemplates.render(null, "a@b.co", "s", Map.of(), BOGOTA)).isEmpty();
        assertThat(EmailTemplates.render("MATCH_CALLUP", " ", "s", Map.of(), BOGOTA)).isEmpty();
        assertThat(EmailTemplates.render("MATCH_CALLUP", null, "s", Map.of(), BOGOTA)).isEmpty();
    }

    // --- NotificationProcessor -----------------------------------------------------------------------------------

    @Test
    void successfulDeliveryIsRecordedOnce() {
        Harness h = new Harness();
        h.outbox.due.add(h.notification("MATCH_CALLUP", "ok@example.com", 1));

        assertThat(h.processor().processDue()).isEqualTo(1);

        assertThat(h.sender.sent).hasSize(1);
        assertThat(h.outbox.events).containsExactly("SENT:1:ref-1");
    }

    @Test
    void transientFailureSchedulesTheNextAttemptWithBackoff() {
        Harness h = new Harness();
        h.sender.next = new EmailDeliveryException("timeout", false, null);
        h.outbox.due.add(h.notification("MATCH_CALLUP", "x@example.com", 2));

        h.processor().processDue();

        assertThat(h.outbox.events).containsExactly("FAILED:2:timeout:PT4M");
    }

    @Test
    void permanentFailureIsNeverRetried() {
        Harness h = new Harness();
        h.sender.next = new EmailDeliveryException("dirección inválida", true, null);
        h.outbox.due.add(h.notification("MATCH_CALLUP", "x@example.com", 1));

        h.processor().processDue();

        assertThat(h.outbox.events).containsExactly("FAILED:1:dirección inválida:definitivo");
    }

    @Test
    void theLastAttemptEndsInADefinitiveFailure() {
        Harness h = new Harness();
        h.sender.next = new EmailDeliveryException("timeout", false, null);
        h.outbox.due.add(h.notification("MATCH_CALLUP", "x@example.com", 5));

        h.processor().processDue();

        assertThat(h.outbox.events).containsExactly("FAILED:5:timeout:definitivo");
    }

    @Test
    void anUnexpectedSenderErrorCountsAsTransientAndDoesNotLeakItsMessage() {
        Harness h = new Harness();
        h.sender.nextRuntime = new IllegalStateException("clave=secreta");
        h.outbox.due.add(h.notification("MATCH_CALLUP", "x@example.com", 1));

        h.processor().processDue();

        assertThat(h.outbox.events).containsExactly("FAILED:1:IllegalStateException:PT1M");
    }

    @Test
    void aTypeWithoutTemplateFailsWithoutCallingTheSender() {
        Harness h = new Harness();
        h.outbox.due.add(h.notification("NO_SUCH_TYPE", "x@example.com", 1));

        h.processor().processDue();

        assertThat(h.sender.sent).isEmpty();
        assertThat(h.outbox.events).hasSize(1);
        assertThat(h.outbox.events.get(0)).startsWith("FAILED:1:NO_TEMPLATE").endsWith(":definitivo");
    }

    @Test
    void oneBrokenNotificationDoesNotStopTheBatch() {
        Harness h = new Harness();
        h.outbox.failOnRecordFor = "primero@example.com";
        h.outbox.due.add(h.notification("MATCH_CALLUP", "primero@example.com", 1));
        h.outbox.due.add(h.notification("MATCH_CALLUP", "segundo@example.com", 1));

        assertThat(h.processor().processDue()).isEqualTo(2);

        assertThat(h.sender.sent).extracting(OutboundEmail::to).containsExactly("primero@example.com", "segundo@example.com");
        assertThat(h.outbox.events).as("solo el segundo quedó registrado").hasSize(1);
    }

    @Test
    void anEmptyQueueDoesNothing() {
        Harness h = new Harness();
        assertThat(h.processor().processDue()).isZero();
        assertThat(h.sender.sent).isEmpty();
    }

    // --- soportes ------------------------------------------------------------------------------------------------

    private static Map<String, String> payload(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private static final class Harness {

        final FakeOutbox outbox = new FakeOutbox();
        final FakeSender sender = new FakeSender();

        NotificationProcessor processor() {
            return new NotificationProcessor(outbox, sender, new NotificationSettings(50, Duration.ofMinutes(5), BOGOTA, POLICY));
        }

        DueNotification notification(String type, String email, int attempt) {
            DueNotification n = new DueNotification(UUID.randomUUID(), type, email, null,
                    payload("opponent", "Club Rival", "startAt", "2026-12-05T20:00:00Z"), attempt);
            outbox.recipients.put(n.id(), email);
            return n;
        }
    }

    private static final class FakeOutbox implements NotificationOutbox {

        final List<DueNotification> due = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        String failOnRecordFor;

        @Override
        public List<DueNotification> claimDue(int limit, Duration lease) {
            List<DueNotification> batch = new ArrayList<>(due);
            due.clear();
            return batch;
        }

        @Override
        public void markSent(UUID id, int attemptNumber, String providerResponse) {
            guard(id);
            events.add("SENT:" + attemptNumber + ":" + providerResponse);
        }

        @Override
        public void markFailed(UUID id, int attemptNumber, String error, Duration retryAfter) {
            guard(id);
            String shortError = error.startsWith("NO_TEMPLATE") ? "NO_TEMPLATE" : error;
            events.add("FAILED:" + attemptNumber + ":" + shortError + ":" + (retryAfter == null ? "definitivo" : retryAfter.toString()));
        }

        private void guard(UUID id) {
            if (failOnRecordFor != null && recipients.getOrDefault(id, "").equals(failOnRecordFor)) {
                throw new IllegalStateException("la base no responde");
            }
        }

        final Map<UUID, String> recipients = new LinkedHashMap<>();
    }

    private static final class FakeSender implements EmailSender {

        final List<OutboundEmail> sent = new ArrayList<>();
        EmailDeliveryException next;
        RuntimeException nextRuntime;
        private int counter;

        @Override
        public String send(OutboundEmail email) throws EmailDeliveryException {
            sent.add(email);
            if (next != null) {
                throw next;
            }
            if (nextRuntime != null) {
                throw nextRuntime;
            }
            counter++;
            return "ref-" + counter;
        }
    }
}
