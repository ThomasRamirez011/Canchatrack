package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

public record PlayerDetail(UUID id, String code, String firstName, String lastName, String documentType,
                           String documentNumber, LocalDate birthDate, PlayerStatus status, Instant createdAt) {
}
