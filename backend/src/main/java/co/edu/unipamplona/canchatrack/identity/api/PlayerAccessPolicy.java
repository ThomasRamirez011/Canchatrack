package co.edu.unipamplona.canchatrack.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Fachada pública: ¿puede este usuario ver o actuar sobre este jugador? (PA-04, PA-14). Los demás módulos la usan
 * antes de exponer datos de un jugador; no deben consultar el vínculo acudiente-jugador por su cuenta.
 *
 * <p>Reglas vigentes. {@link #canAccess}: acceso completo; coordinador y comité técnico, todos los jugadores; acudiente,
 * solo los jugadores con vínculo vigente; entrenador y evaluador, ninguno (no deben ver pagos, documentos ni contacto).
 * {@link #canView}: acceso por clase de dato ({@link PlayerData}); además de lo anterior, el entrenador titular vigente
 * de un jugador ve sus datos básicos, su asistencia y sus partidos, y un evaluador con asignación vigente ve sus datos
 * básicos. Lo no resuelto se deniega: es el valor por defecto seguro.
 */
public interface PlayerAccessPolicy {

    boolean canAccess(UUID userId, Set<String> roleCodes, UUID playerId);

    /**
     * ¿Puede ver esta clase de dato del jugador? Quien tiene acceso completo ({@link #canAccess}) siempre puede; el
     * personal deportivo solo las clases que su función requiere. Por defecto equivale a {@link #canAccess}.
     */
    default boolean canView(UUID userId, Set<String> roleCodes, UUID playerId, PlayerData data) {
        return canAccess(userId, roleCodes, playerId);
    }

    /**
     * Alcance para listados: qué jugadores puede ver un usuario con estos roles. Debe ser coherente con
     * {@link #canAccess}; el vínculo acudiente-jugador lo resuelve quien consulta (con el id de la cuenta).
     */
    PlayerScope scopeOf(Set<String> roleCodes);
}
