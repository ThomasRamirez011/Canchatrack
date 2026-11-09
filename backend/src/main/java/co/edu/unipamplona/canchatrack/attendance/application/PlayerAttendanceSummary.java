package co.edu.unipamplona.canchatrack.attendance.application;

import java.math.BigDecimal;
import java.util.UUID;

/** Sesiones cerradas del jugador; {@code percentage} es nulo si no hay sesiones que contar. */
public record PlayerAttendanceSummary(UUID playerId, int sessions, int present, int late, int excused, int acceptedExcuses,
                                      int absent, BigDecimal percentage) {
}
