package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Instant;
import java.util.UUID;

/** Informe de temporada ya generado (fila inmutable de {@code season_reports}). */
public record SeasonReport(UUID id, UUID playerId, UUID seasonId, long consecutive, String number, int version,
                           UUID previousReportId, String reason, String verificationCode, String templateVersion,
                           UUID fileId, String fileHash, Instant generatedAt, UUID generatedBy) {
}
