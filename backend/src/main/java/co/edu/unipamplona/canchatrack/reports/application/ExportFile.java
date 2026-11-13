package co.edu.unipamplona.canchatrack.reports.application;

/** Exportación lista para descargar: nombre sugerido, contenido CSV (sin la marca BOM, que agrega la capa web) y filas de datos. */
public record ExportFile(String fileName, String csv, int rows) {
}
