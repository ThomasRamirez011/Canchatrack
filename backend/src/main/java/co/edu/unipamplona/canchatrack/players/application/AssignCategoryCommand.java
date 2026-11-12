package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.UUID;

/** Decisión T-01: categoría, entrenador titular y panel de tres evaluadores, respaldada por un acta. */
public record AssignCategoryCommand(MinuteData minute, UUID categoryId, UUID headCoachId, List<UUID> evaluatorIds,
                                    String capacityExceptionReason) {
}
