package co.edu.unipamplona.canchatrack.evaluations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.ChangePeriod;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.CloseRound;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.Decide;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.OpenRound;
import co.edu.unipamplona.canchatrack.evaluations.application.EvaluationCommands.Promote;
import co.edu.unipamplona.canchatrack.evaluations.domain.EvaluationRules;
import co.edu.unipamplona.canchatrack.evaluations.domain.FieldError;
import co.edu.unipamplona.canchatrack.evaluations.domain.RoundOutcome;
import co.edu.unipamplona.canchatrack.evaluations.domain.Verdict;
import co.edu.unipamplona.canchatrack.players.api.EvaluationGateway;
import co.edu.unipamplona.canchatrack.players.api.PromotionResult;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Evaluación del jugador y ascenso de categoría (CU-07 y CU-08; RN-08..RN-12).
 *
 * <p>La coordinación abre una ronda sobre un periodo; cada uno de los tres evaluadores del panel emite su concepto y puede
 * ajustarlo mientras la ronda esté abierta. Un concepto emitido sobre un periodo anterior deja de contar. El cierre lo
 * hace la coordinación: con tres conceptos favorables vigentes el ascenso queda aprobado; con alguno desfavorable, con
 * seguimiento o faltante al vencimiento, el jugador sigue en su categoría con un plan de seguimiento. La coordinación nunca
 * puede modificar un concepto (RN-10): no existe operación para ello y la base de datos lo impide.
 *
 * <p>Mientras la ronda está abierta, un evaluador solo ve su propio concepto (independencia de criterio).
 */
@Service
public class EvaluationService {

    static final String ACTION_OPENED = "EVALUATION_ROUND_OPENED";
    static final String ACTION_PERIOD = "EVALUATION_PERIOD_CHANGED";
    static final String ACTION_DECISION = "EVALUATOR_DECISION_RECORDED";
    static final String ACTION_CLOSED = "EVALUATION_ROUND_CLOSED";
    static final String ACTION_PROMOTED = "PLAYER_PROMOTED";
    static final int MAX_CRITERIA_JSON = 8000;

    private final EvaluationStore store;
    private final EvaluationGateway players;
    private final AuditWriter audit;

    public EvaluationService(EvaluationStore store, EvaluationGateway players, AuditWriter audit) {
        this.store = store;
        this.players = players;
        this.audit = audit;
    }

    @Transactional
    public RoundView open(EvaluationsActor actor, UUID playerId, OpenRound command) {
        ValidationFailure.throwIfAny(EvaluationRules.periodViolations(command.periodStart(), command.periodEnd(),
                command.dueAt(), true, store.now()));
        players.startEvaluation(playerId);
        UUID roundId = store.insertRound(playerId, command.periodStart(), command.periodEnd(), command.dueAt(), actor.id())
                .orElseThrow(() -> new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                        "El jugador ya tiene una ronda de evaluación abierta."));
        audit.write(base(ACTION_OPENED, actor, roundId)
                .after(Json.of("playerId", playerId, "periodStart", command.periodStart(), "periodEnd", command.periodEnd(),
                        "dueAt", command.dueAt()))
                .build());
        return assemble(store.findRound(roundId, false).orElseThrow(), true, actor.id());
    }

    @Transactional
    public RoundView changePeriod(EvaluationsActor actor, UUID roundId, ChangePeriod command) {
        List<FieldError> errors = new ArrayList<>(EvaluationRules.periodViolations(command.periodStart(),
                command.periodEnd(), command.dueAt(), false, store.now()));
        errors.addAll(EvaluationRules.reasonViolations(command.reason()));
        ValidationFailure.throwIfAny(errors);
        RoundView round = store.findRound(roundId, true).orElseThrow(EvaluationService::roundNotFound);
        requireOpen(round);
        store.updatePeriod(roundId, command.periodStart(), command.periodEnd(), command.dueAt());
        audit.write(base(ACTION_PERIOD, actor, roundId)
                .before(Json.of("periodStart", round.periodStart(), "periodEnd", round.periodEnd(), "dueAt", round.dueAt()))
                .after(Json.of("periodStart", command.periodStart(), "periodEnd", command.periodEnd(),
                        "dueAt", command.dueAt() == null ? round.dueAt() : command.dueAt()))
                .reason(command.reason().trim())
                .build());
        return assemble(store.findRound(roundId, false).orElseThrow(), true, actor.id());
    }

    /** El evaluador del panel emite o ajusta su propio concepto (RN-08); nadie decide en nombre de otro. */
    @Transactional
    public DecisionView decide(EvaluationsActor actor, UUID roundId, Decide command) {
        RoundView round = store.findRound(roundId, false).orElseThrow(EvaluationService::roundNotFound);
        if (!players.panelOf(round.playerId()).contains(actor.id())) {
            throw new DomainException(ErrorCode.PANEL_INVALID, "El usuario no está asignado como evaluador de este jugador.");
        }
        requireOpen(round);

        List<FieldError> errors = new ArrayList<>(EvaluationRules.decisionViolations(command.verdict(),
                command.justification(), command.actions(), command.criteria()));
        Map<String, Object> criteria = command.criteria() == null ? Map.of() : command.criteria();
        if (Json.write(criteria).length() > MAX_CRITERIA_JSON) {
            errors.add(new FieldError("criteria", "Los criterios superan el tamaño admitido."));
        }
        boolean negativeReason = errors.stream().anyMatch(e -> EvaluationRules.FIELD_JUSTIFICATION.equals(e.field())
                || EvaluationRules.FIELD_ACTIONS.equals(e.field()));
        ValidationFailure.throwIfAny(negativeReason ? ErrorCode.NEGATIVE_REASON_REQUIRED : ErrorCode.VALIDATION_FAILED, errors);

        Verdict verdict = Verdict.parse(command.verdict()).orElseThrow();
        DecisionView decision = store.upsertDecision(roundId, actor.id(), verdict.name(), blankToNull(command.justification()),
                blankToNull(command.actions()), criteria, round.periodStart(), round.periodEnd());
        // El texto de la justificación puede contener datos sensibles: la auditoría solo guarda el sentido del concepto.
        audit.write(base(ACTION_DECISION, actor, roundId)
                .after(Json.of("playerId", round.playerId(), "verdict", verdict, "periodStart", round.periodStart(),
                        "periodEnd", round.periodEnd()))
                .build());
        return decision;
    }

    @Transactional
    public RoundView close(EvaluationsActor actor, UUID roundId, CloseRound command) {
        RoundView round = store.findRound(roundId, true).orElseThrow(EvaluationService::roundNotFound);
        requireOpen(round);
        List<DecisionView> decisions = store.findDecisions(roundId);
        RoundOutcome outcome = outcomeOf(decisions, players.panelOf(round.playerId()));
        boolean overdue = round.dueAt() != null && !round.dueAt().isAfter(store.now());
        if (outcome == RoundOutcome.PENDING && !overdue) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "Faltan conceptos vigentes y la ronda aún no vence (" + round.dueAt() + "); no puede cerrarse.",
                    Map.of("outcome", RoundOutcome.PENDING.name()));
        }
        boolean approved = outcome == RoundOutcome.APPROVED;
        String plan = null;
        if (!approved) {
            ValidationFailure.throwIfAny(EvaluationRules.followUpPlanViolations(command.followUpPlan()));
            plan = command.followUpPlan().trim();
        }
        String result = approved ? "APPROVED" : "FOLLOW_UP";
        if (!store.close(roundId, result, plan)) {
            throw new DomainException(ErrorCode.ROUND_CLOSED, null);
        }
        players.concludeEvaluation(round.playerId(), approved);
        audit.write(base(ACTION_CLOSED, actor, roundId)
                .before(Json.of("status", "OPEN"))
                .after(Json.of("status", "CLOSED", "result", result, "outcome", outcome, "playerId", round.playerId()))
                .reason(plan)
                .build());
        return assemble(store.findRound(roundId, false).orElseThrow(), true, actor.id());
    }

    /** CU-08 / T-08: ejecuta el ascenso aprobado; exige cupo en la categoría destino y conserva el historial. */
    @Transactional
    public PromotionResult promote(EvaluationsActor actor, UUID playerId, Promote command) {
        UUID roundId = store.findApprovedRoundId(playerId).orElseThrow(() -> new DomainException(
                ErrorCode.PLAYER_STATE_CONFLICT, "El jugador no tiene una ronda de evaluación con ascenso aprobado."));
        PromotionResult result = players.promote(playerId, roundId, command.targetCategoryId(), command.headCoachId(), actor.id());
        audit.write(AuditRecord.builder(ACTION_PROMOTED, "player")
                .actor(actor.id(), actor.role())
                .entity(playerId)
                .before(Json.of("categoryId", result.previousCategoryId(), "status", "PROMOTION_APPROVED"))
                .after(Json.of("categoryId", result.categoryId(), "headCoachId", result.headCoachId(), "roundId", roundId,
                        "status", "ACTIVE"))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return result;
    }

    @Transactional(readOnly = true)
    public RoundView get(UUID userId, Set<String> roles, UUID roundId) {
        RoundView round = store.findRound(roundId, false).orElseThrow(EvaluationService::roundNotFound);
        boolean all = seesAll(roles);
        if (!all && !players.panelOf(round.playerId()).contains(userId)
                && store.findDecisions(roundId).stream().noneMatch(d -> d.evaluatorId().equals(userId))) {
            throw roundNotFound();
        }
        return assemble(round, all, userId);
    }

    @Transactional(readOnly = true)
    public List<RoundView> list(UUID userId, Set<String> roles, UUID playerId) {
        if (!seesAll(roles) && !players.panelOf(playerId).contains(userId)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe o no está a su cargo.");
        }
        return store.findRounds(playerId);
    }

    // ---------- apoyo ----------

    private RoundView assemble(RoundView round, boolean seesAll, UUID userId) {
        List<DecisionView> all = store.findDecisions(round.id());
        List<DecisionView> visible = seesAll || !round.open() ? all
                : all.stream().filter(d -> d.evaluatorId().equals(userId)).toList();
        String outcome = seesAll && round.open() ? outcomeOf(all, players.panelOf(round.playerId())).name() : null;
        return round.with(outcome, visible);
    }

    /** DDS 5.1: solo cuentan los conceptos de integrantes del panel emitidos sobre el periodo vigente. */
    private static RoundOutcome outcomeOf(List<DecisionView> decisions, List<UUID> panel) {
        return EvaluationRules.outcome(decisions.stream()
                .filter(DecisionView::current)
                .filter(d -> panel.contains(d.evaluatorId()))
                .map(d -> Verdict.valueOf(d.verdict()))
                .toList());
    }

    private static void requireOpen(RoundView round) {
        if (!round.open()) {
            throw new DomainException(ErrorCode.ROUND_CLOSED, null);
        }
    }

    private static boolean seesAll(Set<String> roles) {
        return roles.contains("COORDINATOR") || roles.contains("COMMITTEE");
    }

    private static DomainException roundNotFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La ronda de evaluación no existe.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AuditRecord.Builder base(String action, EvaluationsActor actor, UUID roundId) {
        return AuditRecord.builder(action, "evaluation_round")
                .actor(actor.id(), actor.role())
                .entity(roundId)
                .origin(actor.ip(), actor.userAgent());
    }
}
