package co.edu.unipamplona.canchatrack.players.api;

import java.util.List;
import java.util.UUID;

/** Jugador que entra en evaluación: su categoría vigente y el panel de tres evaluadores asignado por el comité. */
public record EvaluationSubject(UUID playerId, String playerCode, UUID categoryId, List<UUID> panel) {
}
