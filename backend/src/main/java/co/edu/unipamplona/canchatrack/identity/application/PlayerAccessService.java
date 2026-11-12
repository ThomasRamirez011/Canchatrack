package co.edu.unipamplona.canchatrack.identity.application;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.identity.api.PlayerData;
import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;

@Service
public class PlayerAccessService implements PlayerAccessPolicy {

    private final GuardianLinks guardianLinks;
    private final StaffAssignments staff;

    public PlayerAccessService(GuardianLinks guardianLinks, StaffAssignments staff) {
        this.guardianLinks = guardianLinks;
        this.staff = staff;
    }

    @Override
    public boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId) {
        if (userId == null || playerId == null || roleCodes == null) {
            return false;
        }
        if (roleCodes.contains(RoleCode.COORDINATOR.name()) || roleCodes.contains(RoleCode.COMMITTEE.name())) {
            return true;
        }
        if (roleCodes.contains(RoleCode.GUARDIAN.name())) {
            return guardianLinks.isActiveGuardianOf(userId, playerId);
        }
        // COACH y EVALUATOR no tienen acceso completo: ven solo lo que concede canView.
        return false;
    }

    @Override
    public boolean canView(UUID userId, Set<String> roleCodes, UUID playerId, PlayerData data) {
        if (canAccess(userId, roleCodes, playerId)) {
            return true;
        }
        if (userId == null || playerId == null || roleCodes == null || data == null) {
            return false;
        }
        boolean coach = roleCodes.contains(RoleCode.COACH.name());
        if (coach && staff.isHeadCoachOf(userId, playerId)) {
            return true; // BASIC, ATTENDANCE y MATCHES: todo lo que el entrenador titular necesita
        }
        // El panel de evaluación admite entrenadores y evaluadores (canchatrack.committee.panel-roles): ambos roles
        // ven los datos básicos del jugador que evalúan y nada más; el resto lo da la propia ronda.
        return data == PlayerData.BASIC
                && (coach || roleCodes.contains(RoleCode.EVALUATOR.name()))
                && staff.isEvaluatorOf(userId, playerId);
    }

    @Override
    public PlayerScope scopeOf(Set<String> roleCodes) {
        if (roleCodes == null) {
            return PlayerScope.NONE;
        }
        if (roleCodes.contains(RoleCode.COORDINATOR.name()) || roleCodes.contains(RoleCode.COMMITTEE.name())) {
            return PlayerScope.ALL;
        }
        if (roleCodes.contains(RoleCode.GUARDIAN.name())) {
            return PlayerScope.LINKED_GUARDIAN;
        }
        if (roleCodes.contains(RoleCode.COACH.name()) || roleCodes.contains(RoleCode.EVALUATOR.name())) {
            return PlayerScope.ASSIGNED_STAFF;
        }
        return PlayerScope.NONE;
    }
}
