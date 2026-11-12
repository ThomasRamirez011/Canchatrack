package co.edu.unipamplona.canchatrack.attendance.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AttendanceView(UUID eventId, boolean closed, Instant closedAt, List<RosterEntry> entries) {
}
