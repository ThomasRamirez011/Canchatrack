package co.edu.unipamplona.canchatrack.reports.application;

import java.util.List;

/**
 * Encabezados de las exportaciones del ERS 7.3, en español porque reproducen las planillas del club (CA-21). El orden
 * de cada lista es el contrato con {@link ExportStore}: las filas que devuelve el almacén traen las celdas en este orden.
 * Ninguna columna lleva documento de identidad, datos de contacto ni datos de salud (RN-17).
 */
public final class ExportColumns {

    public static final List<String> ROSTER = List.of("Categoría", "Código", "Jugador", "Fecha de nacimiento", "Estado",
            "Entrenador titular", "Acudiente principal", "En la categoría desde");

    public static final List<String> ATTENDANCE = List.of("Fecha", "Hora", "Tipo", "Categoría", "Escenario", "Código",
            "Jugador", "Asistencia", "Excusa aceptada");

    public static final List<String> MATCHES = List.of("Fecha", "Hora", "Categoría", "Tipo", "Torneo", "Rival", "Escenario",
            "Estado del partido", "Goles a favor", "Goles en contra", "Código", "Jugador", "Convocatoria", "Minutos",
            "Goles");

    public static final List<String> EVALUATIONS = List.of("Categoría", "Código", "Jugador", "Periodo desde",
            "Periodo hasta", "Vence", "Estado de la ronda", "Resultado", "Fecha de cierre", "Conceptos emitidos",
            "Favorables", "Seguimiento", "Desfavorables");

    private ExportColumns() {
    }
}
