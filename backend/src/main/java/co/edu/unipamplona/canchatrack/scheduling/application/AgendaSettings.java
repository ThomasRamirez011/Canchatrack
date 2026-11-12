package co.edu.unipamplona.canchatrack.scheduling.application;

import java.util.List;

/**
 * Parámetros de los avisos de agenda.
 *
 * @param appUrl        dirección pública del front para el enlace del correo (RF-016); vacía si no se configuró
 * @param reminderHours horas antes del evento en que sale un recordatorio, en orden ascendente (RF-019)
 */
public record AgendaSettings(String appUrl, List<Integer> reminderHours) {

    public AgendaSettings {
        appUrl = appUrl == null ? "" : appUrl.trim();
        reminderHours = List.copyOf(reminderHours);
    }
}
