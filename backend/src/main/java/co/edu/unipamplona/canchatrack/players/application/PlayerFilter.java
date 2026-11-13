package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.api.PlayerScope;
import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/**
 * Criterio de consulta del listado de jugadores: qué puede ver el usuario y, opcionalmente, un estado.
 *
 * @param scope  alcance del usuario; {@code NONE} no devuelve nada
 * @param userId cuenta del usuario (necesaria para {@code LINKED_GUARDIAN} y {@code ASSIGNED_STAFF})
 * @param status filtro opcional por estado; {@code null} no filtra
 */
public record PlayerFilter(PlayerScope scope, UUID userId, PlayerStatus status) {

    /** {@code true} si el criterio nunca puede devolver filas: sin alcance, o alcance que exige una cuenta que no hay. */
    public boolean matchesNothing() {
        boolean needsUser = scope == PlayerScope.LINKED_GUARDIAN || scope == PlayerScope.ASSIGNED_STAFF;
        return scope == PlayerScope.NONE || (needsUser && userId == null);
    }
}
