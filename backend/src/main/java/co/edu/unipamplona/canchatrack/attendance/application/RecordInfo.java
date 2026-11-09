package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.UUID;

public record RecordInfo(UUID id, UUID playerId, String status, boolean excuseAccepted) {
}
