package co.edu.unipamplona.canchatrack.players.application;

/** Decisión T-02: la inscripción no se acepta; el motivo es obligatorio. */
public record RejectEnrollmentCommand(MinuteData minute, String reason) {
}
