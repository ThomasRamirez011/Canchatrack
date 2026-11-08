package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

/** Sugerencia de categoría por año de nacimiento (RF-046). {@code suggested} es {@code null} si ninguna activa lo cubre. */
public record CategorySuggestion(UUID playerId, int birthYear, CategoryInfo suggested) {
}
