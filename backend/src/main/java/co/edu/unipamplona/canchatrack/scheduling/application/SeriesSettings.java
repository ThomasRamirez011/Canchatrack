package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.ZoneId;

/** Zona horaria del club: las horas de una serie son horas locales de esa zona. */
public record SeriesSettings(ZoneId zone) {

    public SeriesSettings {
        if (zone == null) {
            throw new IllegalArgumentException("canchatrack.zone-id es obligatoria");
        }
    }
}
