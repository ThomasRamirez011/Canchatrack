package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

public record CreateCategoryCommand(String name, Integer birthYearMin, Integer birthYearMax, Integer capacity,
                                    UUID nextCategoryId) {
}
