package co.edu.unipamplona.canchatrack.reports.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.players.api.SeasonClosureGateway;
import co.edu.unipamplona.canchatrack.reports.domain.FieldError;
import co.edu.unipamplona.canchatrack.reports.domain.ReportRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Cierre y reapertura del expediente por temporada (CU-11, RF-050, RN-18). El cierre exige el informe vigente, sin
 * cambios posteriores en el expediente deportivo ("informe generado y soportes verificados", DAD E11). La reapertura es
 * solo de la coordinación, con motivo, detalle y confirmación reforzada, y queda en auditoría; nada se borra.
 */
@Service
public class SeasonClosureService {

    public static final String CLOSED = "PLAYER_SEASON_CLOSED";
    public static final String REOPENED = "PLAYER_SEASON_REOPENED";

    private final SeasonReportStore store;
    private final SeasonClosureGateway players;
    private final AuditWriter audit;

    public SeasonClosureService(SeasonReportStore store, SeasonClosureGateway players, AuditWriter audit) {
        this.store = store;
        this.players = players;
        this.audit = audit;
    }

    @Transactional
    public SeasonClosure close(ReportsActor actor, UUID playerId, UUID seasonId, UUID reportId) {
        List<FieldError> errors = new ArrayList<>();
        if (seasonId == null) {
            errors.add(new FieldError("seasonId", "La temporada es obligatoria."));
        }
        if (reportId == null) {
            errors.add(new FieldError("reportId", "El informe es obligatorio."));
        }
        ValidationFailure.throwIfAny(errors);
        store.findPlayer(playerId).orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        store.findSeason(seasonId).orElseThrow(() -> ValidationFailure.of("seasonId", "La temporada no existe."));

        SeasonReport report = store.find(reportId)
                .filter(r -> r.playerId().equals(playerId) && r.seasonId().equals(seasonId))
                .orElseThrow(() -> ValidationFailure.of("reportId", "El informe no corresponde al jugador y la temporada indicados."));
        SeasonReport latest = store.latest(playerId, seasonId).orElse(report);
        if (!latest.id().equals(report.id())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "Existe una versión más reciente del informe (" + ReportRules.reportNumber(latest.consecutive())
                            + ", versión " + latest.version() + "); revísela y use esa.");
        }
        if (store.changedSince(playerId, report.generatedAt())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "El expediente deportivo cambió después de generar el informe; genere una nueva versión antes de cerrar.");
        }

        players.closeSeason(playerId, actor.id());
        SeasonClosure closure;
        try {
            closure = store.insertClosure(playerId, seasonId, reportId, actor.id());
        } catch (DuplicateKeyException e) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "La temporada ya está cerrada para este jugador.");
        }
        audit.write(AuditRecord.builder(CLOSED, "player")
                .actor(actor.id(), actor.role())
                .entity(playerId)
                .before(Json.of("status", "ACTIVE"))
                .after(Json.of("status", "SEASON_CLOSED", "seasonId", seasonId, "reportId", reportId,
                        "reportNumber", ReportRules.reportNumber(report.consecutive()), "reportVersion", report.version(),
                        "closureId", closure.id()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return closure;
    }

    @Transactional
    public SeasonClosure reopen(ReportsActor actor, UUID playerId, String reason, String detail, String confirmation) {
        SeasonReportFacts.Player player = store.findPlayer(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        List<FieldError> errors = new ArrayList<>();
        String reasonProblem = ReportRules.requiredTextProblem(reason, ReportRules.MAX_REASON);
        if (reasonProblem != null) {
            errors.add(new FieldError("reason", "El motivo " + reasonProblem.toLowerCase(java.util.Locale.ROOT)));
        }
        String detailProblem = ReportRules.requiredTextProblem(detail, ReportRules.MAX_DETAIL);
        if (detailProblem != null) {
            errors.add(new FieldError("detail", "El detalle " + detailProblem.toLowerCase(java.util.Locale.ROOT)));
        }
        if (!ReportRules.confirmationMatches(player.code(), confirmation)) {
            errors.add(new FieldError("confirmation", "Para confirmar escriba el código del jugador (" + player.code() + ")."));
        }
        ValidationFailure.throwIfAny(errors);

        SeasonClosure closure = store.latestOpenClosure(playerId).orElseThrow(() -> new DomainException(
                ErrorCode.PLAYER_STATE_CONFLICT, "El jugador no tiene una temporada cerrada que reabrir."));
        players.reopenSeason(playerId, actor.id(), reason.strip());
        SeasonClosure reopened = store.markReopened(closure.id(), actor.id(), reason.strip(), detail.strip());
        audit.write(AuditRecord.builder(REOPENED, "player")
                .actor(actor.id(), actor.role())
                .entity(playerId)
                .before(Json.of("status", "SEASON_CLOSED", "seasonId", closure.seasonId(), "closureId", closure.id()))
                .after(Json.of("status", "ACTIVE", "detail", detail.strip()))
                .reason(reason.strip())
                .origin(actor.ip(), actor.userAgent())
                .build());
        return reopened;
    }

    @Transactional(readOnly = true)
    public SeasonClosure latest(UUID playerId) {
        store.findPlayer(playerId).orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        return store.latestClosure(playerId).orElseThrow(() -> new DomainException(
                ErrorCode.RESOURCE_NOT_FOUND, "El jugador no tiene cierres de temporada."));
    }
}
