package co.edu.unipamplona.canchatrack.attendance.application;

import java.time.Instant;
import java.util.UUID;

/** Una marca hecha en el dispositivo; {@code clientUuid} la identifica de forma única aunque se reintente. */
public record MarkInput(UUID clientUuid, UUID playerId, String status, boolean excuseAccepted, Instant clientRecordedAt) {
}
