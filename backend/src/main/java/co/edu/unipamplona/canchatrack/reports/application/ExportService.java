package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.reports.domain.Csv;
import co.edu.unipamplona.canchatrack.reports.domain.ExportRules;
import co.edu.unipamplona.canchatrack.reports.domain.FieldError;

/**
 * Exportaciones a CSV del ERS 7.3 (CU-12): plantilla, asistencia, convocatorias y resultados, y seguimiento de evaluación
 * (la cartera vive en {@link ReportService}). Solo lectura salvo el rastro de auditoría: cada exportación queda como
 * {@code REPORT_EXPORTED} con sus filtros y el número de filas, nunca con el contenido (DDS 12.2). La autorización
 * (coordinación y comité) se aplica en la capa web.
 */
@Service
public class ExportService {

    private final ExportStore store;
    private final AuditWriter audit;
    private final Clock clock;
    private final ReportSettings settings;

    public ExportService(ExportStore store, AuditWriter audit, Clock clock, ReportSettings settings) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    /** Plantilla vigente: jugadores con categoría asignada hoy. */
    @Transactional
    public ExportFile roster(ReportsActor actor, UUID categoryId) {
        List<FieldError> errors = new ArrayList<>();
        checkCategory(categoryId, errors);
        ValidationFailure.throwIfAny(errors);

        LocalDate today = LocalDate.now(clock.withZone(settings.zone()));
        List<List<Object>> rows = capped(limit -> store.roster(categoryId, limit));
        ExportFile file = file("plantilla-" + today + ".csv", ExportColumns.ROSTER, rows);
        record(actor, "roster", file, "categoryId", categoryId);
        return file;
    }

    /** Asistencia por sesión cerrada y jugador. Con temporada, las fechas son opcionales; sin ella, obligatorias. */
    @Transactional
    public ExportFile attendance(ReportsActor actor, UUID seasonId, UUID categoryId, LocalDate from, LocalDate to) {
        List<FieldError> errors = new ArrayList<>();
        Optional<ExportStore.Period> season = checkSeason(seasonId, from, to, errors);
        checkCategory(categoryId, errors);
        ValidationFailure.throwIfAny(errors);

        ZoneId zone = settings.zone();
        ExportRules.Window window = window(season, from, to);
        List<List<Object>> rows = capped(limit -> store.attendance(categoryId, window.start(), window.end(), limit));
        ExportFile file = file(ExportRules.fileName("asistencia", window, zone), ExportColumns.ATTENDANCE, rows);
        record(actor, "attendance", file, "seasonId", seasonId, "categoryId", categoryId, "from", window.firstDay(zone),
                "to", window.lastDay(zone));
        return file;
    }

    /** Convocatorias y resultados de los partidos que empiezan en la ventana, con puntos y minutos por convocado. */
    @Transactional
    public ExportFile matches(ReportsActor actor, UUID seasonId, UUID categoryId, UUID tournamentId, LocalDate from,
                              LocalDate to) {
        List<FieldError> errors = new ArrayList<>();
        Optional<ExportStore.Period> season = checkSeason(seasonId, from, to, errors);
        checkCategory(categoryId, errors);
        if (tournamentId != null && !store.tournamentExists(tournamentId)) {
            errors.add(new FieldError("tournamentId", "El torneo no existe."));
        }
        ValidationFailure.throwIfAny(errors);

        ZoneId zone = settings.zone();
        ExportRules.Window window = window(season, from, to);
        List<List<Object>> rows = capped(limit -> store.matches(categoryId, tournamentId, window.start(), window.end(), limit));
        ExportFile file = file(ExportRules.fileName("convocatorias", window, zone), ExportColumns.MATCHES, rows);
        record(actor, "matches", file, "seasonId", seasonId, "categoryId", categoryId, "tournamentId", tournamentId,
                "from", window.firstDay(zone), "to", window.lastDay(zone));
        return file;
    }

    /**
     * Seguimiento de las rondas de evaluación cuyo periodo toca la ventana. Solo cuenta conceptos por tipo: no publica quién
     * dio cada uno ni sus justificaciones (RN-10, RN-17).
     */
    @Transactional
    public ExportFile evaluations(ReportsActor actor, UUID seasonId, UUID categoryId, LocalDate from, LocalDate to) {
        List<FieldError> errors = new ArrayList<>();
        Optional<ExportStore.Period> season = checkSeason(seasonId, from, to, errors);
        checkCategory(categoryId, errors);
        ValidationFailure.throwIfAny(errors);

        ZoneId zone = settings.zone();
        ExportRules.Window window = window(season, from, to);
        LocalDate firstDay = window.firstDay(zone);
        LocalDate lastDay = window.lastDay(zone);
        List<List<Object>> rows = capped(limit -> store.evaluations(categoryId, firstDay, lastDay, limit));
        ExportFile file = file(ExportRules.fileName("evaluaciones", window, zone), ExportColumns.EVALUATIONS, rows);
        record(actor, "evaluations", file, "seasonId", seasonId, "categoryId", categoryId, "from", firstDay, "to", lastDay);
        return file;
    }

    // ---------- piezas comunes ----------

    private Optional<ExportStore.Period> checkSeason(UUID seasonId, LocalDate from, LocalDate to, List<FieldError> errors) {
        errors.addAll(ExportRules.windowErrors(seasonId != null, from, to));
        if (seasonId == null) {
            return Optional.empty();
        }
        Optional<ExportStore.Period> period = store.seasonPeriod(seasonId);
        if (period.isEmpty()) {
            errors.add(new FieldError("seasonId", "La temporada no existe."));
        }
        return period;
    }

    private void checkCategory(UUID categoryId, List<FieldError> errors) {
        if (categoryId != null && !store.categoryExists(categoryId)) {
            errors.add(new FieldError("categoryId", "La categoría no existe."));
        }
    }

    private ExportRules.Window window(Optional<ExportStore.Period> season, LocalDate from, LocalDate to) {
        return ExportRules.window(season.map(ExportStore.Period::start).orElse(null),
                season.map(ExportStore.Period::end).orElse(null), from, to, settings.zone());
    }

    /** Pide una fila de más para saber si se pasó del tope y rechaza en vez de entregar un archivo recortado sin aviso. */
    private static List<List<Object>> capped(IntFunction<List<List<Object>>> query) {
        List<List<Object>> rows = query.apply(ExportRules.MAX_ROWS + 1);
        if (rows.size() > ExportRules.MAX_ROWS) {
            throw ValidationFailure.of("categoryId", "El resultado supera " + ExportRules.MAX_ROWS
                    + " filas: acota la exportación por categoría o por fechas.");
        }
        return rows;
    }

    private static ExportFile file(String name, List<String> header, List<List<Object>> rows) {
        StringBuilder csv = new StringBuilder(Csv.line(header));
        for (List<Object> row : rows) {
            csv.append(Csv.line(row));
        }
        return new ExportFile(name, csv.toString(), rows.size());
    }

    private void record(ReportsActor actor, String report, ExportFile file, Object... filters) {
        Object[] pairs = new Object[filters.length + 6];
        pairs[0] = "report";
        pairs[1] = report;
        pairs[2] = "format";
        pairs[3] = "csv";
        pairs[4] = "rows";
        pairs[5] = file.rows();
        System.arraycopy(filters, 0, pairs, 6, filters.length);
        audit.write(AuditRecord.builder(ReportService.EXPORTED, "report")
                .actor(actor.id(), actor.role())
                .after(Json.of(pairs))
                .origin(actor.ip(), actor.userAgent())
                .build());
    }
}
