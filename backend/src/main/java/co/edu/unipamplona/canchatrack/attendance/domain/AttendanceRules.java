package co.edu.unipamplona.canchatrack.attendance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reglas puras de asistencia (RF-021, RF-022, RN-15). */
public final class AttendanceRules {

    public static final Set<String> STATUSES = Set.of("PRESENT", "LATE", "EXCUSED", "ABSENT");
    public static final int MAX_BATCH = 500;
    public static final int MAX_KEY = 100;
    public static final int MAX_REASON = 2000;

    private AttendanceRules() {
    }

    /** Motivos por los que una marca individual se rechaza (lista vacía = válida). */
    public static List<String> markViolations(UUID clientUuid, UUID playerId, String status, boolean excuseAccepted) {
        List<String> reasons = new ArrayList<>();
        if (clientUuid == null) {
            reasons.add("clientUuid es obligatorio.");
        }
        if (playerId == null) {
            reasons.add("playerId es obligatorio.");
        }
        if (status == null || !STATUSES.contains(status.trim().toUpperCase())) {
            reasons.add("El estado debe ser uno de: " + String.join(", ", STATUSES.stream().sorted().toList()) + ".");
        } else if (excuseAccepted && !"EXCUSED".equals(status.trim().toUpperCase())) {
            reasons.add("Solo una excusa puede marcarse como aceptada.");
        }
        return reasons;
    }

    public static List<FieldError> correctionViolations(String newStatus, boolean excuseAccepted, String reason) {
        List<FieldError> errors = new ArrayList<>();
        if (newStatus == null || !STATUSES.contains(newStatus.trim().toUpperCase())) {
            errors.add(new FieldError("status", "El estado debe ser uno de: " + String.join(", ", STATUSES.stream().sorted().toList()) + "."));
        } else if (excuseAccepted && !"EXCUSED".equals(newStatus.trim().toUpperCase())) {
            errors.add(new FieldError("excuseAccepted", "Solo una excusa puede marcarse como aceptada."));
        }
        if (reason == null || reason.isBlank()) {
            errors.add(new FieldError("reason", "La justificación de la corrección es obligatoria."));
        } else if (reason.trim().length() > MAX_REASON) {
            errors.add(new FieldError("reason", "La justificación no puede superar " + MAX_REASON + " caracteres."));
        }
        return errors;
    }

    /**
     * Porcentaje de asistencia: (presentes + tardes) / (sesiones cerradas − excusas aceptadas), con un decimal. Una excusa
     * aceptada no cuenta a favor ni en contra; una no aceptada cuenta como falta. {@code null} si no hay sesiones que contar.
     */
    public static BigDecimal percentage(int attended, int sessions, int acceptedExcuses) {
        int counted = sessions - acceptedExcuses;
        if (counted <= 0) {
            return null;
        }
        return BigDecimal.valueOf(attended * 100L).divide(BigDecimal.valueOf(counted), 1, RoundingMode.HALF_UP);
    }
}
