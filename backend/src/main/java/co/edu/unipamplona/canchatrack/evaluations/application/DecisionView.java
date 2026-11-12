package co.edu.unipamplona.canchatrack.evaluations.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Concepto de un evaluador. {@code current} es verdadero si se emitió sobre el periodo vigente de la ronda; los emitidos
 * sobre un periodo anterior no cuentan para la unanimidad (DAD CU-07).
 */
public record DecisionView(UUID id, UUID roundId, UUID evaluatorId, String verdict, String justification, String actions,
                           Map<String, Object> criteria, LocalDate periodStart, LocalDate periodEnd, boolean current,
                           Instant decidedAt, Instant updatedAt) {
}
