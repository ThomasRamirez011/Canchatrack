package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.UUID;

public record CorrectionCommand(UUID playerId, String status, boolean excuseAccepted, String reason) {
}
