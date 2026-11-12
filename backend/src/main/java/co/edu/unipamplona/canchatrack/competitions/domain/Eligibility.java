package co.edu.unipamplona.canchatrack.competitions.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resultado de evaluar los requisitos para convocar a un jugador a un partido (RN-06, RN-07, RF-033): activo, certificado
 * médico vigente, autorización de participación vigente y cumplimiento de la política de mora. Solo guarda si cada
 * requisito se cumple, nunca el contenido de un documento de salud (RN-17).
 */
public record Eligibility(boolean active, boolean medicalCertificate, boolean participationAuthorization, boolean arrearsClear) {

    public static final String NOT_ACTIVE = "PLAYER_NOT_ACTIVE";
    public static final String MEDICAL_CERTIFICATE_INVALID = "MEDICAL_CERTIFICATE_INVALID";
    public static final String AUTHORIZATION_INVALID = "PARTICIPATION_AUTHORIZATION_INVALID";
    public static final String ARREARS = "ARREARS_POLICY";

    public boolean eligible() {
        return active && medicalCertificate && participationAuthorization && arrearsClear;
    }

    /** Causas de no elegibilidad en orden de prioridad; vacía si es elegible. */
    public List<String> causes() {
        List<String> causes = new ArrayList<>();
        if (!active) {
            causes.add(NOT_ACTIVE);
        }
        if (!medicalCertificate) {
            causes.add(MEDICAL_CERTIFICATE_INVALID);
        }
        if (!participationAuthorization) {
            causes.add(AUTHORIZATION_INVALID);
        }
        if (!arrearsClear) {
            causes.add(ARREARS);
        }
        return causes;
    }

    /** La causa que se registra como {@code ineligibility_code}; nula si es elegible. */
    public String primaryCause() {
        List<String> causes = causes();
        return causes.isEmpty() ? null : causes.get(0);
    }

    /** Estado de cada requisito, para guardarlo en {@code callup_entries.eligibility}. */
    public Map<String, Boolean> checks() {
        Map<String, Boolean> checks = new LinkedHashMap<>();
        checks.put("active", active);
        checks.put("medicalCertificate", medicalCertificate);
        checks.put("participationAuthorization", participationAuthorization);
        checks.put("arrearsClear", arrearsClear);
        return checks;
    }
}
