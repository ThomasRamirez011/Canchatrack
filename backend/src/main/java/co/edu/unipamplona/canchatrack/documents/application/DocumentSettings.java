package co.edu.unipamplona.canchatrack.documents.application;

import java.time.ZoneId;

/** Parámetros del módulo: la zona del club decide qué día es «hoy» para vigencias y alertas. */
public record DocumentSettings(ZoneId zone, long maxFileSizeBytes) {
}
