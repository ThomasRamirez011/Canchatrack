package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.UUID;

public record SyncBatch(UUID id, UUID eventId, int received, int accepted, int duplicates, int rejected) {
}
