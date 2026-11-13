package co.edu.unipamplona.canchatrack.reports.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Reglas de las exportaciones a CSV del ERS 7.3 (plantilla, asistencia, convocatorias y resultados, seguimiento de
 * evaluación). Puras y sin estado: reciben la zona del club en lugar de leer el reloj.
 */
public final class ExportRules {

    /** Tope de filas de una exportación. Si hay más se rechaza (422) en vez de entregar un archivo incompleto sin aviso. */
    public static final int MAX_ROWS = 20_000;
    /** Tope del rango explícito de fechas (sin temporada): un año bisiesto completo. */
    public static final int MAX_RANGE_DAYS = 366;

    private ExportRules() {
    }

    /** Ventana de tiempo de una exportación: {@code start} inclusivo, {@code end} exclusivo. */
    public record Window(Instant start, Instant end) {

        public LocalDate firstDay(ZoneId zone) {
            return start.atZone(zone).toLocalDate();
        }

        /** Último día cubierto (inclusivo), en la zona del club. */
        public LocalDate lastDay(ZoneId zone) {
            return end.minusNanos(1).atZone(zone).toLocalDate();
        }
    }

    /**
     * Errores de los parámetros de fecha. Con temporada, {@code from} y {@code to} son opcionales y solo acotan; sin
     * temporada son obligatorios y el rango no puede superar {@value #MAX_RANGE_DAYS} días.
     */
    public static List<FieldError> windowErrors(boolean hasSeason, LocalDate from, LocalDate to) {
        List<FieldError> errors = new ArrayList<>();
        if (!hasSeason) {
            if (from == null) {
                errors.add(new FieldError("from", "Indica la fecha inicial o una temporada."));
            }
            if (to == null) {
                errors.add(new FieldError("to", "Indica la fecha final o una temporada."));
            }
        }
        if (from != null && to != null) {
            if (to.isBefore(from)) {
                errors.add(new FieldError("to", "La fecha final no puede ser anterior a la inicial."));
            } else if (!hasSeason && ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
                errors.add(new FieldError("to", "El rango no puede superar " + MAX_RANGE_DAYS + " días."));
            }
        }
        return errors;
    }

    /**
     * Ventana efectiva. Con temporada parte de su periodo y las fechas la acotan; sin temporada son las fechas, de
     * 00:00 del primer día a 00:00 del día siguiente al último, en la zona del club. Puede quedar vacía (inicio no
     * anterior al fin) si no hay solapamiento: la exportación sale solo con encabezados.
     * Supone que {@link #windowErrors} no devolvió errores.
     */
    public static Window window(Instant seasonStart, Instant seasonEnd, LocalDate from, LocalDate to, ZoneId zone) {
        Instant start = seasonStart;
        Instant end = seasonEnd;
        if (from != null) {
            Instant fromStart = from.atStartOfDay(zone).toInstant();
            start = start == null || fromStart.isAfter(start) ? fromStart : start;
        }
        if (to != null) {
            Instant toEnd = to.plusDays(1).atStartOfDay(zone).toInstant();
            end = end == null || toEnd.isBefore(end) ? toEnd : end;
        }
        return new Window(start, end);
    }

    /** Nombre del archivo: {@code base-AAAA-MM-DD_AAAA-MM-DD.csv}. */
    public static String fileName(String base, Window window, ZoneId zone) {
        return base + "-" + window.firstDay(zone) + "_" + window.lastDay(zone) + ".csv";
    }

    public static String yesNo(boolean value) {
        return value ? "Sí" : "No";
    }
}
