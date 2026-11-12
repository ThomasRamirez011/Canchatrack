package co.edu.unipamplona.canchatrack.players.api;

import java.util.UUID;

/** Resultado de un ascenso: la categoría anterior se conserva en el historial (CA-14). */
public record PromotionResult(UUID playerId, UUID previousCategoryId, UUID categoryId, String categoryName,
                              UUID headCoachId) {
}
