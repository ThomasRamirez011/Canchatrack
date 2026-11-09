package co.edu.unipamplona.canchatrack.attendance.application;

import java.util.UUID;

/** {@code status} es nulo mientras el jugador no tiene marca. */
public record RosterEntry(UUID playerId, String code, String firstName, String lastName, String status, boolean excuseAccepted) {
}
