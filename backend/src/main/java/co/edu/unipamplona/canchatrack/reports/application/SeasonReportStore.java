package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Puerto del informe de temporada. Las lecturas de datos deportivos son de solo lectura sobre tablas de otros módulos
 * (modelo de lectura); las escrituras ({@code season_reports}, {@code season_closures}) exigen una transacción activa.
 */
public interface SeasonReportStore {

    Optional<SeasonReportFacts.Season> findSeason(UUID seasonId);

    Optional<SeasonReportFacts.Player> findPlayer(UUID playerId);

    SeasonReportFacts.Attendance attendance(UUID playerId, Instant from, Instant to);

    List<SeasonReportFacts.Evaluation> evaluations(UUID playerId, Instant from, Instant to);

    List<SeasonReportFacts.MatchLine> matches(UUID playerId, Instant from, Instant to);

    /** ¿Cambió algo del expediente deportivo del jugador después de {@code since}? (asistencia, evaluaciones, partidos). */
    boolean changedSince(UUID playerId, Instant since);

    // ---------- informes ----------

    /** Serializa a quien genera versiones del mismo jugador y temporada hasta el fin de la transacción. */
    void lockForGeneration(UUID playerId, UUID seasonId);

    long nextConsecutive();

    Optional<SeasonReport> latest(UUID playerId, UUID seasonId);

    Optional<SeasonReport> find(UUID reportId);

    Optional<SeasonReport> findByVerificationCode(String code);

    List<SeasonReport> list(UUID playerId, UUID seasonId);

    SeasonReport insert(NewReport report);

    /** Datos de un informe nuevo; la base completa el identificador y la fecha de generación. */
    record NewReport(UUID playerId, UUID seasonId, long consecutive, int version, UUID previousReportId, String reason,
                     String verificationCode, String templateVersion, String snapshotJson, UUID fileId, String fileHash,
                     UUID generatedBy) {
    }

    // ---------- cierres ----------

    SeasonClosure insertClosure(UUID playerId, UUID seasonId, UUID reportId, UUID closedBy);

    /** Cierre vigente más reciente del jugador (el que una reapertura deshace). */
    Optional<SeasonClosure> latestOpenClosure(UUID playerId);

    Optional<SeasonClosure> latestClosure(UUID playerId);

    SeasonClosure markReopened(UUID closureId, UUID reopenedBy, String reason, String detail);
}
