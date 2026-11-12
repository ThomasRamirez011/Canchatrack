package co.edu.unipamplona.canchatrack.reports.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.documents.api.FileArchive;
import co.edu.unipamplona.canchatrack.reports.domain.FieldError;
import co.edu.unipamplona.canchatrack.reports.domain.ReportRules;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Informe de temporada por jugador (CU-10, RF-049): PDF con asistencia, evaluaciones publicadas y participación, con
 * consecutivo único, código de verificación y huella, versionado sin sobrescribir. Regenerar crea una versión nueva que
 * apunta a la anterior y exige motivo; las anteriores se conservan. Los datos se congelan en {@code snapshot}.
 */
@Service
public class SeasonReportService {

    public static final String GENERATED = "SEASON_REPORT_GENERATED";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SeasonReportStore store;
    private final SeasonReportRenderer renderer;
    private final FileArchive archive;
    private final AuditWriter audit;
    private final Clock clock;
    private final ReportSettings settings;

    public SeasonReportService(SeasonReportStore store, SeasonReportRenderer renderer, FileArchive archive, AuditWriter audit,
                               Clock clock, ReportSettings settings) {
        this.store = store;
        this.renderer = renderer;
        this.archive = archive;
        this.audit = audit;
        this.clock = clock;
        this.settings = settings;
    }

    /**
     * Genera la primera versión del informe o, si ya existe, una nueva (con {@code reason} obligatorio). Solo para
     * temporadas iniciadas y jugadores que ya tienen trayectoria; el periodo cubre hasta hoy si la temporada no terminó.
     */
    @Transactional
    public SeasonReport generate(ReportsActor actor, UUID playerId, UUID seasonId, String reason) {
        if (seasonId == null) {
            throw ValidationFailure.of("seasonId", "La temporada es obligatoria.");
        }
        SeasonReportFacts.Player player = store.findPlayer(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        SeasonReportFacts.Season season = store.findSeason(seasonId)
                .orElseThrow(() -> ValidationFailure.of("seasonId", "La temporada no existe."));

        store.lockForGeneration(playerId, seasonId);
        Optional<SeasonReport> previous = store.latest(playerId, seasonId);

        List<FieldError> errors = new ArrayList<>();
        if ("PLANNED".equals(season.status())) {
            errors.add(new FieldError("seasonId", "La temporada aún no ha iniciado; no hay nada que informar."));
        }
        String cleanReason = null;
        if (previous.isPresent()) {
            String problem = ReportRules.requiredTextProblem(reason, ReportRules.MAX_REASON);
            if (problem != null) {
                errors.add(new FieldError("reason", "Al regenerar el informe el motivo " + problem.toLowerCase(java.util.Locale.ROOT)));
            } else {
                cleanReason = reason.strip();
            }
        }
        ValidationFailure.throwIfAny(errors);
        if (!ReportRules.isReportablePlayerState(player.status())) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT,
                    "El jugador aún no tiene trayectoria que informar (estado actual: " + player.status() + ").");
        }
        if (player.categoryName() == null) {
            throw new DomainException(ErrorCode.PLAYER_STATE_CONFLICT, "El jugador no tiene una categoría asignada.");
        }

        Instant now = clock.instant();
        Instant to = ReportRules.periodEnd(season.endAt(), now);
        SeasonReportFacts facts = SeasonReportFacts.of(season, player,
                new SeasonReportFacts.Period(season.startAt(), to, now.isBefore(season.endAt())),
                store.attendance(playerId, season.startAt(), to),
                store.evaluations(playerId, season.startAt(), to),
                store.matches(playerId, season.startAt(), to));

        long consecutive = store.nextConsecutive();
        int version = previous.map(p -> p.version() + 1).orElse(1);
        String code = ReportRules.verificationCode(RANDOM::nextInt);
        String number = ReportRules.reportNumber(consecutive);

        byte[] pdf;
        try {
            pdf = renderer.render(new SeasonReportContent(ReportRules.TEMPLATE_VERSION, number, version, code, now,
                    settings.zone(), cleanReason, facts));
        } catch (RuntimeException e) {
            throw new DomainException(ErrorCode.REPORT_GENERATION_FAILED, "No fue posible producir el PDF; puede reintentarse.");
        }
        FileArchive.ArchivedFile file = archive.store(actor.id(), "informe-temporada-" + number + "-v" + version + ".pdf",
                "application/pdf", pdf);

        SeasonReport report = store.insert(new SeasonReportStore.NewReport(playerId, seasonId, consecutive, version,
                previous.map(SeasonReport::id).orElse(null), cleanReason, code, ReportRules.TEMPLATE_VERSION,
                snapshotJson(facts), file.id(), file.sha256(), actor.id()));

        audit.write(AuditRecord.builder(GENERATED, "season_report")
                .actor(actor.id(), actor.role())
                .entity(report.id())
                .after(Json.of("reportNumber", number, "version", version, "playerId", playerId, "seasonId", seasonId,
                        "fileId", file.id(), "fileHash", file.sha256(), "partial", facts.period().partial()))
                .reason(cleanReason)
                .origin(actor.ip(), actor.userAgent())
                .build());
        return report;
    }

    @Transactional(readOnly = true)
    public List<SeasonReport> list(UUID playerId, UUID seasonId) {
        store.findPlayer(playerId).orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        return store.list(playerId, seasonId);
    }

    @Transactional(readOnly = true)
    public SeasonReport get(UUID reportId) {
        return store.find(reportId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El informe no existe."));
    }

    /**
     * Comprueba un código de verificación. Devuelve el informe y si es la versión vigente; un código mal formado o
     * desconocido responde igual: 404.
     */
    @Transactional(readOnly = true)
    public Verification verify(String rawCode) {
        String code = ReportRules.normalizeVerificationCode(rawCode);
        SeasonReport report = code == null ? null : store.findByVerificationCode(code).orElse(null);
        if (report == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El código de verificación no corresponde a ningún informe.");
        }
        boolean current = store.latest(report.playerId(), report.seasonId()).map(l -> l.id().equals(report.id())).orElse(false);
        return new Verification(report, current);
    }

    /** Resultado de verificar un código; {@code current} es falso si existe una versión más reciente. */
    public record Verification(SeasonReport report, boolean current) {
    }

    private static String snapshotJson(SeasonReportFacts facts) {
        try {
            return MAPPER.writeValueAsString(facts.toSnapshot());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
