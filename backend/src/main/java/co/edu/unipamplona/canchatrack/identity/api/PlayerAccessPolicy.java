package co.edu.unipamplona.canchatrack.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Fachada pública: ¿puede este usuario ver o actuar sobre este jugador? (PA-04, PA-14). Los demás módulos la usan
 * antes de exponer datos de un jugador; no deben consultar el vínculo acudiente-jugador por su cuenta.
 *
 * <p>Reglas vigentes: coordinador y comité técnico, todos los jugadores; acudiente, solo los jugadores con vínculo
 * vigente; entrenador y evaluador, ninguno todavía (su alcance depende de la categoría asignada y de las asignaciones
 * de evaluación, que define el módulo players). Lo no resuelto se deniega: es el valor por defecto seguro.
 */
public interface PlayerAccessPolicy {

    boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId);

    /**
     * Alcance para listados: qué jugadores puede ver un usuario con estos roles. Debe ser coherente con
     * {@link #canAccess}; el vínculo acudiente-jugador lo resuelve quien consulta (con el id de la cuenta).
     */
    PlayerScope scopeOf(Set<String> roleCodes);
}
