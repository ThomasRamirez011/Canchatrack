package co.edu.unipamplona.canchatrack.reports.application;

/** Puerto de salida: produce el PDF del informe. Debe ser determinista (mismo contenido, mismos bytes). */
public interface SeasonReportRenderer {

    byte[] render(SeasonReportContent content);
}
