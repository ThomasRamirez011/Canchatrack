package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.reports.application.ReceivableLine;
import co.edu.unipamplona.canchatrack.reports.application.ReceivablesCsv;
import co.edu.unipamplona.canchatrack.reports.application.ReceivablesReport;
import co.edu.unipamplona.canchatrack.reports.domain.Csv;
import co.edu.unipamplona.canchatrack.reports.domain.ReportRules;

class ReportRulesTest {

    // ---------- días de mora ----------

    @Test
    void daysOverdueCountsFromTheDueDateAndNeverGoesNegative() {
        LocalDate due = LocalDate.of(2030, 1, 5);
        assertThat(ReportRules.daysOverdue(due, LocalDate.of(2030, 1, 15))).isEqualTo(10);
        assertThat(ReportRules.daysOverdue(due, due)).isZero();
        assertThat(ReportRules.daysOverdue(due, due.minusDays(3))).isZero();
    }

    // ---------- porcentaje de asistencia ----------

    @Test
    void attendancePercentageUsesTheSameFormulaAsTheAttendanceModule() {
        // 2 asistió de 4 registros, 1 excusa aceptada: 2 / (4 - 1)
        assertThat(ReportRules.attendancePercentage(2, 4, 1)).isEqualByComparingTo("66.7");
        assertThat(ReportRules.attendancePercentage(4, 4, 0)).isEqualByComparingTo("100.0");
        assertThat(ReportRules.attendancePercentage(0, 3, 0)).isEqualByComparingTo("0.0");
    }

    @Test
    void attendancePercentageIsNullWhenThereIsNothingToCount() {
        assertThat(ReportRules.attendancePercentage(0, 0, 0)).isNull();
        assertThat(ReportRules.attendancePercentage(0, 2, 2)).isNull();
    }

    // ---------- CSV ----------

    @Test
    void aCsvLineJoinsCellsWithCommasAndEndsWithCrlf() {
        assertThat(Csv.line(Arrays.asList("a", 3, new BigDecimal("1500.50"), LocalDate.of(2030, 1, 5), null)))
                .isEqualTo("a,3,1500.50,2030-01-05,\r\n");
    }

    @Test
    void cellsWithSeparatorsQuotesOrNewlinesAreQuoted() {
        assertThat(Csv.line(List.of("Pérez, Ana"))).isEqualTo("\"Pérez, Ana\"\r\n");
        assertThat(Csv.line(List.of("dijo \"hola\""))).isEqualTo("\"dijo \"\"hola\"\"\"\r\n");
        assertThat(Csv.line(List.of("línea 1\nlínea 2"))).isEqualTo("\"línea 1\nlínea 2\"\r\n");
    }

    @Test
    void textThatLooksLikeAFormulaIsNeutralised() {
        assertThat(Csv.line(List.of("=1+1"))).isEqualTo("'=1+1\r\n");
        assertThat(Csv.line(List.of("+57 300"))).isEqualTo("'+57 300\r\n");
        assertThat(Csv.line(List.of("-cmd"))).isEqualTo("'-cmd\r\n");
        assertThat(Csv.line(List.of("@SUM(A1)"))).isEqualTo("'@SUM(A1)\r\n");
        assertThat(Csv.line(List.of("\tTAB"))).isEqualTo("'\tTAB\r\n");
        assertThat(Csv.line(List.of("=HYPERLINK(\"x\"),y"))).isEqualTo("\"'=HYPERLINK(\"\"x\"\"),y\"\r\n");
    }

    @Test
    void numbersAreNotTreatedAsFormulasEvenWhenNegative() {
        assertThat(Csv.line(List.of(new BigDecimal("-500.00"), -3))).isEqualTo("-500.00,-3\r\n");
    }

    @Test
    void theReceivablesCsvStartsWithTheSpanishHeaderAndHasOneRowPerLine() {
        ReceivableLine line = new ReceivableLine(UUID.randomUUID(), "JG-000001", "Ana Gómez", UUID.randomUUID(), "Sub-12",
                "Marta López", "MONTHLY", "Mensualidad", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 1, 5),
                new BigDecimal("100000.00"), new BigDecimal("40000.00"), new BigDecimal("60000.00"), 10, "PARTIAL");
        ReceivablesReport report = new ReceivablesReport(LocalDate.of(2030, 1, 15), java.time.Instant.parse("2030-01-15T12:00:00Z"),
                List.of(line), List.of(), false);

        String[] rows = ReceivablesCsv.render(report).split("\r\n");

        assertThat(rows).hasSize(2);
        assertThat(rows[0]).isEqualTo("Categoría,Código,Jugador,Acudiente,Concepto,Tarifa,Periodo,Vencimiento,Valor,Pagado,Saldo,Días de mora,Estado");
        assertThat(rows[1]).isEqualTo("Sub-12,JG-000001,Ana Gómez,Marta López,MONTHLY,Mensualidad,2030-01-01,2030-01-05,100000.00,40000.00,60000.00,10,PARTIAL");
    }
}
