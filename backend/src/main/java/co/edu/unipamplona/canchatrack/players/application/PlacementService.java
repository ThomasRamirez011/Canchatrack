package co.edu.unipamplona.canchatrack.players.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.identity.api.StaffDirectory;
import co.edu.unipamplona.canchatrack.players.api.EvaluationGateway;
import co.edu.unipamplona.canchatrack.players.api.EvaluationSubject;
import co.edu.unipamplona.canchatrack.players.api.PromotionResult;
import co.edu.unipamplona.canchatrack.players.domain.FieldError;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Cambios de estado y de categoría del jugador que provocan las evaluaciones (DDS transiciones T-05..T-08). El historial
 * se conserva: la asignación anterior se cierra con {@code valid_to}, nunca se borra (CA-14).
 */
@Service
public class PlacementService implements EvaluationGateway {

    static final int PANEL_SIZE = 3;

    private final PlacementStore store;
    private final CategoryStore categories;
    private final StaffDirectory staff;
    private final CommitteeSettings settings;

    public PlacementService(PlacementStore store, CategoryStore categories, StaffDirectory staff, CommitteeSettings settings) {
        this.store = store;
        this.categories = categories;
        this.staff = staff;
        this.settings = settings;
    }

    @Override
    @Transactional
    public EvaluationSubject startEvaluation(UUID playerId) {
        PlayerRecord player = lock(playerId);
        if (player.status() != PlayerStatus.ACTIVE) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "Solo un jugador activo puede entrar en evaluación (estado actual: " + player.status() + ").");
        }
        UUID categoryId = store.currentCategoryId(playerId).orElseThrow(() -> new DomainException(
                ErrorCode.PLAYER_STATE_CONFLICT, "El jugador no tiene una categoría vigente."));
        List<UUID> panel = store.currentPanel(playerId);
        validatePanel(panel);
        if (!store.transition(playerId, PlayerStatus.ACTIVE, PlayerStatus.IN_EVALUATION)) {
            throw stateChanged();
        }
        return new EvaluationSubject(playerId, player.code(), categoryId, panel);
    }

    @Override
    @Transactional
    public void concludeEvaluation(UUID playerId, boolean promotionApproved) {
        lock(playerId);
        PlayerStatus target = promotionApproved ? PlayerStatus.PROMOTION_APPROVED : PlayerStatus.ACTIVE;
        if (!store.transition(playerId, PlayerStatus.IN_EVALUATION, target)) {
            throw stateChanged();
        }
    }

    @Override
    @Transactional
    public PromotionResult promote(UUID playerId, UUID roundId, UUID targetCategoryId, UUID headCoachId, UUID actorId) {
        PlayerRecord player = lock(playerId);
        if (player.status() != PlayerStatus.PROMOTION_APPROVED) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "El jugador no tiene un ascenso aprobado pendiente de ejecutar (estado actual: " + player.status() + ").");
        }
        UUID previousId = store.currentCategoryId(playerId).orElseThrow(() -> new DomainException(
                ErrorCode.PLAYER_STATE_CONFLICT, "El jugador no tiene una categoría vigente."));

        List<FieldError> errors = new ArrayList<>();
        UUID targetId = targetCategoryId;
        if (targetId == null) {
            targetId = categories.findById(previousId).map(CategoryInfo::nextCategoryId).orElse(null);
            if (targetId == null) {
                errors.add(new FieldError("targetCategoryId",
                        "La categoría actual no define una categoría siguiente; indique targetCategoryId."));
            }
        } else if (targetId.equals(previousId)) {
            errors.add(new FieldError("targetCategoryId", "La categoría destino debe ser distinta de la actual."));
        }
        if (headCoachId == null) {
            errors.add(new FieldError("headCoachId", "El entrenador titular de la nueva categoría es obligatorio."));
        } else if (!staff.activeRolesOf(List.of(headCoachId)).getOrDefault(headCoachId, Set.of()).contains("COACH")) {
            errors.add(new FieldError("headCoachId", "El entrenador titular debe ser una cuenta activa con rol de entrenador."));
        }
        ValidationFailure.throwIfAny(errors);

        CategoryInfo target = categories.findByIdForUpdate(targetId).orElse(null);
        if (target == null) {
            throw ValidationFailure.of("targetCategoryId", "La categoría destino no existe.");
        }
        if (!target.active()) {
            throw ValidationFailure.of("targetCategoryId", "La categoría «" + target.name() + "» no está activa.");
        }
        if (target.occupied() >= target.capacity()) {
            throw new DomainException(ErrorCode.CATEGORY_FULL, "La categoría «" + target.name() + "» no tiene cupo.",
                    Map.of("categoryId", target.id().toString(), "capacity", target.capacity(), "occupied", target.occupied()));
        }

        store.closeCurrentCategoryAssignment(playerId);
        store.insertPromotionAssignment(playerId, target.id(), headCoachId, roundId, actorId);
        if (!store.transition(playerId, PlayerStatus.PROMOTION_APPROVED, PlayerStatus.ACTIVE)) {
            throw stateChanged();
        }
        return new PromotionResult(playerId, previousId, target.id(), target.name(), headCoachId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> panelOf(UUID playerId) {
        return store.currentPanel(playerId);
    }

    // ---------- validaciones ----------

    private PlayerRecord lock(UUID playerId) {
        return store.lockPlayer(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
    }

    /** DAD SUP-04: exactamente tres evaluadores distintos, con cuenta activa y un rol admitido en el panel. */
    private void validatePanel(List<UUID> panel) {
        Set<UUID> distinct = new LinkedHashSet<>(panel);
        if (distinct.size() != PANEL_SIZE) {
            throw new DomainException(ErrorCode.PANEL_INVALID,
                    "El jugador debe tener exactamente " + PANEL_SIZE + " evaluadores asignados (tiene " + distinct.size() + ").");
        }
        Map<UUID, Set<String>> roles = staff.activeRolesOf(distinct);
        for (UUID evaluatorId : distinct) {
            Set<String> evaluatorRoles = roles.getOrDefault(evaluatorId, Set.of());
            if (evaluatorRoles.stream().noneMatch(settings.panelRoles()::contains)) {
                throw new DomainException(ErrorCode.PANEL_INVALID, "El evaluador " + evaluatorId
                        + " ya no es una cuenta activa con un rol admitido en el panel.");
            }
        }
    }

    private static DomainException stateChanged() {
        return new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, "El expediente cambió de estado; vuelva a consultarlo.");
    }
}
