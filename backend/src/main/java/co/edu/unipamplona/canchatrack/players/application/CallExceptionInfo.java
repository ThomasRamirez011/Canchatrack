package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

/** Habilitación excepcional (RF-044). {@code active} lo calcula la base con su reloj. */
public record CallExceptionInfo(UUID id, UUID callId, UUID guardianId, String reason, UUID grantedBy,
                                Instant grantedAt, Instant expiresAt, boolean active) {
}
