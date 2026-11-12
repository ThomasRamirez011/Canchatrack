package co.edu.unipamplona.canchatrack.reports.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.reports.domain.FieldError;
import co.edu.unipamplona.canchatrack.reports.domain.ReportRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Reportes de cartera y de temporada (CU-10 a CU-12, RF-042, RF-049/050 en su parte de consulta). Solo lectura; la única
 * escritura es el rastro de auditoría de las exportaciones (DDS 12.2). El informe PDF versionado por jugador y el cierre
 * de temporada quedan para un incremento posterior.
 */
@Service
public class ReportService {

    public static final String EXPORTED = "REPORT_EXPORTED";

    private final ReportStore store;
    private final AuditWriter audit;
    private final Clock clock;
    private final ReportSettings settings;

    public ReportService(ReportStore store, AuditWriter audit, Clock clock, ReportSettings settings) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ReceivablesReport receivables(UUID seasonId, UUID categoryId, LocalDate asOf) {
        return build(seasonId, categoryId, asOf);
    }

    /** Igual que {@link #receivables} pero deja constancia de la exportación (datos financieros de menores y sus familias). */
    @Transactional
    public ReceivablesReport exportReceivables(ReportsActor actor, UUID seasonId, UUID categoryId, LocalDate asOf) {
        ReceivablesReport report = build(seasonId, categoryId, asOf);
        audit.write(AuditRecord.builder(EXPORTED, "report")
                .actor(actor.id(), actor.role())
                .after(Json.of("report", "receivables", "format", "csv", "seasonId", seasonId, "categoryId", categoryId,
                        "asOf", report.asOf(), "rows", report.lines().size()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return report;
    }

    @Transactional(readOnly = true)
    public SeasonSummary seasonSummary(UUID seasonId) {
        if (seasonId == null) {
            throw ValidationFailure.of("seasonId", "La temporada es obligatoria.");
        }
        return store.seasonSummary(seasonId, today(), clock.instant())
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La temporada no existe."));
    }

    private ReceivablesReport build(UUID seasonId, UUID categoryId, LocalDate asOf) {
        List<FieldError> errors = new ArrayList<>();
        if (seasonId != null && !store.seasonExists(seasonId)) {
            errors.add(new FieldError("seasonId", "La temporada no existe."));
        }
        if (categoryId != null && !store.categoryExists(categoryId)) {
            errors.add(new FieldError("categoryId", "La categoría no existe."));
        }
        ValidationFailure.throwIfAny(errors);

        LocalDate day = asOf == null ? today() : asOf;
        List<ReceivableLine> rows = store.receivables(seasonId, categoryId, day, ReportRules.MAX_RECEIVABLE_LINES + 1);
        boolean truncated = rows.size() > ReportRules.MAX_RECEIVABLE_LINES;
        List<ReceivableLine> lines = truncated ? List.copyOf(rows.subList(0, ReportRules.MAX_RECEIVABLE_LINES)) : List.copyOf(rows);
        return new ReceivablesReport(day, clock.instant(), lines, store.receivableTotals(seasonId, categoryId, day), truncated);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(settings.zone()));
    }
}
