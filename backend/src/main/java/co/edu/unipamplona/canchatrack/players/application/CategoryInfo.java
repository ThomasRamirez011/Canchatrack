package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

/**
 * Categoría por rango de año de nacimiento. {@code occupied} cuenta las asignaciones vigentes de jugadores que siguen
 * en proceso o activos (no retirados, rechazados ni con temporada cerrada).
 */
public record CategoryInfo(UUID id, String name, int birthYearMin, int birthYearMax, int capacity, int occupied,
                           UUID nextCategoryId, boolean active) {

    public int available() {
        return Math.max(0, capacity - occupied);
    }

    public boolean covers(int birthYear) {
        return birthYearMin <= birthYear && birthYear <= birthYearMax;
    }
}
