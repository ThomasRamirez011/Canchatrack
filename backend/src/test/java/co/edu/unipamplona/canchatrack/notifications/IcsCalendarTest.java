package co.edu.unipamplona.canchatrack.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.notifications.domain.CalendarInvite;
import co.edu.unipamplona.canchatrack.notifications.domain.IcsCalendar;

/** La invitación .ics cumple lo esencial de RFC 5545 y no deja que un dato de usuario inyecte propiedades. */
class IcsCalendarTest {

    private static final Instant STAMP = Instant.parse("2026-11-12T15:00:00Z");
    private static final Instant START = Instant.parse("2026-12-01T22:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T23:30:00Z");

    private static CalendarInvite sample(String method, int sequence, String summary, String location, String description) {
        return IcsCalendar.event(method, "abc-123@canchatrack", sequence, STAMP, START, END, summary, location, description);
    }

    @Test
    void producesAWellFormedSingleEventCalendarWithCrlfLines() {
        CalendarInvite invite = sample("REQUEST", 0, "Entrenamiento · Sub-12", "Cancha Municipal", "Traer agua");
        String ics = invite.content();

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(ics).endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n");
        assertThat(ics).contains("VERSION:2.0\r\n", "METHOD:REQUEST\r\n", "UID:abc-123@canchatrack\r\n",
                "DTSTAMP:20261112T150000Z\r\n", "DTSTART:20261201T220000Z\r\n", "DTEND:20261201T233000Z\r\n",
                "SEQUENCE:0\r\n", "STATUS:CONFIRMED\r\n", "LOCATION:Cancha Municipal\r\n", "DESCRIPTION:Traer agua\r\n");
        assertThat(invite.method()).isEqualTo("REQUEST");
        assertThat(ics.replace("\r\n", "").contains("\n")).isFalse();
    }

    @Test
    void aCancellationUsesMethodCancelAndStatusCancelled() {
        CalendarInvite invite = sample("CANCEL", 1, "CANCELADO: Entrenamiento", null, null);

        assertThat(invite.method()).isEqualTo("CANCEL");
        assertThat(invite.content()).contains("METHOD:CANCEL\r\n", "STATUS:CANCELLED\r\n", "SEQUENCE:1\r\n");
        assertThat(invite.content().contains("LOCATION")).isFalse();
        assertThat(invite.content().contains("DESCRIPTION")).isFalse();
    }

    @Test
    void anUnknownMethodFallsBackToRequest() {
        assertThat(sample("PUBLISH", 0, "x", null, null).method()).isEqualTo("REQUEST");
    }

    @Test
    void textValuesAreEscapedAndControlCharactersCannotInjectProperties() {
        CalendarInvite invite = sample("REQUEST", 0, "A; B, C \\ D", "Cancha\r\nATTENDEE:mailto:x@y.z",
                "Línea 1\nLínea 2");
        String ics = invite.content();

        assertThat(ics).contains("SUMMARY:A\\; B\\, C \\\\ D\r\n");
        assertThat(ics).contains("DESCRIPTION:Línea 1\\nLínea 2\r\n");
        assertThat(ics.contains("\r\nATTENDEE")).isFalse();
        assertThat(ics).contains("LOCATION:Cancha \\nATTENDEE:mailto:x@y.z\r\n");
    }

    @Test
    void longLinesAreFoldedAt75OctetsWithoutSplittingACharacter() {
        String longDescription = "ñ".repeat(120) + " fin";
        String ics = sample("REQUEST", 0, "x", null, longDescription).content();

        for (String line : ics.split("\r\n")) {
            assertThat(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 75).isTrue();
        }
        // desplegar (quitar CRLF + espacio) reconstruye el valor completo
        assertThat(ics.replace("\r\n ", "")).contains("DESCRIPTION:" + longDescription + "\r\n");
        assertThat(Arrays.stream(ics.split("\r\n")).filter(l -> l.startsWith(" ")).count() > 0).isTrue();
    }

    @Test
    void theUidKeepsOnlyHarmlessCharacters() {
        CalendarInvite invite = IcsCalendar.event("REQUEST", "id\r\nX-INJECT:1@canchatrack", 0, STAMP, START, END, "x", null, null);

        assertThat(invite.content()).contains("UID:idX-INJECT1@canchatrack\r\n");
    }

    @Test
    void aNegativeSequenceIsTreatedAsZero() {
        assertThat(sample("REQUEST", -3, "x", null, null).content()).contains("SEQUENCE:0\r\n");
    }

    @Test
    void aSeriesIsOneCalendarWithOneEventPerSessionAndItsOwnUid() {
        CalendarInvite invite = IcsCalendar.series("REQUEST", STAMP, java.util.List.of(
                new IcsCalendar.Occurrence("a-1@canchatrack", 0, START, END),
                new IcsCalendar.Occurrence("b-2@canchatrack", 3, START.plusSeconds(7 * 86400), END.plusSeconds(7 * 86400))),
                "Entrenamientos · Sub-12", "Cancha Municipal", "Traer agua");
        String ics = invite.content();

        assertThat(ics.split("BEGIN:VEVENT", -1).length - 1).isEqualTo(2);
        assertThat(ics.split("BEGIN:VCALENDAR", -1).length - 1).isEqualTo(1);
        assertThat(ics).contains("UID:a-1@canchatrack\r\n", "UID:b-2@canchatrack\r\n", "SEQUENCE:0\r\n", "SEQUENCE:3\r\n",
                "DTSTART:20261208T220000Z\r\n", "METHOD:REQUEST\r\n");
        assertThat(ics.split("SUMMARY:Entrenamientos", -1).length - 1).isEqualTo(2);
        assertThat(ics).endsWith("END:VEVENT\r\nEND:VCALENDAR\r\n");
    }

    @Test
    void aSeriesCancellationMarksEverySessionCancelled() {
        CalendarInvite invite = IcsCalendar.series("CANCEL", STAMP, java.util.List.of(
                new IcsCalendar.Occurrence("a-1@canchatrack", 1, START, END),
                new IcsCalendar.Occurrence("b-2@canchatrack", 1, START.plusSeconds(86400), END.plusSeconds(86400))),
                "CANCELADO: Entrenamientos", null, null);

        assertThat(invite.method()).isEqualTo("CANCEL");
        assertThat(invite.content().split("STATUS:CANCELLED", -1).length - 1).isEqualTo(2);
        assertThat(invite.content()).contains("METHOD:CANCEL\r\n");
    }
}
