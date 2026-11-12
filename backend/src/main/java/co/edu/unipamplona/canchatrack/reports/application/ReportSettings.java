package co.edu.unipamplona.canchatrack.reports.application;

import java.time.ZoneId;

/** Zona horaria del club: decide qué día es «hoy» para la mora y los vencimientos. */
public record ReportSettings(ZoneId zone) {
}
