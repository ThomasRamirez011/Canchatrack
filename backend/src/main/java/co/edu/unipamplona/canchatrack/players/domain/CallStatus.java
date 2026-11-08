package co.edu.unipamplona.canchatrack.players.domain;

import java.util.Optional;

public enum CallStatus {
    DRAFT, PUBLISHED, CANCELLED;

    public static Optional<CallStatus> parse(String code) {
        for (CallStatus status : values()) {
            if (status.name().equals(code)) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
