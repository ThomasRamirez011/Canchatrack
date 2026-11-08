package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/** Constancia de radicación. */
public record EnrollmentReceipt(UUID enrollmentId, String filingCode, UUID playerId, String playerCode,
                                PlayerStatus playerStatus, UUID callId, Instant submittedAt, boolean viaException) {
}
