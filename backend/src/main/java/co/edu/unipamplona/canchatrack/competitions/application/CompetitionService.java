package co.edu.unipamplona.canchatrack.competitions.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.competitions.application.CompetitionCommands.CreateTournament;
import co.edu.unipamplona.canchatrack.competitions.application.CompetitionCommands.RecordResult;
import co.edu.unipamplona.canchatrack.competitions.application.CompetitionCommands.ScheduleMatch;
import co.edu.unipamplona.canchatrack.competitions.application.Views.AuthorizationResult;
import co.edu.unipamplona.canchatrack.competitions.application.Views.CallupRow;
import co.edu.unipamplona.canchatrack.competitions.application.Views.CallupView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.MatchDetail;
import co.edu.unipamplona.canchatrack.competitions.application.Views.MatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.PlayerMatchView;
import co.edu.unipamplona.canchatrack.competitions.application.Views.TournamentView;
import co.edu.unipamplona.canchatrack.competitions.domain.CompetitionRules;
import co.edu.unipamplona.canchatrack.competitions.domain.Eligibility;
import co.edu.unipamplona.canchatrack.competitions.domain.FieldError;
import co.edu.unipamplona.canchatrack.competitions.domain.PlayerResultLine;
import co.edu.unipamplona.canchatrack.documents.api.ParticipationClearance;
import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.identity.api.GuardianDirectory;
import co.edu.unipamplona.canchatrack.identity.api.PlayerAccessPolicy;
import co.edu.unipamplona.canchatrack.identity.api.PlayerData;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.payments.api.AccountStanding;
import co.edu.unipamplona.canchatrack.players.api.CategoryRoster;
import co.edu.unipamplona.canchatrack.players.api.RosterPlayer;
import co.edu.unipamplona.canchatrack.scheduling.api.MatchEventScheduling;
import co.edu.unipamplona.canchatrack.scheduling.api.MatchEventSlot;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Torneos, partidos y convocatorias (CU-09). El coordinador registra torneos y programa partidos (RN-13); el entrenador
 * propone la convocatoria y el sistema evalúa a cada jugador (activo, certificado médico, autorización y mora: RN-06,
 * RN-07); quien no cumple queda marcado con la causa y no se convoca. El coordinador confirma, se avisa a los acudientes
 * (sin datos de salud) y cada acudiente autoriza el partido. Tras el partido se registra el resultado.
 *
 * <p>Toda operación sobre un partido bloquea su fila: convocar, confirmar y registrar el resultado se serializan.
 */
@Service
public class CompetitionService {

    static final String ACTION_TOURNAMENT = "TOURNAMENT_CREATED";
    static final String ACTION_MATCH = "MATCH_SCHEDULED";
    static final String ACTION_PROPOSED = "CALLUP_PROPOSED";
    static final String ACTION_REMOVED = "CALLUP_REMOVED";
    static final String ACTION_CONFIRMED = "CALLUP_CONFIRMED";
    static final String ACTION_AUTHORIZED = "MATCH_AUTHORIZED";
    static final String ACTION_CANCELLED = "MATCH_CANCELLED";
    static final String ACTION_RESULT = "MATCH_RESULT_RECORDED";
    static final String ACTION_RESULT_CORRECTED = "MATCH_RESULT_CORRECTED";
    static final String ENTITY_MATCH = "match";
    static final String AUTHORIZATION_TEXT_VERSION = "match-authorization-v1";
    static final int MAX_LIST = 200;

    private static final String CONFIRMED = "CONFIRMED";
    private static final String PROPOSED = "PROPOSED";
    private static final String NOT_ELIGIBLE = "NOT_ELIGIBLE";
    private static final String REMOVED = "REMOVED";

    private final CompetitionStore store;
    private final MatchEventScheduling events;
    private final CategoryRoster roster;
    private final ParticipationClearance clearance;
    private final AccountStanding standing;
    private final GuardianContacts guardianContacts;
    private final GuardianDirectory guardianDirectory;
    private final PlayerAccessPolicy access;
    private final NotificationQueue notifications;
    private final AuditWriter audit;
    private final CompetitionSettings settings;

    public CompetitionService(CompetitionStore store, MatchEventScheduling events, CategoryRoster roster,
                              ParticipationClearance clearance, AccountStanding standing, GuardianContacts guardianContacts,
                              GuardianDirectory guardianDirectory, PlayerAccessPolicy access, NotificationQueue notifications,
                              AuditWriter audit, CompetitionSettings settings) {
        this.store = store;
        this.events = events;
        this.roster = roster;
        this.clearance = clearance;
        this.standing = standing;
        this.guardianContacts = guardianContacts;
        this.guardianDirectory = guardianDirectory;
        this.access = access;
        this.notifications = notifications;
        this.audit = audit;
        this.settings = settings;
    }

    // ---------- torneos ----------

    @Transactional
    public TournamentView createTournament(CompetitionsActor actor, CreateTournament command) {
        List<FieldError> errors = new ArrayList<>(CompetitionRules.tournamentViolations(command.name(), command.startDate(),
                command.endDate(), command.rules()));
        if (command.seasonId() == null) {
            errors.add(new FieldError("seasonId", "La temporada es obligatoria."));
        }
        ValidationFailure.throwIfAny(errors);
        UUID id;
        try {
            id = store.insertTournament(command.seasonId(), command.name().trim(), command.startDate(), command.endDate(),
                    command.categoryId(), command.venueId(), blankToNull(command.rules()), actor.id());
        } catch (DuplicateTournamentException e) {
            throw ValidationFailure.of("name", "Ya existe un torneo con ese nombre en la temporada.");
        } catch (ReferenceNotFoundException e) {
            throw ValidationFailure.of("seasonId", "La temporada, la categoría o el escenario indicados no existen.");
        }
        TournamentView tournament = store.findTournament(id).orElseThrow();
        audit.write(auditOf(ACTION_TOURNAMENT, "tournament", actor, id)
                .after(Json.of("name", tournament.name(), "seasonId", tournament.seasonId(), "categoryId", tournament.categoryId(),
                        "venueId", tournament.venueId(), "startDate", tournament.startDate(), "endDate", tournament.endDate()))
                .build());
        return tournament;
    }

    @Transactional(readOnly = true)
    public List<TournamentView> tournaments(UUID seasonId) {
        return store.findTournaments(seasonId);
    }

    // ---------- partidos ----------

    @Transactional
    public MatchView scheduleMatch(CompetitionsActor actor, ScheduleMatch command) {
        List<FieldError> errors = new ArrayList<>(CompetitionRules.opponentViolations(command.opponent()));
        TournamentView tournament = null;
        if (command.tournamentId() != null) {
            tournament = store.findTournament(command.tournamentId()).orElse(null);
            if (tournament == null) {
                errors.add(new FieldError("tournamentId", "El torneo no existe."));
            } else {
                LocalDate day = command.startAt() == null ? null : command.startAt().atZone(settings.zone()).toLocalDate();
                errors.addAll(CompetitionRules.tournamentFitViolations(command.categoryId(), tournament.categoryId(), day,
                        tournament.startDate(), tournament.endDate()));
            }
        }
        ValidationFailure.throwIfAny(errors);

        String type = tournament == null ? "FRIENDLY_MATCH" : "TOURNAMENT_MATCH";
        UUID eventId = events.schedule(new MatchEventSlot(type, command.categoryId(), command.venueId(), command.headCoachId(),
                command.startAt(), command.endAt(), command.meetingAt(), command.meetingPoint(), command.instructions()),
                actor.id(), actor.ip(), actor.userAgent());
        UUID id = store.insertMatch(eventId, command.tournamentId(), command.opponent().trim(), actor.id());
        MatchView match = store.findMatch(id).orElseThrow();
        audit.write(auditOf(ACTION_MATCH, ENTITY_MATCH, actor, id)
                .after(Json.of("eventId", eventId, "type", type, "opponent", match.opponent(), "tournamentId", match.tournamentId(),
                        "categoryId", match.categoryId(), "startAt", match.startAt()))
                .build());
        return match;
    }

    /** Cancela el partido (el evento queda auditado por agenda) y avisa a los acudientes de los convocados confirmados. */
    @Transactional
    public MatchView cancelMatch(CompetitionsActor actor, UUID matchId, String reason) {
        MatchView match = store.lockMatch(matchId).orElseThrow(CompetitionService::matchNotFound);
        events.cancel(match.eventId(), reason, actor.id(), actor.ip(), actor.userAgent());
        List<CallupRow> confirmed = store.callupsOf(matchId).stream().filter(r -> CONFIRMED.equals(r.status())).toList();
        notifyGuardians(confirmed.stream().map(CallupRow::playerId).toList(), "MATCH_CANCELLED", "Partido cancelado", match, reason.trim());
        audit.write(auditOf(ACTION_CANCELLED, ENTITY_MATCH, actor, matchId)
                .before(Json.of("status", match.status()))
                .after(Json.of("status", "CANCELLED", "notifiedPlayers", confirmed.size()))
                .reason(reason.trim())
                .build());
        return store.findMatch(matchId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<MatchView> matches(CompetitionsActor actor, UUID categoryId, UUID tournamentId, Instant from, Instant to) {
        UUID coachFilter = actor.seesAllMatches() ? null : actor.id();
        return store.findMatches(categoryId, tournamentId, from, to, coachFilter, MAX_LIST);
    }

    /** Coordinador y comité ven cualquier partido; el entrenador, solo los suyos (sin acceso responde 404). */
    @Transactional(readOnly = true)
    public MatchDetail match(CompetitionsActor actor, UUID matchId) {
        MatchView match = store.findMatch(matchId).orElseThrow(CompetitionService::matchNotFound);
        if (!actor.seesAllMatches() && !actor.id().equals(match.headCoachId())) {
            throw matchNotFound();
        }
        return new MatchDetail(match, callupViews(matchId, null));
    }

    // ---------- convocatoria ----------

    /**
     * El entrenador del partido (o el coordinador) propone jugadores. Cada uno se evalúa; el que no cumple queda
     * {@code NOT_ELIGIBLE} con su causa. Proponer de nuevo re-evalúa, salvo a quienes ya están confirmados.
     */
    @Transactional
    public List<CallupView> propose(CompetitionsActor actor, UUID matchId, List<UUID> playerIds) {
        MatchView match = store.lockMatch(matchId).orElseThrow(CompetitionService::matchNotFound);
        requireManager(actor, match);
        ValidationFailure.throwIfAny(CompetitionRules.squadViolations(playerIds));
        requireOpenForCallup(match);

        Map<UUID, RosterPlayer> people = roster.describe(playerIds);
        List<FieldError> missing = new ArrayList<>();
        for (int i = 0; i < playerIds.size(); i++) {
            if (!people.containsKey(playerIds.get(i))) {
                missing.add(new FieldError("playerIds[" + i + "]", "El jugador no existe."));
            }
        }
        ValidationFailure.throwIfAny(missing);

        Map<UUID, Eligibility> evaluation = evaluate(match, playerIds);
        Map<UUID, CallupRow> existing = store.callupsOf(matchId, playerIds);
        int proposed = 0;
        int notEligible = 0;
        for (UUID playerId : playerIds) {
            CallupRow row = existing.get(playerId);
            if (row != null && CONFIRMED.equals(row.status())) {
                continue;
            }
            Eligibility eligibility = evaluation.get(playerId);
            String status = eligibility.eligible() ? PROPOSED : NOT_ELIGIBLE;
            store.upsertProposal(matchId, playerId, status, eligibility.checks(), eligibility.primaryCause(), actor.id());
            if (eligibility.eligible()) {
                proposed++;
            } else {
                notEligible++;
            }
        }
        audit.write(auditOf(ACTION_PROPOSED, ENTITY_MATCH, actor, matchId)
                .after(Json.of("requested", playerIds.size(), "proposed", proposed, "notEligible", notEligible))
                .build());
        return callupViews(matchId, new HashSet<>(playerIds));
    }

    /** Quita a un jugador de la convocatoria. Si ya estaba confirmado, se avisa a sus acudientes. Es idempotente. */
    @Transactional
    public void remove(CompetitionsActor actor, UUID matchId, UUID playerId) {
        MatchView match = store.lockMatch(matchId).orElseThrow(CompetitionService::matchNotFound);
        requireManager(actor, match);
        requireOpenForCallup(match);
        CallupRow row = store.callupsOf(matchId, List.of(playerId)).get(playerId);
        if (row == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no está en la convocatoria.");
        }
        if (REMOVED.equals(row.status())) {
            return;
        }
        store.markRemoved(matchId, playerId);
        if (CONFIRMED.equals(row.status())) {
            notifyGuardians(List.of(playerId), "MATCH_CALLUP_REMOVED", "Cambio en la convocatoria", match, null);
        }
        audit.write(auditOf(ACTION_REMOVED, ENTITY_MATCH, actor, matchId)
                .before(Json.of("playerId", playerId, "status", row.status()))
                .after(Json.of("playerId", playerId, "status", REMOVED))
                .build());
    }

    /**
     * El coordinador confirma a los jugadores propuestos. Se vuelve a evaluar cada uno (un certificado pudo vencer desde la
     * propuesta): si alguno ya no cumple, nada se confirma y la respuesta 409 indica quién y por qué. Los acudientes
     * reciben el aviso sin datos de salud.
     */
    @Transactional
    public List<CallupView> confirm(CompetitionsActor actor, UUID matchId, List<UUID> playerIds) {
        if (!actor.isCoordinator()) {
            throw new DomainException(ErrorCode.AUTH_FORBIDDEN, null);
        }
        MatchView match = store.lockMatch(matchId).orElseThrow(CompetitionService::matchNotFound);
        ValidationFailure.throwIfAny(CompetitionRules.squadViolations(playerIds));
        requireOpenForCallup(match);

        Map<UUID, CallupRow> existing = store.callupsOf(matchId, playerIds);
        List<UUID> notProposed = new ArrayList<>();
        List<UUID> toConfirm = new ArrayList<>();
        for (UUID playerId : playerIds) {
            CallupRow row = existing.get(playerId);
            if (row != null && CONFIRMED.equals(row.status())) {
                continue;
            }
            if (row == null || !PROPOSED.equals(row.status())) {
                notProposed.add(playerId);
            } else {
                toConfirm.add(playerId);
            }
        }
        if (!notProposed.isEmpty()) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT,
                    "Solo se confirman jugadores propuestos y elegibles.",
                    Map.of("players", notProposed.stream().map(UUID::toString).toList()));
        }

        Map<UUID, Eligibility> evaluation = evaluate(match, toConfirm);
        List<Map<String, Object>> failing = new ArrayList<>();
        for (UUID playerId : toConfirm) {
            Eligibility eligibility = evaluation.get(playerId);
            if (!eligibility.eligible()) {
                failing.add(Map.of("playerId", playerId.toString(), "causes", eligibility.causes()));
            }
        }
        if (!failing.isEmpty()) {
            throw new DomainException(ErrorCode.CALLUP_NOT_ELIGIBLE, null, Map.of("players", failing));
        }
        for (UUID playerId : toConfirm) {
            store.confirm(matchId, playerId, evaluation.get(playerId).checks(), actor.id());
            notifyGuardians(List.of(playerId), "MATCH_CALLUP", "Convocatoria a partido", match, null);
        }
        audit.write(auditOf(ACTION_CONFIRMED, ENTITY_MATCH, actor, matchId)
                .after(Json.of("confirmed", toConfirm.size()))
                .build());
        return callupViews(matchId, new HashSet<>(playerIds));
    }

    /**
     * El acudiente autoriza la participación o el desplazamiento de su hijo en un partido para el que fue convocado y
     * confirmado (RF-034). Repetirlo no duplica la autorización.
     */
    @Transactional
    public AuthorizationResult authorize(CompetitionsActor actor, UUID matchId, UUID playerId) {
        if (!actor.roles().contains("GUARDIAN") || !access.canAccess(actor.id(), actor.roles(), playerId)) {
            throw matchNotFound();
        }
        UUID guardianId = guardianDirectory.guardianIdOf(actor.id()).orElseThrow(CompetitionService::matchNotFound);
        MatchView match = store.lockMatch(matchId).orElseThrow(CompetitionService::matchNotFound);
        CallupRow row = store.callupsOf(matchId, List.of(playerId)).get(playerId);
        if (row == null || !CONFIRMED.equals(row.status())) {
            throw matchNotFound();
        }
        if (!CONFIRMED.equals(match.status()) || !CompetitionRules.callupWindowOpen(match.startAt(), store.now())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "El partido ya no admite autorizaciones.");
        }
        boolean recorded = clearance.authorizeMatch(guardianId, playerId, matchId, AUTHORIZATION_TEXT_VERSION);
        if (recorded) {
            audit.write(auditOf(ACTION_AUTHORIZED, ENTITY_MATCH, actor, matchId)
                    .after(Json.of("playerId", playerId))
                    .build());
        }
        return new AuthorizationResult(matchId, playerId, recorded);
    }

    // ---------- resultado ----------

    /**
     * Registra el marcador, los minutos y goles de los convocados y las observaciones (RF-036) una vez comenzado el
     * partido. Un resultado ya registrado solo se corrige con motivo, y la corrección queda auditada con el valor anterior.
     */
    @Transactional
    public MatchDetail recordResult(CompetitionsActor actor, UUID matchId, RecordResult command) {
        MatchView match = store.lockMatch(matchId).orElseThrow(CompetitionService::matchNotFound);
        requireManager(actor, match);
        if ("CANCELLED".equals(match.status())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "Un partido cancelado no admite resultado.");
        }
        if (!CompetitionRules.resultWindowOpen(match.startAt(), store.now())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "El partido aún no ha comenzado.");
        }
        boolean correction = match.scoreFor() != null;
        List<PlayerResultLine> lines = command.players() == null ? List.of() : command.players();
        List<FieldError> errors = new ArrayList<>(CompetitionRules.resultViolations(command.scoreFor(), command.scoreAgainst(),
                command.resultNotes(), lines, correction, command.reason()));
        List<UUID> lineIds = lines.stream().map(PlayerResultLine::playerId).filter(id -> id != null).distinct().toList();
        Map<UUID, CallupRow> rows = lineIds.isEmpty() ? Map.of() : store.callupsOf(matchId, lineIds);
        for (int i = 0; i < lines.size(); i++) {
            PlayerResultLine line = lines.get(i);
            if (line != null && line.playerId() != null) {
                CallupRow row = rows.get(line.playerId());
                if (row == null || !CONFIRMED.equals(row.status())) {
                    errors.add(new FieldError("players[" + i + "].playerId", "El jugador no está convocado en este partido."));
                }
            }
        }
        ValidationFailure.throwIfAny(errors);

        store.saveResult(matchId, command.scoreFor(), command.scoreAgainst(), blankToNull(command.resultNotes()), actor.id());
        for (PlayerResultLine line : lines) {
            store.saveParticipation(matchId, new PlayerResultLine(line.playerId(), line.minutesPlayed(), line.goals(),
                    blankToNull(line.observations())));
        }
        events.complete(match.eventId());
        AuditRecord.Builder record = auditOf(correction ? ACTION_RESULT_CORRECTED : ACTION_RESULT, ENTITY_MATCH, actor, matchId)
                .after(Json.of("scoreFor", command.scoreFor(), "scoreAgainst", command.scoreAgainst(), "players", lines.size()));
        if (correction) {
            record.before(Json.of("scoreFor", match.scoreFor(), "scoreAgainst", match.scoreAgainst()))
                    .reason(command.reason().trim());
        }
        audit.write(record.build());
        return new MatchDetail(store.findMatch(matchId).orElseThrow(), callupViews(matchId, null));
    }

    // ---------- historial ----------

    /** Partidos con convocatoria confirmada del jugador (RF-037); acudiente solo de sus hijos, personal según su alcance. */
    @Transactional(readOnly = true)
    public List<PlayerMatchView> history(CompetitionsActor actor, UUID playerId) {
        if (!access.canView(actor.id(), actor.roles(), playerId, PlayerData.MATCHES)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe.");
        }
        Set<UUID> authorized = clearance.authorizedMatchesOf(playerId);
        return store.historyOf(playerId).stream()
                .map(v -> new PlayerMatchView(v.matchId(), v.opponent(), v.tournamentName(), v.startAt(), v.matchStatus(),
                        authorized.contains(v.matchId()), v.minutesPlayed(), v.goals(), v.scoreFor(), v.scoreAgainst()))
                .toList();
    }

    // ---------- apoyo ----------

    /** Evalúa los requisitos de cada jugador para ese partido con las fachadas de los módulos dueños de cada dato. */
    private Map<UUID, Eligibility> evaluate(MatchView match, Collection<UUID> playerIds) {
        Map<UUID, Eligibility> result = new LinkedHashMap<>();
        if (playerIds.isEmpty()) {
            return result;
        }
        Set<UUID> inCategory = new HashSet<>(roster.activePlayerIds(match.categoryId()));
        LocalDate matchDay = match.startAt().atZone(settings.zone()).toLocalDate();
        LocalDate today = store.now().atZone(settings.zone()).toLocalDate();
        Set<UUID> withoutCertificate = clearance.withoutValidMedicalCertificate(playerIds, matchDay);
        Set<UUID> withoutAuthorization = clearance.withoutValidParticipationAuthorization(playerIds, match.startAt());
        Set<UUID> inArrears = standing.inArrears(playerIds, today);
        for (UUID playerId : playerIds) {
            result.put(playerId, new Eligibility(inCategory.contains(playerId), !withoutCertificate.contains(playerId),
                    !withoutAuthorization.contains(playerId), !inArrears.contains(playerId)));
        }
        return result;
    }

    /** Convocados del partido (sin los retirados) con nombre y estado de autorización; {@code only} filtra por jugador. */
    private List<CallupView> callupViews(UUID matchId, Set<UUID> only) {
        List<CallupRow> rows = store.callupsOf(matchId).stream()
                .filter(r -> only == null || only.contains(r.playerId())).toList();
        Map<UUID, RosterPlayer> people = roster.describe(rows.stream().map(CallupRow::playerId).toList());
        Set<UUID> authorized = clearance.matchAuthorizedPlayers(matchId);
        return rows.stream().map(r -> {
            RosterPlayer p = people.get(r.playerId());
            return new CallupView(r.playerId(), p == null ? null : p.code(), p == null ? null : p.firstName(),
                    p == null ? null : p.lastName(), r.status(), r.ineligibilityCode(), r.eligibility(), r.proposedAt(),
                    r.confirmedAt(), CONFIRMED.equals(r.status()) && authorized.contains(r.playerId()), r.minutesPlayed(),
                    r.goals(), r.observations());
        }).toList();
    }

    /** Avisa a los acudientes de cada jugador. El contenido es solo de plantilla y nunca incluye datos de salud (RN-17). */
    private void notifyGuardians(Collection<UUID> playerIds, String type, String subject, MatchView match, String reason) {
        if (playerIds.isEmpty()) {
            return;
        }
        Map<UUID, RosterPlayer> people = roster.describe(playerIds);
        String stamp = store.now().toString();
        for (UUID playerId : playerIds) {
            Set<UUID> recipients = guardianContacts.guardianUserIdsOf(List.of(playerId));
            if (recipients.isEmpty()) {
                continue;
            }
            RosterPlayer player = people.get(playerId);
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("matchId", match.id().toString());
            payload.put("playerId", playerId.toString());
            if (player != null) {
                payload.put("playerName", player.firstName() + " " + player.lastName());
            }
            payload.put("opponent", match.opponent());
            payload.put("startAt", match.startAt().toString());
            if (match.meetingAt() != null) {
                payload.put("meetingAt", match.meetingAt().toString());
            }
            if (match.meetingPoint() != null) {
                payload.put("meetingPoint", match.meetingPoint());
            }
            if (reason != null) {
                payload.put("reason", reason);
            }
            notifications.enqueue(type, recipients, subject, payload, ENTITY_MATCH, match.id(), playerId + ":" + stamp);
        }
    }

    /** Coordinador o entrenador responsable del partido; cualquier otro recibe 404, como si no existiera. */
    private static void requireManager(CompetitionsActor actor, MatchView match) {
        if (!actor.isCoordinator() && !actor.id().equals(match.headCoachId())) {
            throw matchNotFound();
        }
    }

    private void requireOpenForCallup(MatchView match) {
        if (!CONFIRMED.equals(match.status())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "El partido no admite cambios en la convocatoria.");
        }
        if (!CompetitionRules.callupWindowOpen(match.startAt(), store.now())) {
            throw new DomainException(ErrorCode.RESOURCE_STATE_CONFLICT, "El partido ya comenzó.");
        }
    }

    private static AuditRecord.Builder auditOf(String action, String entityType, CompetitionsActor actor, UUID entityId) {
        return AuditRecord.builder(action, entityType)
                .actor(actor.id(), actor.auditRole())
                .entity(entityId)
                .origin(actor.ip(), actor.userAgent());
    }

    private static DomainException matchNotFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El partido no existe.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
