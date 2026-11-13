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
    void cancelledEventInvitationUsesTheSequenceFromThePayload() {
        Map<String, String> payload = eventPayload();
        payload.put("sequence", "4");

        OutboundEmail email = EmailTemplates.render("EVENT_CANCELLED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.calendar().content()).contains("SEQUENCE:4");
        payload.put("sequence", "4\r\nBcc: x@y.z");
        assertThat(EmailTemplates.render("EVENT_CANCELLED", "a@b.co", null, payload, BOGOTA).orElseThrow().calendar().content())
                .contains("SEQUENCE:1").doesNotContain("Bcc");
    }

    @Test
    void rescheduledEventEmailShowsThePreviousAndNewScheduleAndUpdatesTheInvitation() {
        Map<String, String> payload = eventPayload();
        payload.put("previousStartAt", "2026-11-30T22:00:00Z");   // 30/11/2026 17:00 en Bogotá
        payload.put("reason", "Cancha en mantenimiento\nBcc: x@y.z");
        payload.put("sequence", "2");

        OutboundEmail email = EmailTemplates.render("EVENT_RESCHEDULED", "padre@example.com", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("REPROGRAMADO: entrenamiento · Sub-12");
        assertThat(email.body()).contains("REPROGRAMACIÓN", "que estaba para el 30/11/2026 17:00", "Motivo: Cancha en mantenimiento Bcc: x@y.z",
                "Nueva programación:", "01/12/2026 17:00 a 18:30", "Cancha Municipal (Calle 5 # 3-20)");
        assertThat(email.body().contains("\nBcc")).isFalse();
        assertThat(email.calendar().method()).isEqualTo("REQUEST");
        assertThat(email.calendar().content()).contains("METHOD:REQUEST", "DTSTART:20261201T220000Z", "SEQUENCE:2", "STATUS:CONFIRMED",
                "UID:7b1e1c4a-6a4b-4b5e-9b64-0a5d9f1f2c10@canchatrack");
    }

    @Test
    void aRescheduleInvitationNeverGoesOutWithSequenceZero() {
        Map<String, String> payload = eventPayload();

        assertThat(EmailTemplates.render("EVENT_RESCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow().calendar().content())
                .contains("SEQUENCE:1");
        payload.put("sequence", "0");
        assertThat(EmailTemplates.render("EVENT_RESCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow().calendar().content())
                .contains("SEQUENCE:1");
    }

    @Test
    void rescheduledMatchEmailCarriesTheNewDateTheMeetingPointAndNoInvitation() {
        Map<String, String> payload = payload("playerName", "Ana Pérez", "opponent", "Club Rival",
                "startAt", "2026-12-12T20:00:00Z", "previousStartAt", "2026-12-05T20:00:00Z",
                "meetingAt", "2026-12-12T18:30:00Z", "meetingPoint", "Puerta principal", "reason", "Cambio del campeonato");

        OutboundEmail email = EmailTemplates.render("MATCH_RESCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("Partido reprogramado");
        assertThat(email.body()).contains("Ana Pérez", "Club Rival", "que estaba para el 05/12/2026 15:00",
                "se reprogramó para el 12/12/2026 15:00", "Punto de encuentro: 12/12/2026 13:30 en Puerta principal",
                "Motivo: Cambio del campeonato", "comuníquese con el club");
        assertThat(email.calendar()).isNull();
        assertThat(email.body().toLowerCase().contains("salud")).isFalse();
    }

    @Test
    void matchEmailsNameTheRivalAndTheCalendarEntryToo() {
        Map<String, String> payload = eventPayload();
        payload.put("eventType", "TOURNAMENT_MATCH");
        payload.put("opponent", "Club Rival\nBcc: x@y.z");
        payload.put("hoursBefore", "48");

        OutboundEmail scheduled = EmailTemplates.render("EVENT_SCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow();
        OutboundEmail reminder = EmailTemplates.render("EVENT_REMINDER", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(scheduled.subject()).isEqualTo("Partido de torneo programado · Sub-12");
        assertThat(scheduled.body()).contains("partido de torneo de la categoría Sub-12", "Rival: Club Rival Bcc: x@y.z.");
        assertThat(scheduled.body().contains("\nBcc")).isFalse();
        assertThat(scheduled.calendar().content()).contains("SUMMARY:Partido de torneo · Sub-12 vs Club Rival Bcc: x@y.z", "Rival: Club Rival");
        assertThat(reminder.body()).contains("Faltan 48 horas para el partido de torneo", "Rival: Club Rival");
        assertThat(reminder.calendar()).isNull();
    }

    @Test
    void committeeEmailsTellTheOutcomeWithoutAnyDetailOfTheMinute() {
        Map<String, String> assigned = new LinkedHashMap<>();
        assigned.put("playerName", "Ana Ríos");
        assigned.put("categoryName", "Sub-12\nBcc: x@y.z");

        OutboundEmail ok = EmailTemplates.render("COMMITTEE_ASSIGNED", "madre@example.com", null, assigned, BOGOTA).orElseThrow();
        assertThat(ok.subject()).isEqualTo("Categoría asignada");
        assertThat(ok.body()).contains("inscripción de Ana Ríos", "la categoría Sub-12 Bcc: x@y.z", "matrícula");
        assertThat(ok.body().contains("\nBcc")).isFalse();
        assertThat(ok.calendar()).isNull();

        OutboundEmail no = EmailTemplates.render("COMMITTEE_REJECTED", "madre@example.com", null,
                Map.of("playerName", "Ana Ríos", "reason", "motivo secreto"), BOGOTA).orElseThrow();
        assertThat(no.subject()).isEqualTo("Resultado de la inscripción");
        assertThat(no.body()).contains("inscripción de Ana Ríos", "comuníquese directamente con el club");
        assertThat(no.body()).doesNotContain("motivo secreto");
    }

    @Test
    void arrearsEmailNamesTheChargeTheBalanceAndTheDaysLate() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("playerName", "Ana Ríos");
        payload.put("concept", "Mensualidad");
        payload.put("period", "2026-11-01");
        payload.put("dueDate", "2026-11-05");
        payload.put("outstanding", "80000.00");
        payload.put("currency", "COP");
        payload.put("daysOverdue", "1");
        payload.put("appUrl", "https://club.example.co");

        OutboundEmail email = EmailTemplates.render("ARREARS_NOTICE", "madre@example.com", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("Saldo pendiente vencido");
        assertThat(email.body()).contains("«Mensualidad» de Ana Ríos (periodo 11/2026) venció el 05/11/2026",
                "saldo pendiente de $80.000 COP", "Llevan 1 día de retraso", "no puede ser convocado a partidos",
                "https://club.example.co");
        assertThat(email.calendar()).isNull();
    }

    @Test
    void arrearsAmountsAreGroupedAndTolerateBadData() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("playerName", "Ana Ríos");
        payload.put("concept", "Matrícula");
        payload.put("dueDate", "2026-11-05");
        payload.put("outstanding", "1234567.5");
        payload.put("daysOverdue", "x\nBcc: a@b.c");

        String body = EmailTemplates.render("ARREARS_NOTICE", "a@b.co", null, payload, BOGOTA).orElseThrow().body();
        assertThat(body).contains("$1.234.567,50 COP").doesNotContain("Llevan").doesNotContain("periodo");

        payload.put("outstanding", "abc");
        assertThat(EmailTemplates.render("ARREARS_NOTICE", "a@b.co", null, payload, BOGOTA).orElseThrow().body())
                .contains("abc COP");
        payload.remove("outstanding");
        assertThat(EmailTemplates.render("ARREARS_NOTICE", "a@b.co", null, payload, BOGOTA).orElseThrow().body())
                .contains("un valor por confirmar");
        payload.put("outstanding", "999");
        assertThat(EmailTemplates.render("ARREARS_NOTICE", "a@b.co", null, payload, BOGOTA).orElseThrow().body())
                .contains("$999 COP");
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
        for (String type : List.of("EVENT_SCHEDULED", "EVENT_CANCELLED", "EVENT_REMINDER", "EVENT_RESCHEDULED")) {
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

    // ---------- series de sesiones (RF-014, RF-016) ----------

    private static final String FIRST = "11111111-1111-4111-8111-111111111111";
    private static final String SECOND = "22222222-2222-4222-8222-222222222222";

    private static Map<String, String> seriesPayload() {
        Map<String, String> payload = eventPayload();
        payload.remove("eventId");
        payload.remove("startAt");
        payload.remove("endAt");
        payload.remove("meetingAt");
        payload.put("seriesId", "33333333-3333-4333-8333-333333333333");
        payload.put("count", "2");
        payload.put("occurrences", FIRST + "|2026-12-07T22:00:00Z|2026-12-08T00:00:00Z|0;" + SECOND + "|2026-12-09T22:00:00Z|2026-12-10T00:00:00Z|0");
        return payload;
    }

    @Test
    void aSeriesScheduledEmailListsEverySessionAndAttachesOneInvitationForAll() {
        OutboundEmail email = EmailTemplates.render("EVENT_SERIES_SCHEDULED", "padre@example.com", null, seriesPayload(), BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("Serie de entrenamientos programada · Sub-12");
        assertThat(email.body()).contains("serie de 2 entrenamientos de la categoría Sub-12", "- lun 07/12/2026 17:00 a 19:00",
                "- mié 09/12/2026 17:00 a 19:00", "Cancha Municipal (Calle 5 # 3-20)", "Carlos Ruiz", "Traer uniforme de color azul",
                "https://app.club.example");
        assertThat(email.calendar().method()).isEqualTo("REQUEST");
        assertThat(email.calendar().content().split("BEGIN:VEVENT", -1).length - 1).isEqualTo(2);
        assertThat(email.calendar().content()).contains("UID:" + FIRST + "@canchatrack", "UID:" + SECOND + "@canchatrack",
                "DTSTART:20261207T220000Z", "DTSTART:20261209T220000Z", "SEQUENCE:0", "SUMMARY:Entrenamientos · Sub-12");
    }

    @Test
    void anEvaluationSeriesUsesItsOwnWording() {
        Map<String, String> payload = seriesPayload();
        payload.put("eventType", "EVALUATION");

        OutboundEmail email = EmailTemplates.render("EVENT_SERIES_SCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("Serie de sesiones de evaluación programada · Sub-12");
        assertThat(email.body()).contains("serie de 2 sesiones de evaluación");
    }

    @Test
    void aSeriesCancelledEmailListsTheSessionsTheReasonAndCancelsEachAppointment() {
        Map<String, String> payload = seriesPayload();
        payload.put("reason", "Cancha cerrada por obras\nBcc: x@y.z");
        payload.put("occurrences", FIRST + "|2026-12-07T22:00:00Z|2026-12-08T00:00:00Z|1;" + SECOND + "|2026-12-09T22:00:00Z|2026-12-10T00:00:00Z|2");

        OutboundEmail email = EmailTemplates.render("EVENT_SERIES_CANCELLED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("CANCELADO: serie de entrenamientos · Sub-12");
        assertThat(email.body()).contains("CANCELACIÓN", "se cancelaron 2 entrenamientos", "- lun 07/12/2026 17:00 a 19:00",
                "Motivo: Cancha cerrada por obras Bcc: x@y.z");
        assertThat(email.body().contains("\nBcc")).isFalse();
        assertThat(email.calendar().method()).isEqualTo("CANCEL");
        assertThat(email.calendar().content().split("STATUS:CANCELLED", -1).length - 1).isEqualTo(2);
        assertThat(email.calendar().content()).contains("SEQUENCE:1", "SEQUENCE:2");
    }

    @Test
    void anUnreadableSessionIsSkippedAndTheRestStillGoOut() {
        Map<String, String> payload = seriesPayload();
        payload.put("occurrences", "basura;" + FIRST + "|no-es-fecha|2026-12-08T00:00:00Z|0;" + SECOND
                + "|2026-12-09T22:00:00Z|2026-12-10T00:00:00Z|x;|2026-12-09T22:00:00Z|2026-12-10T00:00:00Z|0;"
                + FIRST + "|2026-12-10T22:00:00Z|2026-12-10T21:00:00Z|0");

        OutboundEmail email = EmailTemplates.render("EVENT_SERIES_SCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.body()).contains("serie de 1 entrenamientos", "- mié 09/12/2026 17:00 a 19:00");
        assertThat(email.calendar().content().split("BEGIN:VEVENT", -1).length - 1).isEqualTo(1);
        assertThat(email.calendar().content()).contains("SEQUENCE:0");
    }

    @Test
    void aSeriesEmailWithoutSessionsOrStampStillGoesOutWithoutInvitation() {
        Map<String, String> noSessions = seriesPayload();
        noSessions.remove("occurrences");
        assertThat(EmailTemplates.render("EVENT_SERIES_SCHEDULED", "a@b.co", null, noSessions, BOGOTA).orElseThrow().calendar()).isNull();

        Map<String, String> noStamp = seriesPayload();
        noStamp.remove("stamp");
        OutboundEmail email = EmailTemplates.render("EVENT_SERIES_SCHEDULED", "a@b.co", null, noStamp, BOGOTA).orElseThrow();
        assertThat(email.calendar()).isNull();
        assertThat(email.body()).contains("Sub-12");
    }

    @Test
    void aSeriesIsCappedAtSixtySessionsInTheEmailAndTheInvitation() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 70; i++) {
            many.append(i == 0 ? "" : ";").append(UUID.randomUUID()).append("|2027-01-").append(String.format("%02d", 1 + i % 28))
                    .append("T22:00:00Z|2027-01-").append(String.format("%02d", 1 + i % 28)).append("T23:00:00Z|0");
        }
        Map<String, String> payload = seriesPayload();
        payload.put("occurrences", many.toString());

        OutboundEmail email = EmailTemplates.render("EVENT_SERIES_SCHEDULED", "a@b.co", null, payload, BOGOTA).orElseThrow();

        assertThat(email.calendar().content().split("BEGIN:VEVENT", -1).length - 1).isEqualTo(60);
    }

    @Test
    void seriesEmailsNeverNameOrDescribeAnyPlayer() {
        for (String type : List.of("EVENT_SERIES_SCHEDULED", "EVENT_SERIES_CANCELLED")) {
            String body = EmailTemplates.render(type, "a@b.co", null, seriesPayload(), BOGOTA).orElseThrow().body();
            assertThat(body.toLowerCase().contains("jugador")).isFalse();
            assertThat(body.toLowerCase().contains("salud")).isFalse();
        }
    }

    @Test
    void passwordResetEmailCarriesTheLinkAndNeverClaimsTheAccountExists() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("token", "abc123");
        payload.put("url", "https://club.example.co/reset-password?token=abc123");
        payload.put("validityMinutes", "30");

        OutboundEmail email = EmailTemplates.render("PASSWORD_RESET", "madre@example.com", null, payload, BOGOTA).orElseThrow();

        assertThat(email.subject()).isEqualTo("Restablecer su contraseña");
        assertThat(email.body()).contains("Abra este enlace para elegir una nueva contraseña:",
                "https://club.example.co/reset-password?token=abc123", "Vale 30 minutos y solo se puede usar una vez",
                "ignore este correo: su contraseña no cambiará");
        assertThat(email.body()).doesNotContain("Use este código");
        assertThat(email.calendar()).isNull();
    }

    @Test
    void secretEmailsFallBackToTheCodeWhenThereIsNoFrontAddress() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("token", "codigo-xyz");
        payload.put("validityHours", "48");

        String verify = EmailTemplates.render("EMAIL_VERIFICATION", "a@b.co", null, payload, BOGOTA).orElseThrow().body();
        assertThat(verify).contains("Use este código en la pantalla de confirmación:", "codigo-xyz", "Vale 48 horas");

        payload.put("validityMinutes", "no-es-numero");
        String reset = EmailTemplates.render("PASSWORD_RESET", "a@b.co", null, payload, BOGOTA).orElseThrow().body();
        assertThat(reset).contains("Use este código en la pantalla de restablecimiento:", "codigo-xyz")
                .doesNotContain("Vale ");
    }

    @Test
    void emailVerificationAndPasswordChangedNoticesHaveTheirOwnWording() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("token", "t");
        payload.put("url", "https://club.example.co/verify-email?token=t");

        OutboundEmail verify = EmailTemplates.render("EMAIL_VERIFICATION", "a@b.co", null, payload, BOGOTA).orElseThrow();
        assertThat(verify.subject()).isEqualTo("Confirme su correo");
        assertThat(verify.body()).contains("Abra este enlace para confirmar su correo:", "sin confirmar, la cuenta no podrá usarse");

        OutboundEmail changed = EmailTemplates.render("PASSWORD_CHANGED", "a@b.co", null, Map.of(), BOGOTA).orElseThrow();
        assertThat(changed.subject()).isEqualTo("Su contraseña fue cambiada");
        assertThat(changed.body()).contains("se cerraron sus sesiones abiertas", "avise al club")
                .doesNotContain("token").doesNotContain("http");
    }
}
