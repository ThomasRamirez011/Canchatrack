package co.edu.unipamplona.canchatrack.players.application;

import java.time.LocalDate;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

public record PlayerSummary(UUID id, String code, String firstName, String lastName, LocalDate birthDate,
                            PlayerStatus status) {
}
