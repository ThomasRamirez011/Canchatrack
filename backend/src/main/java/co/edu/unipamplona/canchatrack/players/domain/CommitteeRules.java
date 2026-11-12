package co.edu.unipamplona.canchatrack.players.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reglas puras de la decisión del Comité Técnico (RN-03, RF-045; DDS UC-02). Solo validan la forma de los datos;
 * lo que exige consultar otros módulos o la base (roles, archivo del acta, fecha de hoy, cupo) lo comprueba el servicio.
 */
public final class CommitteeRules {

    public static final int MAX_MINUTE_NUMBER = 30;
    public static final int MAX_ATTENDEES = 30;
    public static final int MAX_ATTENDEE_NAME = 150;
    public static final int MAX_REASON = 2000;
    /** DAD SUP-04 / DDS UC-02: exactamente tres evaluadores. */
    public static final int PANEL_SIZE = 3;

    public static final String FIELD_EVALUATORS = "evaluatorIds";
    public static final String FIELD_HEAD_COACH = "headCoachId";

    private CommitteeRules() {
    }

    /** Campos que, al fallar, se informan con el código {@code PANEL_INVALID} (DDS secc. 13). */
    public static boolean isPanelField(String field) {
        return FIELD_EVALUATORS.equals(field) || FIELD_HEAD_COACH.equals(field);
    }

    /** Datos del acta: número, fecha, asistentes y archivo adjunto. La fecha no puede ser futura (lo valida el servicio). */
    public static List<FieldError> minuteViolations(String minuteNumber, LocalDate meetingDate, List<String> attendees,
                                                    UUID minuteFileId) {
        List<FieldError> errors = new ArrayList<>();
        if (minuteNumber == null || minuteNumber.isBlank()) {
            errors.add(new FieldError("minuteNumber", "El número de acta es obligatorio."));
        } else if (minuteNumber.trim().length() > MAX_MINUTE_NUMBER) {
            errors.add(new FieldError("minuteNumber", "El número de acta no puede superar " + MAX_MINUTE_NUMBER + " caracteres."));
        }
        if (meetingDate == null) {
            errors.add(new FieldError("meetingDate", "La fecha de la reunión es obligatoria."));
        }
        if (attendees == null || attendees.isEmpty()) {
            errors.add(new FieldError("attendees", "Indique al menos un asistente a la reunión."));
        } else if (attendees.size() > MAX_ATTENDEES) {
            errors.add(new FieldError("attendees", "No puede haber más de " + MAX_ATTENDEES + " asistentes."));
        } else if (attendees.stream().anyMatch(a -> a == null || a.isBlank())) {
            errors.add(new FieldError("attendees", "Todos los asistentes deben tener nombre."));
        } else if (attendees.stream().anyMatch(a -> a.trim().length() > MAX_ATTENDEE_NAME)) {
            errors.add(new FieldError("attendees", "El nombre de un asistente no puede superar " + MAX_ATTENDEE_NAME + " caracteres."));
        }
        if (minuteFileId == null) {
            errors.add(new FieldError("minuteFileId", "El acta firmada (archivo) es obligatoria."));
        }
        return errors;
    }

    /** Categoría, entrenador titular, panel de tres evaluadores distintos y motivo de excepción de cupo (opcional). */
    public static List<FieldError> assignmentViolations(UUID categoryId, UUID headCoachId, List<UUID> evaluatorIds,
                                                        String capacityExceptionReason) {
        List<FieldError> errors = new ArrayList<>();
        if (categoryId == null) {
            errors.add(new FieldError("categoryId", "La categoría es obligatoria."));
        }
        if (headCoachId == null) {
            errors.add(new FieldError(FIELD_HEAD_COACH, "El entrenador titular es obligatorio."));
        }
        if (evaluatorIds == null || evaluatorIds.size() != PANEL_SIZE) {
            errors.add(new FieldError(FIELD_EVALUATORS, "El panel debe tener exactamente " + PANEL_SIZE + " evaluadores."));
        } else if (evaluatorIds.stream().anyMatch(id -> id == null)) {
            errors.add(new FieldError(FIELD_EVALUATORS, "Todos los evaluadores deben indicarse."));
        } else {
            Set<UUID> distinct = new HashSet<>(evaluatorIds);
            if (distinct.size() != evaluatorIds.size()) {
                errors.add(new FieldError(FIELD_EVALUATORS, "Los evaluadores del panel no pueden repetirse."));
            }
        }
        if (capacityExceptionReason != null && capacityExceptionReason.trim().length() > MAX_REASON) {
            errors.add(new FieldError("capacityExceptionReason", "El motivo no puede superar " + MAX_REASON + " caracteres."));
        }
        return errors;
    }

    /** El rechazo exige motivo (RN-03; la base lo impone también con {@code ck_committee_minutes_reason}). */
    public static List<FieldError> rejectionViolations(String reason) {
        List<FieldError> errors = new ArrayList<>();
        if (reason == null || reason.isBlank()) {
            errors.add(new FieldError("reason", "El motivo del rechazo es obligatorio."));
        } else if (reason.trim().length() > MAX_REASON) {
            errors.add(new FieldError("reason", "El motivo no puede superar " + MAX_REASON + " caracteres."));
        }
        return errors;
    }
}
