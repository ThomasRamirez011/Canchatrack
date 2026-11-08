package co.edu.unipamplona.canchatrack.identity.application;

import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;

@Service
public class PlayerAccessService implements PlayerAccessPolicy {

    private final GuardianLinks guardianLinks;

    public PlayerAccessService(GuardianLinks guardianLinks) {
        this.guardianLinks = guardianLinks;
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
        // COACH y EVALUATOR: su alcance depende de datos del módulo players (categoría asignada, panel de evaluación).
        // Hasta definirlo se deniega.
        return false;
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
        return PlayerScope.NONE;
    }
}
