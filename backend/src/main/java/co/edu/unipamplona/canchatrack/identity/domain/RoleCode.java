package co.edu.unipamplona.canchatrack.identity.domain;

import java.util.Optional;

/** Códigos de rol de la matriz de capacidades del DAD 4.1. Coinciden con {@code roles.code} (V2). */
public enum RoleCode {
    GUARDIAN,
    COORDINATOR,
    COACH,
    EVALUATOR,
    COMMITTEE;

    public static Optional<RoleCode> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (RoleCode role : values()) {
            if (role.name().equals(code.trim())) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
