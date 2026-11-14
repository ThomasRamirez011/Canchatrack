package co.edu.unipamplona.canchatrack.reports.application;

import java.util.Arrays;
import java.util.List;

import co.edu.unipamplona.canchatrack.reports.domain.Csv;
import co.edu.unipamplona.canchatrack.reports.domain.ExportLabels;

/**
 * Cartera en CSV (hoja de cálculo): mismas columnas que ERS 7.3 «Cartera» más categoría y estado. Encabezados en español
 * porque reproducen la planilla del club (CA-21). Excel exige la marca BOM para leer bien las tildes; la agrega la capa web.
 */
public final class ReceivablesCsv {

    private static final List<String> HEADER = Arrays.asList("Categoría", "Código", "Jugador", "Acudiente", "Concepto",
            "Tarifa", "Periodo", "Vencimiento", "Valor", "Pagado", "Saldo", "Días de mora", "Estado");

    private ReceivablesCsv() {
    }

    public static String render(ReceivablesReport report) {
        StringBuilder out = new StringBuilder(Csv.line(HEADER));
        for (ReceivableLine l : report.lines()) {
            out.append(Csv.line(Arrays.asList(l.categoryName(), l.playerCode(), l.playerName(), l.guardianName(), ExportLabels.concept(l.concept()),
                    l.feeName(), l.period(), l.dueDate(), l.amount(), l.applied(), l.outstanding(), l.daysOverdue(),
                    ExportLabels.chargeStatus(l.status()))));
        }
        return out.toString();
    }
}
