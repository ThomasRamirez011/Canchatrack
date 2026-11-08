package co.edu.unipamplona.canchatrack.players.application;

import java.time.Duration;

/** @param maxExceptionValidity vigencia máxima de una habilitación excepcional desde que se concede */
public record CallSettings(Duration maxExceptionValidity) {

    public CallSettings {
        if (maxExceptionValidity == null || maxExceptionValidity.isZero() || maxExceptionValidity.isNegative()) {
            throw new IllegalArgumentException("maxExceptionValidity must be positive");
        }
    }
}
