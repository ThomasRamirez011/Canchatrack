package co.edu.unipamplona.canchatrack.reports.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Escritura de CSV segura para abrir en hojas de cálculo (RFC 4180, fin de línea CRLF).
 *
 * <p>Los textos que empiezan por {@code = + - @}, tabulación o retorno se prefijan con una comilla simple para que
 * Excel no los interprete como fórmulas (inyección CSV): los nombres de jugadores los escribe un acudiente. Los números
 * y fechas no se tocan.
 */
public final class Csv {

    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    private Csv() {
    }

    public static String line(List<?> cells) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        return out.append("\r\n").toString();
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof java.time.temporal.TemporalAccessor) {
            return value.toString();
        }
        String text = value.toString();
        if (!text.isEmpty() && FORMULA_TRIGGERS.indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }
}
