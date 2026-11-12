package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.UUID;

public record Rejection(UUID clientUuid, UUID playerId, String reason) {
}
