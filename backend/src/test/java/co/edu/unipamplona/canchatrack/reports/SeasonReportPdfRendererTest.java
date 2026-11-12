package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.reports.application.SeasonReportContent;
import co.edu.unipamplona.canchatrack.reports.application.SeasonReportFacts;
import co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportPdfRenderer;

/** El PDF es válido, determinista y no desborda: estructura, tabla de referencias cruzadas y paginación. */
class SeasonReportPdfRendererTest {

    private static final Instant GENERATED = Instant.parse("2026-11-12T20:49:00Z");

    private static SeasonReportContent content(String playerName, int matchCount, String reason) {
        var season = new SeasonReportFacts.Season(UUID.randomUUID(), "Temporada 2026", "ACTIVE",
                Instant.parse("2026-02-01T05:00:00Z"), Instant.parse("2026-12-15T05:00:00Z"));
        var player = new SeasonReportFacts.Player(UUID.randomUUID(), "JG-000012", playerName, LocalDate.of(2013, 5, 9),
                "ACTIVE", "Sub-13", "María José Ortíz");
        var evaluation = new SeasonReportFacts.Evaluation(Instant.parse("2026-06-01T15:00:00Z"), LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 5, 31), "APPROVED", null,
                List.of(new SeasonReportFacts.Concept("Ana Gómez", "FAVORABLE"), new SeasonReportFacts.Concept("Luis Pérez", "FAVORABLE"),
                        new SeasonReportFacts.Concept("Carla Ruiz", "FAVORABLE")));
        List<SeasonReportFacts.MatchLine> matches = new ArrayList<>();
        for (int i = 0; i < matchCount; i++) {
            matches.add(new SeasonReportFacts.MatchLine(Instant.parse("2026-03-01T15:00:00Z").plusSeconds(86_400L * i),
                    "Rival " + i, i % 2 == 0 ? null : "Copa Regional", 2, 1, 60, 1));
        }
        var facts = SeasonReportFacts.of(season, player, new SeasonReportFacts.Period(season.startAt(), GENERATED, true),
                new SeasonReportFacts.Attendance(20, 15, 2, 1, 1, 2, new BigDecimal("85.0")), List.of(evaluation), matches);
        return new SeasonReportContent("plantilla-1", "IT-000012", 1, "ABCD-EFGH-JKLM", GENERATED, ZoneId.of("America/Bogota"),
                reason, facts);
    }

    private static String text(byte[] pdf) {
        return new String(pdf, StandardCharsets.ISO_8859_1);
    }

    @Test
    void producesAWellFormedPdfWithAValidCrossReferenceTable() {
        byte[] pdf = new SeasonReportPdfRenderer().render(content("Ana Gómez", 3, null));
        String raw = text(pdf);

        assertThat(raw).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        // startxref apunta exactamente al inicio de la tabla xref
        Matcher start = Pattern.compile("startxref\n(\\d+)\n").matcher(raw);
        assertThat(start.find()).isTrue();
        assertThat(raw.substring(Integer.parseInt(start.group(1)))).startsWith("xref\n");
        // cada entrada de la tabla apunta al inicio de «N 0 obj»
        Matcher entries = Pattern.compile("(\\d{10}) 00000 n \n").matcher(raw);
        int objectNumber = 0;
        while (entries.find()) {
            objectNumber++;
            assertThat(raw.substring(Integer.parseInt(entries.group(1)))).startsWith(objectNumber + " 0 obj\n");
        }
        assertThat(objectNumber).isGreaterThanOrEqualTo(7);
    }

    @Test
    void sameContentGivesTheSameBytesSoTheHashIsStable() {
        SeasonReportPdfRenderer renderer = new SeasonReportPdfRenderer();
        assertThat(renderer.render(content("Ana Gómez", 5, "Corrección")))
                .isEqualTo(renderer.render(content("Ana Gómez", 5, "Corrección")));
        assertThat(renderer.render(content("Ana Gómez", 5, null))).isNotEqualTo(renderer.render(content("Ana Gómez", 6, null)));
    }

    @Test
    void showsTheIdentificationAndTheSportingData() {
        String raw = text(new SeasonReportPdfRenderer().render(content("Ana Gómez", 2, null)));
        assertThat(raw).contains("IT-000012", "ABCD-EFGH-JKLM", "JG-000012", "Sub-13", "85,0 %",
                "Informe parcial", "Ascenso avalado", "Favorable", "Temporada 2026");
        // Las tildes salen en WinAnsi (un byte), no en UTF-8
        assertThat(raw).contains("Gómez").doesNotContain("GÃ³mez");
    }

    @Test
    void escapesParenthesesAndBackslashesSoANameCannotBreakTheContentStream() {
        String raw = text(new SeasonReportPdfRenderer().render(content("Ana (la \"Fiera\") \\ Gómez", 1, null)));
        assertThat(raw).contains("Ana \\(la \"Fiera\"\\) \\\\ Gómez");
    }

    @Test
    void longListsContinueOnNewPagesAndEveryPageIsNumbered() {
        byte[] pdf = new SeasonReportPdfRenderer().render(content("Ana Gómez", 120, null));
        String raw = text(pdf);
        Matcher count = Pattern.compile("/Count (\\d+)").matcher(raw);
        assertThat(count.find()).isTrue();
        int pages = Integer.parseInt(count.group(1));
        assertThat(pages).isGreaterThanOrEqualTo(3);
        for (int i = 1; i <= pages; i++) {
            assertThat(raw).contains("Página " + i + " de " + pages);
        }
    }

    @Test
    void unrepresentableCharactersDoNotBreakTheFile() {
        byte[] pdf = new SeasonReportPdfRenderer().render(content("Ana ЖЖ 😀 Gómez", 1, null));
        assertThat(text(pdf)).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        assertThat(Arrays.toString(pdf)).isNotEmpty();
    }
}
