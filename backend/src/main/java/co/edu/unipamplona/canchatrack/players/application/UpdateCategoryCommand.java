package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

/** Modificación parcial: un campo {@code null} no se toca; {@code clearNextCategory} quita el destino de ascenso. */
public record UpdateCategoryCommand(String name, Integer birthYearMin, Integer birthYearMax, Integer capacity,
                                    UUID nextCategoryId, boolean clearNextCategory, Boolean active) {
}
