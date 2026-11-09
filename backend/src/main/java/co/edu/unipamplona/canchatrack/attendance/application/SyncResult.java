package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.List;

/** {@code replayed}: el lote ya se había procesado con la misma clave; el detalle de rechazos solo existe la primera vez. */
public record SyncResult(boolean replayed, int received, int accepted, int duplicates, int rejected, List<Rejection> rejections) {
}
