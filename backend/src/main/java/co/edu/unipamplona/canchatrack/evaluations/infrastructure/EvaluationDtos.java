package co.edu.unipamplona.canchatrack.evaluations.infrastructure;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.evaluations.application.DecisionView;
import co.edu.unipamplona.canchatrack.evaluations.application.RoundView;
import co.edu.unipamplona.canchatrack.players.api.PromotionResult;

/** Contratos JSON de evaluaciones y ascenso. Las solicitudes no llevan Bean Validation: las reglas viven en el dominio. */
final class EvaluationDtos {

    private EvaluationDtos() {
    }

    record OpenRoundRequest(LocalDate periodStart, LocalDate periodEnd, Instant dueAt) {
    }

    record ChangePeriodRequest(LocalDate periodStart, LocalDate periodEnd, Instant dueAt, String reason) {
    }

    record DecisionRequest(String verdict, String justification, String actions, Map<String, Object> criteria) {
    }

    record CloseRoundRequest(String followUpPlan) {
    }

    record PromoteRequest(UUID targetCategoryId, UUID headCoachId) {
    }

    record DecisionResponse(UUID id, UUID roundId, UUID evaluatorId, String verdict, String justification, String actions,
                            Map<String, Object> criteria, LocalDate periodStart, LocalDate periodEnd, boolean current,
                            Instant decidedAt, Instant updatedAt) {

        static DecisionResponse from(DecisionView d) {
            return new DecisionResponse(d.id(), d.roundId(), d.evaluatorId(), d.verdict(), d.justification(), d.actions(),
                    d.criteria(), d.periodStart(), d.periodEnd(), d.current(), d.decidedAt(), d.updatedAt());
        }
    }

    record RoundResponse(UUID id, UUID playerId, LocalDate periodStart, LocalDate periodEnd, Instant dueAt, String status,
                         String result, String followUpPlan, Instant closedAt, String outcome,
                         List<DecisionResponse> decisions) {

        static RoundResponse from(RoundView r) {
            return new RoundResponse(r.id(), r.playerId(), r.periodStart(), r.periodEnd(), r.dueAt(), r.status(), r.result(),
                    r.followUpPlan(), r.closedAt(), r.outcome(),
                    r.decisions() == null ? null : r.decisions().stream().map(DecisionResponse::from).toList());
        }
    }

    record PromotionResponse(UUID playerId, UUID previousCategoryId, UUID categoryId, String categoryName, UUID headCoachId) {

        static PromotionResponse from(PromotionResult r) {
            return new PromotionResponse(r.playerId(), r.previousCategoryId(), r.categoryId(), r.categoryName(), r.headCoachId());
        }
    }
}
