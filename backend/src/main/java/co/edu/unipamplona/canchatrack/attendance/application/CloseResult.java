package co.edu.unipamplona.canchatrack.attendance.application;

import java.time.Instant;
import java.util.UUID;

/** {@code autoAbsent}: jugadores de la plantilla sin marca que quedaron como ausentes al cerrar. */
public record CloseResult(UUID eventId, Instant closedAt, int autoAbsent, int total) {
}
