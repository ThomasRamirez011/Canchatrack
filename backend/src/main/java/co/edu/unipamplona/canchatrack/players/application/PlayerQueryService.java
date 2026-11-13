package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.identity.api.PlayerData;
import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/** Consultas de jugadores, siempre filtradas por {@link PlayerAccessPolicy} (PA-04). */
@Service
@Transactional(readOnly = true)
public class PlayerQueryService {

    static final int MAX_PAGE_SIZE = 100;

    private final PlayerStore players;
    private final EnrollmentStore enrollments;
    private final PlayerAccessPolicy access;

    public PlayerQueryService(PlayerStore players, EnrollmentStore enrollments, PlayerAccessPolicy access) {
        this.players = players;
        this.enrollments = enrollments;
        this.access = access;
    }

    /**
     * Sin acceso y sin existencia responden igual (404): no se revela qué jugadores existen. Quien solo tiene acceso por
     * clase de dato (entrenador titular, evaluador asignado) recibe los datos básicos: sin documento de identidad y sin
     * inscripciones, que traen datos del acudiente.
     */
    public PlayerWithEnrollments get(UUID userId, Set<String> roles, UUID playerId) {
        if (access.canAccess(userId, roles, playerId)) {
            PlayerDetail detail = players.findDetail(playerId).orElseThrow(PlayerQueryService::notFound);
            return new PlayerWithEnrollments(detail, enrollments.findByPlayer(playerId));
        }
        if (access.canView(userId, roles, playerId, PlayerData.BASIC)) {
            PlayerDetail detail = players.findDetail(playerId).orElseThrow(PlayerQueryService::notFound);
            return new PlayerWithEnrollments(basic(detail), List.of());
        }
        throw notFound();
    }

    static PlayerDetail basic(PlayerDetail d) {
        return new PlayerDetail(d.id(), d.code(), d.firstName(), d.lastName(), null, null, d.birthDate(), d.status(),
                d.createdAt());
    }

    public PageResult<PlayerSummary> list(UUID userId, Set<String> roles, String statusFilter, int page, int size) {
        PlayerScope scope = access.scopeOf(roles);
        if (scope == PlayerScope.NONE) {
            throw new DomainException(ErrorCode.AUTH_FORBIDDEN, null);
        }
        PlayerStatus status = null;
        if (statusFilter != null && !statusFilter.isBlank()) {
            status = PlayerStatus.parse(statusFilter).orElseThrow(() -> new DomainException(
                    ErrorCode.VALIDATION_FAILED, null,
                    Map.of("errors", List.of(Map.of("field", "status", "message", "Estado desconocido.")))));
        }
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return players.list(new PlayerFilter(scope, userId, status), safePage, safeSize);
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
    }
}
