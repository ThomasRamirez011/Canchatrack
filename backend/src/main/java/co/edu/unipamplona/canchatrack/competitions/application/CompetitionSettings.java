package co.edu.unipamplona.canchatrack.competitions.application;

import java.time.ZoneId;

/** Parámetros del módulo: la zona horaria del club decide qué día es el partido (vigencia del certificado) y qué día es hoy (mora). */
public record CompetitionSettings(ZoneId zone) {
}
