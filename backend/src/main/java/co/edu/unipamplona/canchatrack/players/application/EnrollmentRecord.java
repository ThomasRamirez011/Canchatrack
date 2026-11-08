package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.UUID;

/** Inscripción recién persistida, con los valores que genera la base de datos. */
public record EnrollmentRecord(UUID id, String filingCode, Instant submittedAt) {
}
