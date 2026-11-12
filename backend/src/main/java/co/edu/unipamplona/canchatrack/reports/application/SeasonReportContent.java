package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Instant;
import java.time.ZoneId;

/** Todo lo que el PDF muestra: los datos deportivos más la identificación del informe (consecutivo, versión, código). */
public record SeasonReportContent(String templateVersion, String number, int version, String verificationCode,
                                  Instant generatedAt, ZoneId zone, String reason, SeasonReportFacts facts) {
}
