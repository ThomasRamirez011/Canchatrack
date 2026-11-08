package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

public record EnrollmentSummary(UUID id, String filingCode, UUID callId, String callName, Instant submittedAt) {
}
