package co.edu.unipamplona.canchatrack.evaluations.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Ronda de evaluación. {@code outcome} es la vista previa de la unanimidad (solo para coordinación y comité, solo mientras
 * está abierta); {@code decisions} es nulo en los listados.
 */
public record RoundView(UUID id, UUID playerId, LocalDate periodStart, LocalDate periodEnd, Instant dueAt, String status,
                        String result, String followUpPlan, Instant closedAt, String outcome, List<DecisionView> decisions) {

    public boolean open() {
        return "OPEN".equals(status);
    }

    public RoundView with(String newOutcome, List<DecisionView> newDecisions) {
        return new RoundView(id, playerId, periodStart, periodEnd, dueAt, status, result, followUpPlan, closedAt,
                newOutcome, newDecisions);
    }
}
