package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

public record GrantExceptionCommand(UUID guardianId, String reason, Instant expiresAt) {
}
