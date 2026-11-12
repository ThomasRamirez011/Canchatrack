package co.edu.unipamplona.canchatrack.evaluations.domain;

import java.util.Optional;

/** Concepto de un evaluador (DAD CU-07). Los nombres coinciden con {@code evaluator_decisions.verdict}. */
public enum Verdict {

    FAVORABLE,
    FOLLOW_UP_REQUIRED,
    UNFAVORABLE;

    public static Optional<Verdict> parse(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (Verdict verdict : values()) {
            if (verdict.name().equals(code.trim())) {
                return Optional.of(verdict);
            }
        }
        return Optional.empty();
    }
}
