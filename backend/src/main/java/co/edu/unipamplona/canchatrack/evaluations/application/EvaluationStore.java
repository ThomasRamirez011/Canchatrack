package co.edu.unipamplona.canchatrack.evaluations.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: rondas y conceptos. Las escrituras exigen una transacción activa. */
public interface EvaluationStore {

    /** Reloj de la base de datos. */
    Instant now();

    /** @return vacío si el jugador ya tiene una ronda abierta */
    Optional<UUID> insertRound(UUID playerId, LocalDate periodStart, LocalDate periodEnd, Instant dueAt, UUID openedBy);

    /** Ronda sin conceptos ni vista previa. Con {@code lock} la bloquea (cierre y cambio de periodo). */
    Optional<RoundView> findRound(UUID roundId, boolean lock);

    /** Rondas del jugador, la más reciente primero, sin conceptos. */
    List<RoundView> findRounds(UUID playerId);

    List<DecisionView> findDecisions(UUID roundId);

    /**
     * Crea o reemplaza el concepto del evaluador sobre el periodo indicado. El historial del concepto anterior lo guarda
     * la base de datos.
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException ROUND_CLOSED si la ronda se cerró entre tanto
     */
    DecisionView upsertDecision(UUID roundId, UUID evaluatorId, String verdict, String justification, String actions,
                                Map<String, Object> criteria, LocalDate periodStart, LocalDate periodEnd);

    /** Con {@code dueAt} nulo conserva el vencimiento actual. */
    void updatePeriod(UUID roundId, LocalDate periodStart, LocalDate periodEnd, Instant dueAt);

    /** @return {@code false} si la ronda ya no estaba abierta */
    boolean close(UUID roundId, String result, String followUpPlan);

    /** Última ronda cerrada con ascenso aprobado. */
    Optional<UUID> findApprovedRoundId(UUID playerId);
}
