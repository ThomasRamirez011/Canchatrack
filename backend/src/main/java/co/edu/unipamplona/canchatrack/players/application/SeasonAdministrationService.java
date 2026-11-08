package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.players.domain.CallRules;
import co.edu.unipamplona.canchatrack.players.domain.FieldError;
import co.edu.unipamplona.canchatrack.players.domain.SeasonStatus;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Temporadas deportivas (DDS secc. 7). Se crean planificadas y el coordinador las activa. El cierre y la reapertura
 * son del caso de uso CU-11 (RN-18) y no se construyen aquí. No se impone una sola temporada activa: el DAD no lo
 * exige y queda pendiente de confirmación del cliente.
 */
@Service
public class SeasonAdministrationService {

    static final String ACTION_CREATED = "SEASON_CREATED";
    static final String ACTION_ACTIVATED = "SEASON_ACTIVATED";
    private static final String ENTITY = "season";

    private final SeasonStore seasons;
    private final AuditWriter audit;

    public SeasonAdministrationService(SeasonStore seasons, AuditWriter audit) {
        this.seasons = seasons;
        this.audit = audit;
    }

    @Transactional
    public SeasonInfo create(AdminActor actor, CreateSeasonCommand command) {
        ValidationFailure.throwIfAny(CallRules.seasonViolations(command.name(), command.startAt(), command.endAt()));
        SeasonInfo season;
        try {
            season = seasons.insert(command.name().trim(), command.startAt(), command.endAt(), actor.id());
        } catch (DuplicateSeasonNameException e) {
            throw ValidationFailure.of(List.of(new FieldError("name", "Ya existe una temporada con ese nombre.")));
        }
        audit.write(AuditRecord.builder(ACTION_CREATED, ENTITY)
                .actor(actor.id(), AdminActor.ROLE)
                .entity(season.id())
                .after(summary(season))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return season;
    }

    @Transactional(readOnly = true)
    public List<SeasonInfo> list() {
        return seasons.findAll();
    }

    @Transactional
    public SeasonInfo activate(AdminActor actor, UUID seasonId) {
        SeasonInfo current = seasons.findByIdForUpdate(seasonId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La temporada no existe."));
        if (current.status() != SeasonStatus.PLANNED || !seasons.activate(seasonId)) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "Solo una temporada planificada puede activarse.");
        }
        SeasonInfo activated = seasons.findById(seasonId).orElseThrow();
        audit.write(AuditRecord.builder(ACTION_ACTIVATED, ENTITY)
                .actor(actor.id(), AdminActor.ROLE)
                .entity(seasonId)
                .before(AuditJson.of("status", current.status()))
                .after(AuditJson.of("status", activated.status()))
                .origin(actor.ip(), actor.userAgent())
                .build());
        return activated;
    }

    private static String summary(SeasonInfo season) {
        return AuditJson.of("name", season.name(), "startAt", season.startAt(), "endAt", season.endAt(),
                "status", season.status());
    }
}
