package co.edu.unipamplona.canchatrack.scheduling.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Cuándo corresponde un recordatorio de un evento (RF-019). Los recordatorios son configurables, por defecto 24 y 2
 * horas antes. Se evalúa por tramo, no por instante exacto: si el proceso estuvo detenido y ya faltan menos de 2 horas,
 * sale el recordatorio de 2 horas y no el de 24.
 */
public final class ReminderPolicy {

    public static final int MAX_HOURS = 168;
    public static final int MAX_STAGES = 5;

    private ReminderPolicy() {
    }

    /**
     * Interpreta la configuración (por ejemplo {@code "24,2"}). Devuelve las horas distintas en orden ascendente.
     *
     * @throws IllegalArgumentException con todos los problemas juntos si el valor no es válido
     */
    public static List<Integer> parseHours(String raw) {
        return parseHours(raw, "canchatrack.scheduling.reminders.hours");
    }

    /** @param property nombre de la propiedad que se interpreta, para que el mensaje señale la correcta */
    public static List<Integer> parseHours(String raw, String property) {
        List<String> problems = new ArrayList<>();
        List<Integer> hours = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                String value = part.trim();
                if (!value.matches("\\d{1,4}")) {
                    problems.add("'" + value + "' no es un número entero de horas");
                    continue;
                }
                int h = Integer.parseInt(value);
                if (h < 1 || h > MAX_HOURS) {
                    problems.add(h + " está fuera de 1.." + MAX_HOURS + " horas");
                } else if (!hours.contains(h)) {
                    hours.add(h);
                }
            }
        }
        if (hours.size() > MAX_STAGES) {
            problems.add("se admiten como máximo " + MAX_STAGES + " recordatorios");
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(property + " no es válido: " + String.join("; ", problems));
        }
        hours.sort(Integer::compare);
        return List.copyOf(hours);
    }

    /**
     * Tramo de recordatorio que corresponde ahora: el menor número de horas {@code h} tal que falta {@code h} horas o
     * menos para el inicio. No hay recordatorio si el evento ya empezó, si aún falta más que el mayor tramo, o si el
     * evento se programó dentro de ese tramo (el aviso de programación ya cubre la fecha).
     *
     * @param hoursAscending tramos configurados en orden ascendente
     */
    public static OptionalInt stage(Instant now, Instant startAt, Instant createdAt, List<Integer> hoursAscending) {
        if (!now.isBefore(startAt)) {
            return OptionalInt.empty();
        }
        Duration remaining = Duration.between(now, startAt);
        for (int h : hoursAscending) {
            Duration window = Duration.ofHours(h);
            if (remaining.compareTo(window) <= 0) {
                return createdAt.isAfter(startAt.minus(window)) ? OptionalInt.empty() : OptionalInt.of(h);
            }
        }
        return OptionalInt.empty();
    }
}
