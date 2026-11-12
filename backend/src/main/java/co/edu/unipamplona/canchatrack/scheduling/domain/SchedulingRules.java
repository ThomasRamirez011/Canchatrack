package co.edu.unipamplona.canchatrack.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Reglas puras de venues y eventos (DDS 11.2). La hora actual la aporta quien llama (reloj de la base de datos). */
public final class SchedulingRules {

    /** Los partidos de torneo los administra {@code competitions}; aquí solo entrenamientos y evaluaciones. */
    public static final Set<String> EVENT_TYPES = Set.of("TRAINING", "EVALUATION");
    public static final Duration MAX_DURATION = Duration.ofHours(12);
    public static final int MAX_NAME = 120;
    public static final int MAX_ADDRESS = 250;
    public static final int MAX_MEETING_POINT = 200;
    public static final int MAX_TEXT = 2000;

    private SchedulingRules() {
    }

    public static List<FieldError> venueViolations(String name, String address) {
        List<FieldError> errors = new ArrayList<>();
        if (name == null || name.isBlank()) {
            errors.add(new FieldError("name", "El nombre del escenario es obligatorio."));
        } else if (name.trim().length() > MAX_NAME) {
            errors.add(new FieldError("name", "El nombre no puede superar " + MAX_NAME + " caracteres."));
        }
        if (address != null && address.trim().length() > MAX_ADDRESS) {
            errors.add(new FieldError("address", "La dirección no puede superar " + MAX_ADDRESS + " caracteres."));
        }
        return errors;
    }

    public static List<FieldError> eventViolations(String eventType, Instant startAt, Instant endAt, Instant meetingAt,
                                                   String meetingPoint, String instructions, Instant now) {
        List<FieldError> errors = new ArrayList<>();
        if (eventType == null || !EVENT_TYPES.contains(eventType.trim().toUpperCase())) {
            errors.add(new FieldError("eventType", "El tipo debe ser uno de: " + String.join(", ", EVENT_TYPES.stream().sorted().toList()) + "."));
        }
        if (startAt == null) {
            errors.add(new FieldError("startAt", "La hora de inicio es obligatoria."));
        } else if (!startAt.isAfter(now)) {
            errors.add(new FieldError("startAt", "El evento debe programarse a futuro."));
        }
        if (endAt == null) {
            errors.add(new FieldError("endAt", "La hora de fin es obligatoria."));
        } else if (startAt != null && !endAt.isAfter(startAt)) {
            errors.add(new FieldError("endAt", "El fin debe ser posterior al inicio."));
        } else if (startAt != null && Duration.between(startAt, endAt).compareTo(MAX_DURATION) > 0) {
            errors.add(new FieldError("endAt", "El evento no puede durar más de 12 horas."));
        }
        if (meetingAt != null && startAt != null && meetingAt.isAfter(startAt)) {
            errors.add(new FieldError("meetingAt", "La concentración no puede ser posterior al inicio."));
        }
        if (meetingPoint != null && meetingPoint.trim().length() > MAX_MEETING_POINT) {
            errors.add(new FieldError("meetingPoint", "El punto de encuentro no puede superar " + MAX_MEETING_POINT + " caracteres."));
        }
        if (instructions != null && instructions.trim().length() > MAX_TEXT) {
            errors.add(new FieldError("instructions", "Las instrucciones no pueden superar " + MAX_TEXT + " caracteres."));
        }
        return errors;
    }

    public static List<FieldError> cancellationViolations(String reason) {
        List<FieldError> errors = new ArrayList<>();
        if (reason == null || reason.isBlank()) {
            errors.add(new FieldError("reason", "El motivo de la cancelación es obligatorio."));
        } else if (reason.trim().length() > MAX_TEXT) {
            errors.add(new FieldError("reason", "El motivo no puede superar " + MAX_TEXT + " caracteres."));
        }
        return errors;
    }

    /** Dos eventos se cruzan si sus intervalos semiabiertos [inicio, fin) tienen intersección. */
    public static boolean overlaps(Instant startA, Instant endA, Instant startB, Instant endB) {
        return startA.isBefore(endB) && startB.isBefore(endA);
    }
}
