package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Deshabilitar (suspender) y rehabilitar a un jugador (E06 ⇄ E09). Un jugador suspendido conserva su categoría y todo su
 * historial, pero sale de la plantilla de asistencia, no recibe cobro mensual ni puede ser convocado. Solo la
 * coordinación lo decide, con motivo, y queda en {@code state_history} y en la auditoría. El expediente se bloquea y el
 * cambio se condiciona al estado de origen, así que dos solicitudes simultáneas se serializan.
 */
@Service
public class PlayerStatusService {

    static final String SUSPEND = "T-13";
    static final String REINSTATE = "T-14";
    static final int MAX_REASON = 500;

    public static final String SUSPENDED_ACTION = "PLAYER_SUSPENDED";
    public static final String REINSTATED_ACTION = "PLAYER_REINSTATED";

    private final PlacementStore store;
    private final AuditWriter audit;

    public PlayerStatusService(PlacementStore store, AuditWriter audit) {
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public PlayerStatusChange suspend(AdminActor actor, UUID playerId, String reason) {
        String clean = requiredReason(reason);
        PlayerRecord player = lock(playerId);
        if (player.status() != PlayerStatus.ACTIVE) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "Solo un jugador activo puede deshabilitarse (estado actual: " + player.status() + ").");
        }
        return change(actor, playerId, SUSPEND, PlayerStatus.ACTIVE, PlayerStatus.SUSPENDED, clean, SUSPENDED_ACTION);
    }

    @Transactional
    public PlayerStatusChange reinstate(AdminActor actor, UUID playerId, String reason) {
        String clean = optionalReason(reason);
        PlayerRecord player = lock(playerId);
        if (player.status() != PlayerStatus.SUSPENDED) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "Solo un jugador deshabilitado puede rehabilitarse (estado actual: " + player.status() + ").");
        }
        return change(actor, playerId, REINSTATE, PlayerStatus.SUSPENDED, PlayerStatus.ACTIVE, clean, REINSTATED_ACTION);
    }

    private PlayerStatusChange change(AdminActor actor, UUID playerId, String code, PlayerStatus from, PlayerStatus to,
                                      String reason, String action) {
        if (!store.transition(playerId, from, to)) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, "El estado del expediente cambió; vuelva a consultarlo.");
        }
        store.recordStateChange(playerId, code, from, to, reason, actor.id());
        AuditRecord.Builder record = AuditRecord.builder(action, "player")
                .actor(actor.id(), AdminActor.ROLE)
                .entity(playerId)
                .before(AuditJson.of("status", from))
                .after(AuditJson.of("status", to))
                .origin(actor.ip(), actor.userAgent());
        if (reason != null) {
            record.reason(reason);
        }
        audit.write(record.build());
        return new PlayerStatusChange(playerId, from, to);
    }

    private PlayerRecord lock(UUID playerId) {
        return store.lockPlayer(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
    }

    private static String requiredReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw ValidationFailure.of("reason", "El motivo es obligatorio.");
        }
        return optionalReason(reason);
    }

    private static String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String clean = reason.strip();
        if (clean.length() > MAX_REASON) {
            throw ValidationFailure.of("reason", "El motivo no puede superar " + MAX_REASON + " caracteres.");
        }
        return clean;
    }
}
