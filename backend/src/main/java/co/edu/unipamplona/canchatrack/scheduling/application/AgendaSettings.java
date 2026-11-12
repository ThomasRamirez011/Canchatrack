package co.edu.unipamplona.canchatrack.scheduling.application;

import java.util.List;

/**
 * Parámetros de los avisos de agenda.
 *
 * @param appUrl             dirección pública del front para el enlace del correo (RF-016); vacía si no se configuró
 * @param reminderHours      horas antes de un entrenamiento o evaluación en que sale un recordatorio, ascendentes (RF-019)
 * @param matchReminderHours lo mismo para los partidos, que piden más preparación (transporte, uniforme, autorización)
 */
public record AgendaSettings(String appUrl, List<Integer> reminderHours, List<Integer> matchReminderHours) {

    public AgendaSettings {
        appUrl = appUrl == null ? "" : appUrl.trim();
        reminderHours = List.copyOf(reminderHours);
        matchReminderHours = List.copyOf(matchReminderHours);
    }

    /** Los partidos usan los mismos tramos que los demás eventos. */
    public AgendaSettings(String appUrl, List<Integer> reminderHours) {
        this(appUrl, reminderHours, reminderHours);
    }

    /** Tramos que corresponden al tipo de evento. */
    public List<Integer> hoursFor(String eventType) {
        return "FRIENDLY_MATCH".equals(eventType) || "TOURNAMENT_MATCH".equals(eventType) ? matchReminderHours : reminderHours;
    }

    /** El tramo más lejano de todos: hasta dónde mirar hacia adelante. */
    public int farthestHours() {
        int farthest = 0;
        for (int h : reminderHours) {
            farthest = Math.max(farthest, h);
        }
        for (int h : matchReminderHours) {
            farthest = Math.max(farthest, h);
        }
        return farthest;
    }
}
