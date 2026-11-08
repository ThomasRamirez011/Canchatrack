package co.edu.unipamplona.canchatrack.players.domain;

import java.util.Optional;

/** Ciclo de vida de una temporada. El cierre (CLOSED) lo ejecuta el caso de uso CU-11, aún no construido. */
public enum SeasonStatus {
    PLANNED, ACTIVE, CLOSED;

    public static Optional<SeasonStatus> parse(String code) {
        for (SeasonStatus status : values()) {
            if (status.name().equals(code)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
