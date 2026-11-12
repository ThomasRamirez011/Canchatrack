package co.edu.unipamplona.canchatrack.identity.application;

import java.util.UUID;

/**
 * Puerto de salida: asignaciones vigentes del personal deportivo a un jugador (entrenador titular de su categoría y
 * evaluador del panel). La vigencia se evalúa con la hora de la base de datos.
 */
public interface StaffAssignments {

    boolean isHeadCoachOf(UUID userId, UUID playerId);

    boolean isEvaluatorOf(UUID userId, UUID playerId);
}
