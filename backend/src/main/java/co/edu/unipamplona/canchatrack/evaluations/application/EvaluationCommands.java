package co.edu.unipamplona.canchatrack.evaluations.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Órdenes de entrada de {@link EvaluationService}. */
public final class EvaluationCommands {

    private EvaluationCommands() {
    }

    public record OpenRound(LocalDate periodStart, LocalDate periodEnd, Instant dueAt) {
    }

    /** {@code dueAt} nulo conserva el vencimiento; {@code reason} es obligatorio. */
    public record ChangePeriod(LocalDate periodStart, LocalDate periodEnd, Instant dueAt, String reason) {
    }

    public record Decide(String verdict, String justification, String actions, Map<String, Object> criteria) {
    }

    public record CloseRound(String followUpPlan) {
    }

    /** {@code targetCategoryId} nulo usa la categoría siguiente de la actual. */
    public record Promote(UUID targetCategoryId, UUID headCoachId) {
    }
}
