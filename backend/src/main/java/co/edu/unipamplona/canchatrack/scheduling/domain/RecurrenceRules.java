package co.edu.unipamplona.canchatrack.scheduling.domain;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reglas puras de las sesiones recurrentes (RF-014): una serie semanal repite los días de la semana indicados, a la misma
 * hora local, entre una fecha de inicio y una de fin (ambas incluidas). Las horas locales se convierten con la zona del club,
 * de modo que "17:00" sigue siendo las 17:00 del club aunque cambie el desfase con UTC.
 */
public final class RecurrenceRules {

    /** Tope de sesiones por serie. */
    public static final int MAX_OCCURRENCES = 60;
    /** Tope de días entre el inicio y el fin; acota el recorrido antes de contar sesiones. */
    public static final int MAX_SPAN_DAYS = 366;

    private RecurrenceRules() {
    }

    /** Una sesión de la serie; {@code position} empieza en 1 y sigue el orden cronológico. */
    public record Slot(int position, Instant startAt, Instant endAt) {
    }

    public static List<FieldError> violations(Set<DayOfWeek> weekdays, LocalDate startDate, LocalDate endDate,
                                              LocalTime startTime, LocalTime endTime, LocalDate today, ZoneId zone) {
        List<FieldError> errors = new ArrayList<>();
        if (weekdays == null || weekdays.isEmpty()) {
            errors.add(new FieldError("weekdays", "Indique al menos un día de la semana."));
        }
        if (startDate == null) {
            errors.add(new FieldError("startDate", "La fecha de inicio es obligatoria."));
        } else if (startDate.isBefore(today)) {
            errors.add(new FieldError("startDate", "La fecha de inicio no puede ser anterior a hoy."));
        }
        boolean datesOk = startDate != null && endDate != null;
        if (endDate == null) {
            errors.add(new FieldError("endDate", "La fecha de fin es obligatoria."));
        } else if (startDate != null && endDate.isBefore(startDate)) {
            errors.add(new FieldError("endDate", "La fecha de fin no puede ser anterior a la de inicio."));
            datesOk = false;
        } else if (startDate != null && ChronoUnit.DAYS.between(startDate, endDate) > MAX_SPAN_DAYS) {
            errors.add(new FieldError("endDate", "La serie no puede abarcar más de " + MAX_SPAN_DAYS + " días."));
            datesOk = false;
        }
        boolean timesOk = startTime != null && endTime != null;
        if (startTime == null) {
            errors.add(new FieldError("startTime", "La hora de inicio es obligatoria."));
        }
        if (endTime == null) {
            errors.add(new FieldError("endTime", "La hora de fin es obligatoria."));
        } else if (startTime != null && !endTime.isAfter(startTime)) {
            errors.add(new FieldError("endTime", "El fin debe ser posterior al inicio (una sesión no cruza la medianoche)."));
            timesOk = false;
        } else if (startTime != null && Duration.between(startTime, endTime).compareTo(SchedulingRules.MAX_DURATION) > 0) {
            errors.add(new FieldError("endTime", "Una sesión no puede durar más de 12 horas."));
            timesOk = false;
        }
        if (weekdays != null && !weekdays.isEmpty() && datesOk && timesOk) {
            int count = expand(weekdays, startDate, endDate, startTime, endTime, zone).size();
            if (count == 0) {
                errors.add(new FieldError("weekdays", "Ninguna fecha entre el inicio y el fin cae en los días indicados."));
            } else if (count > MAX_OCCURRENCES) {
                errors.add(new FieldError("endDate", "La serie no puede tener más de " + MAX_OCCURRENCES
                        + " sesiones (con estos datos tendría " + count + ")."));
            }
        }
        return errors;
    }

    /** Sesiones en orden cronológico. Los datos deben haber pasado {@link #violations}. */
    public static List<Slot> expand(Set<DayOfWeek> weekdays, LocalDate startDate, LocalDate endDate, LocalTime startTime,
                                    LocalTime endTime, ZoneId zone) {
        List<Slot> slots = new ArrayList<>();
        for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
            if (weekdays.contains(day.getDayOfWeek())) {
                Instant start = ZonedDateTime.of(day, startTime, zone).toInstant();
                Instant end = ZonedDateTime.of(day, endTime, zone).toInstant();
                slots.add(new Slot(slots.size() + 1, start, end));
            }
        }
        return slots;
    }
}
