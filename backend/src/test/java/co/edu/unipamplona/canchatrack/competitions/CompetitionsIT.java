package co.edu.unipamplona.canchatrack.competitions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Partidos y convocatorias contra PostgreSQL real (CU-09; RN-06, RN-07, RN-13): elegibilidad con causa, confirmación con
 * nueva evaluación, avisos sin datos de salud, autorización del acudiente, resultado con corrección auditada, cancelación e
 * historial. Cada prueba crea su propio club (categoría, escenario, entrenador y jugadores).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CompetitionsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final ZoneId CLUB_ZONE = ZoneId.of("America/Bogota");
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(2700);
    private static final AtomicInteger NEXT_DAY = new AtomicInteger(3);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    // ---------- utilidades ----------

    private ResultActions send(MockHttpServletRequestBuilder request, Object body, String bearer) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        return send(post(API + path), body, bearer);
    }

    private ResultActions putJson(String path, Object body, String bearer) throws Exception {
        return send(put(API + path), body, bearer);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
    }

    private ResultActions deleteJson(String path, String bearer) throws Exception {
        return send(delete(API + path), null, bearer);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private record Account(UUID id, String token) {
    }

    private String login(String email) throws Exception {
        return body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null).andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    private UUID user(String email, String roleCode) {
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Cuenta de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return id;
    }

    private Account staff(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = user(email, roleCode);
        return new Account(id, login(email));
    }

    /** Categoría, escenario, coordinador y entrenador de una prueba. */
    private record Club(Account coordinator, Account coach, UUID category, UUID venue) {
    }

    private Club club() throws Exception {
        int year = NEXT_YEAR.getAndAdd(3);
        UUID category = jdbc.queryForObject("""
                INSERT INTO categories (name, birth_year_min, birth_year_max, capacity)
                VALUES (?, ?, ?, 40) RETURNING id
                """, UUID.class, "Sub " + UUID.randomUUID(), year, year + 1);
        UUID venue = jdbc.queryForObject("INSERT INTO venues (name) VALUES (?) RETURNING id", UUID.class, "Cancha " + UUID.randomUUID());
        return new Club(staff("COORDINATOR"), staff("COACH"), category, venue);
    }

    /** Jugador de la categoría con su acudiente vinculado; las banderas deciden qué requisitos cumple. */
    private record Kid(UUID id, UUID guardianUserId, UUID guardianId, String guardianEmail) {
    }

    private Kid kid(Club club, String status, boolean certificate, boolean authorization, boolean arrears) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Ana', 'Ruiz', 'TI', ?, DATE '2012-03-01', ?) RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), status);
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "comp-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "CP-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, club.category(), club.coach().id(), minute);

        String email = "guardian-" + UUID.randomUUID() + "@familia.test";
        UUID guardianUser = user(email, "GUARDIAN");
        UUID guardian = jdbc.queryForObject(
                "INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                UUID.class, guardianUser, "cc-" + UUID.randomUUID().toString().substring(0, 20));
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", player, guardian);

        if (certificate) {
            addCertificate(player, guardianUser, 1, "current_date + 90");
        }
        if (authorization) {
            jdbc.update("""
                    INSERT INTO consents (guardian_id, player_id, kind, version_text, method)
                    VALUES (?, ?, 'PARTICIPATION', 'v1', 'AUTHENTICATED_ACCEPTANCE')
                    """, guardian, player);
        }
        if (arrears) {
            UUID season = jdbc.queryForObject("INSERT INTO seasons (name, start_at, end_at) VALUES (?, now(), now() + interval '1 year') RETURNING id",
                    UUID.class, "Temporada " + UUID.randomUUID());
            UUID fee = jdbc.queryForObject("""
                    INSERT INTO fees (season_id, concept, name, amount, valid_from) VALUES (?, 'MONTHLY', ?, 50000, current_date)
                    RETURNING id
                    """, UUID.class, season, "Mensualidad " + UUID.randomUUID());
            jdbc.update("""
                    INSERT INTO charges (player_id, fee_id, period, amount, due_date)
                    VALUES (?, ?, date_trunc('month', current_date - 60)::date, 50000, current_date - 40)
                    """, player, fee);
        }
        return new Kid(player, guardianUser, guardian, email);
    }

    private Kid fullKid(Club club) {
        return kid(club, "ACTIVE", true, true, false);
    }

    /** Certificado médico con la fecha de vencimiento dada como expresión SQL (la tabla es de solo inserción). */
    private void addCertificate(UUID player, UUID uploadedBy, int version, String expiresOnSql) {
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'certificado.pdf', 'application/pdf', 10, repeat('b', 64)) RETURNING id
                """, UUID.class, "comp-it/" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO document_versions (player_id, document_type_id, version_number, file_id, expires_on, uploaded_by)
                SELECT ?, id, ?, ?, %s, ? FROM document_types WHERE code = 'MEDICAL_CERTIFICATE'
                """.formatted(expiresOnSql), player, version, file, uploadedBy);
    }

    private static Instant startAt(int daysAhead, int hourLocal) {
        return LocalDate.now(CLUB_ZONE).plusDays(daysAhead).atTime(hourLocal, 0).atZone(CLUB_ZONE).toInstant();
    }

    private Map<String, Object> matchRequest(Club club, UUID tournament, int daysAhead, int hourLocal) {
        Instant start = startAt(daysAhead, hourLocal);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("tournamentId", tournament);
        request.put("categoryId", club.category());
        request.put("venueId", club.venue());
        request.put("headCoachId", club.coach().id());
        request.put("opponent", "Club Rival");
        request.put("startAt", start.toString());
        request.put("endAt", start.plusSeconds(5400).toString());
        request.put("meetingAt", start.minusSeconds(3600).toString());
        request.put("meetingPoint", "Parqueadero principal");
        return request;
    }

    private UUID scheduleMatch(Club club) throws Exception {
        return UUID.fromString(body(postJson("/matches", matchRequest(club, null, NEXT_DAY.getAndIncrement(), 10),
                club.coordinator().token()).andExpect(status().isCreated())).get("id").asText());
    }

    private JsonNode propose(Club club, UUID match, Kid... kids) throws Exception {
        return body(postJson("/matches/" + match + "/callup", Map.of("playerIds", ids(kids)), club.coach().token())
                .andExpect(status().isOk()));
    }

    private ResultActions confirm(Club club, UUID match, Kid... kids) throws Exception {
        return postJson("/matches/" + match + "/callup/confirm", Map.of("playerIds", ids(kids)), club.coordinator().token());
    }

    private static List<UUID> ids(Kid... kids) {
        List<UUID> ids = new ArrayList<>();
        for (Kid kid : kids) {
            ids.add(kid.id());
        }
        return ids;
    }

    private static JsonNode entry(JsonNode callups, Kid kid) {
        for (JsonNode node : callups) {
            if (kid.id().toString().equals(node.get("playerId").asText())) {
                return node;
            }
        }
        throw new AssertionError("no hay convocado " + kid.id());
    }

    private long notifications(UUID match, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE related_entity_id = ? AND notification_type = ?",
                Long.class, match, type);
    }

    /** Mueve el partido al pasado (la programación exige futuro) para poder registrar el resultado. */
    private void kickOff(UUID match) {
        jdbc.update("""
                UPDATE sports_events SET start_at = now() - interval '2 hours', end_at = now() - interval '30 minutes'
                WHERE id = (SELECT event_id FROM matches WHERE id = ?)
                """, match);
    }

    private Map<String, Object> line(Kid kid, int minutes, int goals) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("playerId", kid.id());
        line.put("minutesPlayed", minutes);
        line.put("goals", goals);
        return line;
    }

    @SafeVarargs
    private Map<String, Object> result(int scoreFor, int scoreAgainst, String reason, Map<String, Object>... players) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("scoreFor", scoreFor);
        request.put("scoreAgainst", scoreAgainst);
        request.put("resultNotes", "Partido disputado");
        request.put("reason", reason);
        request.put("players", List.of(players));
        return request;
    }

    // ---------- pruebas ----------

    @Test
    void proposalMarksIneligiblePlayersWithTheCauseAndConfirmationNotifiesGuardiansWithoutHealthData() throws Exception {
        Club club = club();
        Kid ok1 = fullKid(club);
        Kid ok2 = fullKid(club);
        Kid noCertificate = kid(club, "ACTIVE", false, true, false);
        Kid inArrears = kid(club, "ACTIVE", true, true, true);
        Kid noAuthorization = kid(club, "ACTIVE", true, false, false);
        Kid withdrawn = kid(club, "WITHDRAWN", true, true, false);
        UUID match = scheduleMatch(club);

        JsonNode callups = propose(club, match, ok1, ok2, noCertificate, inArrears, noAuthorization, withdrawn);

        assertThat(entry(callups, ok1).get("status").asText()).isEqualTo("PROPOSED");
        assertThat(entry(callups, ok2).get("status").asText()).isEqualTo("PROPOSED");
        Map<Kid, String> causes = Map.of(noCertificate, "MEDICAL_CERTIFICATE_INVALID", inArrears, "ARREARS_POLICY",
                noAuthorization, "PARTICIPATION_AUTHORIZATION_INVALID", withdrawn, "PLAYER_NOT_ACTIVE");
        causes.forEach((kid, cause) -> {
            assertThat(entry(callups, kid).get("status").asText()).isEqualTo("NOT_ELIGIBLE");
            assertThat(entry(callups, kid).get("ineligibilityCode").asText()).isEqualTo(cause);
        });

        // Quien no es elegible no puede confirmarse; los elegibles sí, y solo el coordinador confirma.
        JsonNode conflict = body(confirm(club, match, noCertificate).andExpect(status().isConflict()));
        assertThat(conflict.get("players").get(0).asText()).isEqualTo(noCertificate.id().toString());
        postJson("/matches/" + match + "/callup/confirm", Map.of("playerIds", ids(ok1, ok2)), club.coach().token())
                .andExpect(status().isForbidden());
        JsonNode confirmed = body(confirm(club, match, ok1, ok2).andExpect(status().isOk()));
        assertThat(entry(confirmed, ok1).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(entry(confirmed, ok2).get("confirmedAt").asText()).isNotBlank();

        // Un aviso por acudiente de convocado confirmado, sin datos de salud ni causas.
        assertThat(notifications(match, "MATCH_CALLUP")).isEqualTo(2);
        List<String> payloads = jdbc.queryForList("SELECT payload::text FROM notifications WHERE related_entity_id = ?", String.class, match);
        assertThat(payloads).hasSize(2).allSatisfy(p -> assertThat(p.toLowerCase())
                .doesNotContain("medic", "certificate", "ineligib", "arrears", "eligib"));

        // Corregido el requisito, proponer de nuevo re-evalúa y deja al jugador propuesto.
        addCertificate(noCertificate.id(), noCertificate.guardianUserId(), 1, "current_date + 30");
        JsonNode again = propose(club, match, noCertificate, ok1);
        assertThat(entry(again, noCertificate).get("status").asText()).isEqualTo("PROPOSED");
        assertThat(entry(again, ok1).get("status").asText()).as("un confirmado no cambia").isEqualTo("CONFIRMED");

        // Retirar a un confirmado lo saca de la convocatoria, avisa a su acudiente y repetirlo no falla.
        deleteJson("/matches/" + match + "/callup/" + ok2.id(), club.coach().token()).andExpect(status().isNoContent());
        deleteJson("/matches/" + match + "/callup/" + ok2.id(), club.coach().token()).andExpect(status().isNoContent());
        assertThat(notifications(match, "MATCH_CALLUP_REMOVED")).isEqualTo(1);
        JsonNode detail = body(getJson("/matches/" + match, club.coach().token()).andExpect(status().isOk()));
        assertThat(detail.get("callups")).noneMatch(c -> ok2.id().toString().equals(c.get("playerId").asText()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'CALLUP_CONFIRMED'",
                Long.class, match)).isEqualTo(1);
    }

    @Test
    void confirmationEvaluatesAgainAndRejectsAPlayerWhoNoLongerQualifies() throws Exception {
        Club club = club();
        Kid kid = fullKid(club);
        UUID match = scheduleMatch(club);
        assertThat(entry(propose(club, match, kid), kid).get("status").asText()).isEqualTo("PROPOSED");

        // Después de proponer vence el certificado: llega una versión nueva ya vencida.
        addCertificate(kid.id(), kid.guardianUserId(), 2, "current_date - 1");

        JsonNode problem = body(confirm(club, match, kid).andExpect(status().isConflict()));
        assertThat(problem.get("code").asText()).isEqualTo("CALLUP_NOT_ELIGIBLE");
        assertThat(problem.get("players").get(0).get("playerId").asText()).isEqualTo(kid.id().toString());
        assertThat(problem.get("players").get(0).get("causes").get(0).asText()).isEqualTo("MEDICAL_CERTIFICATE_INVALID");
        assertThat(jdbc.queryForObject("SELECT status FROM callup_entries WHERE match_id = ? AND player_id = ?", String.class,
                match, kid.id())).as("nada se confirma").isEqualTo("PROPOSED");
        assertThat(notifications(match, "MATCH_CALLUP")).isZero();
    }

    @Test
    void guardianAuthorizesOnlyConfirmedCallupsOfTheirOwnChildAndItIsIdempotent() throws Exception {
        Club club = club();
        Kid kid = fullKid(club);
        Kid ineligible = kid(club, "ACTIVE", false, true, false);
        Kid stranger = fullKid(club);
        UUID match = scheduleMatch(club);
        propose(club, match, kid, ineligible);
        confirm(club, match, kid).andExpect(status().isOk());

        String guardian = login(kid.guardianEmail());
        String path = "/matches/" + match + "/players/" + kid.id() + "/authorization";
        assertThat(body(postJson(path, null, guardian).andExpect(status().isOk())).get("recordedNow").asBoolean()).isTrue();
        assertThat(body(postJson(path, null, guardian).andExpect(status().isOk())).get("recordedNow").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM consents WHERE kind = 'MATCH_AUTHORIZATION' AND match_id = ? AND player_id = ?",
                Long.class, match, kid.id())).isEqualTo(1);

        // Otro acudiente, el jugador no confirmado y el personal no pueden autorizar; no se revela por qué.
        postJson(path, null, login(stranger.guardianEmail())).andExpect(status().isNotFound());
        postJson("/matches/" + match + "/players/" + ineligible.id() + "/authorization", null, login(ineligible.guardianEmail()))
                .andExpect(status().isNotFound());
        postJson(path, null, club.coach().token()).andExpect(status().isForbidden());
        postJson(path, null, null).andExpect(status().isUnauthorized());

        assertThat(entry(body(getJson("/matches/" + match, club.coordinator().token()).andExpect(status().isOk())).get("callups"), kid)
                .get("authorized").asBoolean()).isTrue();
        JsonNode history = body(getJson("/players/" + kid.id() + "/matches", guardian).andExpect(status().isOk()));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).get("matchId").asText()).isEqualTo(match.toString());
        assertThat(history.get(0).get("authorized").asBoolean()).isTrue();
        assertThat(history.get(0).has("ineligibilityCode")).isFalse();
        getJson("/players/" + kid.id() + "/matches", login(stranger.guardianEmail())).andExpect(status().isNotFound());
    }

    @Test
    void resultIsRecordedAfterKickoffCorrectedWithAReasonAndShownInTheHistory() throws Exception {
        Club club = club();
        Kid first = fullKid(club);
        Kid second = fullKid(club);
        Kid notCalled = fullKid(club);
        UUID match = scheduleMatch(club);
        propose(club, match, first, second);
        confirm(club, match, first, second).andExpect(status().isOk());

        // Antes del inicio no hay resultado.
        putJson("/matches/" + match + "/result", result(2, 1, null, line(first, 90, 2)), club.coach().token())
                .andExpect(status().isConflict());
        kickOff(match);

        // Jugador no convocado, puntos individuales por encima del marcador y rol sin permiso.
        JsonNode invalid = body(putJson("/matches/" + match + "/result", result(1, 0, null, line(notCalled, 90, 0), line(first, 80, 3)),
                club.coach().token()).andExpect(status().isUnprocessableEntity()));
        assertThat(invalid.toString()).contains("players[0].playerId", "players");
        putJson("/matches/" + match + "/result", result(2, 1, null), login(first.guardianEmail())).andExpect(status().isForbidden());
        Account otherCoach = staff("COACH");
        putJson("/matches/" + match + "/result", result(2, 1, null), otherCoach.token()).andExpect(status().isNotFound());

        JsonNode recorded = body(putJson("/matches/" + match + "/result", result(2, 1, null, line(first, 90, 2), line(second, 45, 0)),
                club.coach().token()).andExpect(status().isOk()));
        assertThat(recorded.get("match").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(recorded.get("match").get("scoreFor").asInt()).isEqualTo(2);
        assertThat(entry(recorded.get("callups"), first).get("minutesPlayed").asInt()).isEqualTo(90);
        assertThat(entry(recorded.get("callups"), first).get("goals").asInt()).isEqualTo(2);

        // Corregir un resultado ya registrado exige motivo y deja el marcador anterior en la auditoría.
        JsonNode noReason = body(putJson("/matches/" + match + "/result", result(3, 1, null, line(first, 90, 3)),
                club.coach().token()).andExpect(status().isUnprocessableEntity()));
        assertThat(noReason.toString()).contains("reason");
        putJson("/matches/" + match + "/result", result(3, 1, "Gol anotado en tiempo añadido", line(first, 90, 3)),
                club.coach().token()).andExpect(status().isOk());
        Map<String, Object> audit = jdbc.queryForMap("""
                SELECT reason, before_state::text AS before_state, after_state::text AS after_state FROM audit_events
                WHERE entity_id = ? AND action = 'MATCH_RESULT_CORRECTED'
                """, match);
        assertThat(audit.get("reason")).isEqualTo("Gol anotado en tiempo añadido");
        assertThat((String) audit.get("before_state")).contains("\"scoreFor\": 2");
        assertThat((String) audit.get("after_state")).contains("\"scoreFor\": 3");

        JsonNode history = body(getJson("/players/" + first.id() + "/matches", login(first.guardianEmail())).andExpect(status().isOk()));
        assertThat(history.get(0).get("matchStatus").asText()).isEqualTo("COMPLETED");
        assertThat(history.get(0).get("scoreFor").asInt()).isEqualTo(3);
        assertThat(history.get(0).get("minutesPlayed").asInt()).isEqualTo(90);
        assertThat(history.get(0).get("goals").asInt()).isEqualTo(3);
        assertThat(body(getJson("/players/" + notCalled.id() + "/matches", club.coordinator().token()).andExpect(status().isOk()))).isEmpty();
    }

    @Test
    void reschedulingAMatchReevaluatesTheCallupReturnsTheConfirmedWhoNoLongerQualifyAndNotifiesTheirGuardians() throws Exception {
        Club club = club();
        Kid expiring = fullKid(club);                      // certificado hasta dentro de 90 días
        Kid lasting = kid(club, "ACTIVE", false, true, false);
        addCertificate(lasting.id(), lasting.guardianUserId(), 1, "current_date + 400");
        Kid proposedExpiring = fullKid(club);
        Kid proposedLasting = kid(club, "ACTIVE", false, true, false);
        addCertificate(proposedLasting.id(), proposedLasting.guardianUserId(), 1, "current_date + 400");
        UUID match = scheduleMatch(club);
        propose(club, match, expiring, lasting, proposedExpiring, proposedLasting);
        confirm(club, match, expiring, lasting).andExpect(status().isOk());
        String eventId = jdbc.queryForObject("SELECT event_id::text FROM matches WHERE id = ?", String.class, match);
        Instant originalStart = Instant.parse(body(getJson("/matches/" + match, club.coordinator().token()).andExpect(status().isOk()))
                .get("match").get("startAt").asText());

        // Permisos y datos: solo el coordinador; motivo obligatorio; futuro; sin cambios no hay reprogramación.
        Map<String, Object> shift = rescheduleRequest(originalStart.plusSeconds(1800), "Cambio de cancha");
        postJson("/matches/" + match + "/reschedule", shift, club.coach().token()).andExpect(status().isForbidden());
        postJson("/matches/" + match + "/reschedule", rescheduleRequest(originalStart.plusSeconds(1800), " "),
                club.coordinator().token()).andExpect(status().isUnprocessableEntity());
        postJson("/matches/" + match + "/reschedule", rescheduleRequest(Instant.now().minusSeconds(3600), "Ayer"),
                club.coordinator().token()).andExpect(status().isUnprocessableEntity());
        postJson("/events/" + eventId + "/reschedule", shift, club.coordinator().token()).andExpect(status().isConflict());

        // Correrlo media hora no choca con el propio partido y no cambia a nadie.
        JsonNode moved = body(postJson("/matches/" + match + "/reschedule", shift, club.coordinator().token()).andExpect(status().isOk()));
        assertThat(moved.get("match").get("startAt").asText()).isEqualTo(originalStart.plusSeconds(1800).toString());
        assertThat(moved.get("confirmedToReview")).isEmpty();
        assertThat(notifications(match, "MATCH_RESCHEDULED")).as("un aviso por acudiente de confirmado").isEqualTo(2);

        // Pasarlo a dentro de 120 días: el certificado de 90 días no vale ese día.
        Instant far = startAt(120, 10);
        JsonNode result = body(postJson("/matches/" + match + "/reschedule", rescheduleRequest(far, "Se aplazó la fecha"),
                club.coordinator().token()).andExpect(status().isOk()));
        assertThat(result.get("match").get("startAt").asText()).isEqualTo(far.toString());
        assertThat(result.get("confirmedToReview")).hasSize(1);
        assertThat(result.get("confirmedToReview").get(0).get("playerId").asText()).isEqualTo(expiring.id().toString());
        assertThat(result.get("confirmedToReview").get(0).get("causes").get(0).asText()).isEqualTo("MEDICAL_CERTIFICATE_INVALID");
        assertThat(notifications(match, "MATCH_RESCHEDULED")).isEqualTo(4);
        String payload = jdbc.queryForObject("SELECT payload::text FROM notifications WHERE related_entity_id = ? AND notification_type = 'MATCH_RESCHEDULED' ORDER BY created_at DESC LIMIT 1",
                String.class, match);
        assertThat(payload).contains("previousStartAt").contains("Se aplazó la fecha");
        assertThat(payload.toLowerCase()).doesNotContain("medic", "certificate", "eligib");

        JsonNode detail = body(getJson("/matches/" + match, club.coordinator().token()).andExpect(status().isOk()));
        assertThat(entry(detail.get("callups"), expiring).get("status").asText()).as("el confirmado conserva su lugar").isEqualTo("CONFIRMED");
        assertThat(entry(detail.get("callups"), lasting).get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(entry(detail.get("callups"), proposedExpiring).get("status").asText()).isEqualTo("NOT_ELIGIBLE");
        assertThat(entry(detail.get("callups"), proposedLasting).get("status").asText()).isEqualTo("PROPOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'MATCH_RESCHEDULED'",
                Long.class, match)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ?::uuid AND action = 'EVENT_RESCHEDULED'",
                Long.class, eventId)).isEqualTo(2);

        // Un partido cancelado o realizado ya no se reprograma.
        postJson("/matches/" + match + "/cancel", Map.of("reason", "Lluvia"), club.coordinator().token()).andExpect(status().isOk());
        postJson("/matches/" + match + "/reschedule", rescheduleRequest(startAt(130, 10), "Otra vez"), club.coordinator().token())
                .andExpect(status().isConflict());
    }

    private Map<String, Object> rescheduleRequest(Instant start, String reason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("startAt", start.toString());
        request.put("endAt", start.plusSeconds(5400).toString());
        request.put("meetingAt", start.minusSeconds(3600).toString());
        request.put("reason", reason);
        return request;
    }

    @Test
    void theCoachGetsACalendarEntryWhenAMatchIsScheduledRescheduledAndCancelledAndNoFamilyIsNotifiedThatWay() throws Exception {
        Club club = club();
        Kid confirmed = fullKid(club);
        UUID match = scheduleMatch(club);
        propose(club, match, confirmed);
        confirm(club, match, confirmed).andExpect(status().isOk());
        UUID event = jdbc.queryForObject("SELECT event_id FROM matches WHERE id = ?", UUID.class, match);
        String coachEmail = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, club.coach().id());

        assertThat(eventNoticeRecipients(event, "EVENT_SCHEDULED")).containsExactly(coachEmail);
        JsonNode payload = json.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM notifications WHERE related_entity_id = ? AND notification_type = 'EVENT_SCHEDULED'",
                String.class, event));
        assertThat(payload.get("opponent").asText()).isEqualTo("Club Rival");
        assertThat(payload.get("eventType").asText()).isEqualTo("FRIENDLY_MATCH");
        assertThat(payload.has("playerName")).isFalse();

        Instant start = Instant.parse(body(getJson("/matches/" + match, club.coordinator().token()).andExpect(status().isOk()))
                .get("match").get("startAt").asText());
        postJson("/matches/" + match + "/reschedule", rescheduleRequest(start.plusSeconds(1800), "Cambio de cancha"),
                club.coordinator().token()).andExpect(status().isOk());
        assertThat(eventNoticeRecipients(event, "EVENT_RESCHEDULED")).containsExactly(coachEmail);

        postJson("/matches/" + match + "/cancel", Map.of("reason", "Lluvia"), club.coordinator().token()).andExpect(status().isOk());
        assertThat(eventNoticeRecipients(event, "EVENT_CANCELLED")).containsExactly(coachEmail);
        // La familia del confirmado solo recibe los avisos propios del partido.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE related_entity_id = ? AND recipient_email = ?",
                Long.class, event, confirmed.guardianEmail())).isZero();
        assertThat(notifications(match, "MATCH_RESCHEDULED")).isEqualTo(1);
        assertThat(notifications(match, "MATCH_CANCELLED")).isEqualTo(1);
    }

    private List<String> eventNoticeRecipients(UUID event, String type) {
        return jdbc.queryForList("SELECT recipient_email FROM notifications WHERE related_entity_id = ? AND notification_type = ? ORDER BY 1",
                String.class, event, type);
    }

    @Test
    void cancellingAMatchNotifiesConfirmedGuardiansAndBlocksFurtherChanges() throws Exception {
        Club club = club();
        Kid confirmed = fullKid(club);
        Kid proposedOnly = fullKid(club);
        UUID match = scheduleMatch(club);
        propose(club, match, confirmed, proposedOnly);
        confirm(club, match, confirmed).andExpect(status().isOk());

        // El endpoint genérico de eventos no cancela partidos: se pasaría por alto el aviso a los acudientes.
        String eventId = jdbc.queryForObject("SELECT event_id::text FROM matches WHERE id = ?", String.class, match);
        postJson("/events/" + eventId + "/cancel", Map.of("reason", "Lluvia"), club.coordinator().token()).andExpect(status().isConflict());

        postJson("/matches/" + match + "/cancel", Map.of("reason", " "), club.coordinator().token()).andExpect(status().isUnprocessableEntity());
        postJson("/matches/" + match + "/cancel", Map.of("reason", "Lluvia"), club.coach().token()).andExpect(status().isForbidden());
        JsonNode cancelled = body(postJson("/matches/" + match + "/cancel", Map.of("reason", "Cancha inundada"),
                club.coordinator().token()).andExpect(status().isOk()));
        assertThat(cancelled.get("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("cancellationReason").asText()).isEqualTo("Cancha inundada");
        assertThat(notifications(match, "MATCH_CANCELLED")).as("solo al acudiente del confirmado").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action = 'MATCH_CANCELLED'",
                Long.class, match)).isEqualTo(1);

        postJson("/matches/" + match + "/cancel", Map.of("reason", "Otra vez"), club.coordinator().token()).andExpect(status().isConflict());
        postJson("/matches/" + match + "/callup", Map.of("playerIds", ids(confirmed)), club.coach().token()).andExpect(status().isConflict());
        putJson("/matches/" + match + "/result", result(1, 0, null), club.coach().token()).andExpect(status().isConflict());
        JsonNode history = body(getJson("/players/" + confirmed.id() + "/matches", login(confirmed.guardianEmail())).andExpect(status().isOk()));
        assertThat(history.get(0).get("matchStatus").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void matchSchedulingValidatesTheOpponentTheAgendaAndPermissions() throws Exception {
        Club club = club();
        postJson("/matches", matchRequest(club, null, 20, 10), club.coach().token()).andExpect(status().isForbidden());
        postJson("/matches", matchRequest(club, null, 20, 10), null).andExpect(status().isUnauthorized());

        Map<String, Object> noOpponent = matchRequest(club, null, 20, 10);
        noOpponent.put("opponent", " ");
        assertThat(body(postJson("/matches", noOpponent, club.coordinator().token()).andExpect(status().isUnprocessableEntity())).toString())
                .contains("opponent");

        JsonNode match = body(postJson("/matches", matchRequest(club, null, 20, 10), club.coordinator().token())
                .andExpect(status().isCreated()));
        assertThat(match.get("opponent").asText()).isEqualTo("Club Rival");
        assertThat(match.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT event_type FROM sports_events WHERE id = ?", String.class,
                UUID.fromString(match.get("eventId").asText()))).isEqualTo("FRIENDLY_MATCH");

        // Mismo escenario, entrenador y categoría a la misma hora: conflicto de agenda (RN-14).
        JsonNode conflict = body(postJson("/matches", matchRequest(club, null, 20, 10), club.coordinator().token())
                .andExpect(status().isConflict()));
        assertThat(conflict.get("code").asText()).isEqualTo("SCHEDULE_CONFLICT");

        // Un entrenador ajeno no ve el partido ni aparece en su lista; el responsable sí.
        Account other = staff("COACH");
        getJson("/matches/" + match.get("id").asText(), other.token()).andExpect(status().isNotFound());
        assertThat(body(getJson("/matches?categoryId=" + club.category(), other.token()).andExpect(status().isOk()))).isEmpty();
        assertThat(body(getJson("/matches?categoryId=" + club.category(), club.coach().token()).andExpect(status().isOk()))).hasSize(1);
        assertThat(body(getJson("/matches?categoryId=" + club.category(), club.coordinator().token()).andExpect(status().isOk()))).hasSize(1);
        postJson("/matches/" + match.get("id").asText() + "/callup", Map.of("playerIds", List.of(UUID.randomUUID())), other.token())
                .andExpect(status().isNotFound());
        postJson("/matches/" + match.get("id").asText() + "/callup", Map.of("playerIds", List.of(UUID.randomUUID())),
                club.coach().token()).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void tournamentsAreUniquePerSeasonAndConstrainTheirMatches() throws Exception {
        Club club = club();
        UUID season = jdbc.queryForObject("INSERT INTO seasons (name, start_at, end_at) VALUES (?, now(), now() + interval '1 year') RETURNING id",
                UUID.class, "Temporada " + UUID.randomUUID());
        LocalDate matchDay = LocalDate.now(CLUB_ZONE).plusDays(30);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("seasonId", season);
        request.put("name", "Liga Juvenil");
        request.put("startDate", matchDay.minusDays(5).toString());
        request.put("endDate", matchDay.plusDays(5).toString());
        request.put("categoryId", club.category());
        request.put("venueId", club.venue());
        request.put("rules", "Partidos de 2 tiempos de 35 minutos");

        postJson("/tournaments", request, club.coach().token()).andExpect(status().isForbidden());
        JsonNode tournament = body(postJson("/tournaments", request, club.coordinator().token()).andExpect(status().isCreated()));
        UUID tournamentId = UUID.fromString(tournament.get("id").asText());
        assertThat(tournament.get("rules").asText()).contains("35 minutos");
        assertThat(body(postJson("/tournaments", request, club.coordinator().token()).andExpect(status().isUnprocessableEntity())).toString())
                .contains("name");
        Map<String, Object> reversed = new LinkedHashMap<>(request);
        reversed.put("name", "Copa");
        reversed.put("endDate", matchDay.minusDays(30).toString());
        assertThat(body(postJson("/tournaments", reversed, club.coordinator().token()).andExpect(status().isUnprocessableEntity())).toString())
                .contains("endDate");
        assertThat(body(getJson("/tournaments?seasonId=" + season, club.coach().token()).andExpect(status().isOk()))).hasSize(1);

        // El partido debe ser de la categoría del torneo y jugarse dentro de sus fechas.
        Club otherClub = club();
        Map<String, Object> wrongCategory = matchRequest(otherClub, tournamentId, 30, 10);
        assertThat(body(postJson("/matches", wrongCategory, otherClub.coordinator().token()).andExpect(status().isUnprocessableEntity())).toString())
                .contains("categoryId");
        assertThat(body(postJson("/matches", matchRequest(club, tournamentId, 60, 10), club.coordinator().token())
                .andExpect(status().isUnprocessableEntity())).toString()).contains("startAt");
        postJson("/matches", matchRequest(club, UUID.randomUUID(), 30, 10), club.coordinator().token()).andExpect(status().isUnprocessableEntity());

        JsonNode match = body(postJson("/matches", matchRequest(club, tournamentId, 30, 10), club.coordinator().token())
                .andExpect(status().isCreated()));
        assertThat(match.get("tournamentId").asText()).isEqualTo(tournamentId.toString());
        assertThat(match.get("tournamentName").asText()).isEqualTo("Liga Juvenil");
        assertThat(jdbc.queryForObject("SELECT event_type FROM sports_events WHERE id = ?", String.class,
                UUID.fromString(match.get("eventId").asText()))).isEqualTo("TOURNAMENT_MATCH");
        assertThat(body(getJson("/matches?tournamentId=" + tournamentId, club.coordinator().token()).andExpect(status().isOk()))).hasSize(1);
    }
}
