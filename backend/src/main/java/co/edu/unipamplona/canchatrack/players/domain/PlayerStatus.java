package co.edu.unipamplona.canchatrack.players.domain;

import java.util.Optional;

/** Estados del expediente del jugador (DAD 3.2; los nombres coinciden con {@code players.status}). */
public enum PlayerStatus {

    SUBMITTED,            // E02 inscripción radicada
    COMMITTEE_PENDING,    // E03 pendiente de comité técnico
    CATEGORY_ASSIGNED,    // E04
    PENDING_REGISTRATION, // E05 pendiente de matrícula
    ACTIVE,               // E06
    IN_EVALUATION,        // E07
    PROMOTION_APPROVED,   // E08
    SUSPENDED,            // E09
    WITHDRAWN,            // E10 final: solo lectura
    SEASON_CLOSED,        // E11
    REJECTED;             // E12 inscripción no aceptada

    /**
     * Una nueva inscripción del mismo jugador solo procede cuando la anterior terminó: no fue aceptada o la temporada
     * se cerró. Un jugador retirado queda en solo lectura (DAD 3.2) y cualquier otro estado es un proceso en curso.
     */
    public boolean allowsNewEnrollment() {
        return this == REJECTED || this == SEASON_CLOSED;
    }

    public static Optional<PlayerStatus> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (PlayerStatus status : values()) {
            if (status.name().equals(code.trim())) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
