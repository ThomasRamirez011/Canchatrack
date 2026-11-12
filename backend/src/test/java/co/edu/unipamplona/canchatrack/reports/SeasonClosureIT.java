package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
 * Informe de temporada por jugador (CU-10) y cierre o reapertura del expediente (CU-11) contra PostgreSQL real. Los datos
 * deportivos se siembran por SQL en 2024-2025 dentro de una temporada que ya empezó y aún no termina (informe parcial).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SeasonClosureIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(3600);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    // ---------- utilidades ----------

    private record Account(UUID id, String token) {
    }

    private ResultActions send(MockHttpServletRequestBuilder request, Object body, String bearer) throws Exception {
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
    }

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        return send(post(API + path), body, bearer);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private UUID user(String email, String fullName, String roleCode) {
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, fullName, encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return id;
    }

    private Account staff(String roleCode, String fullName) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = user(email, fullName, roleCode);
        String token = body(send(post(API + "/auth/login"), Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isOk())).get("accessToken").asText();
        return new Account(id, token);
    }

    private Account staff(String roleCode) throws Exception {
        return staff(roleCode, "Personal de prueba");
    }

    private UUID season(String start, String end, String status) {
        return jdbc.queryForObject("INSERT INTO seasons (name, start_at, end_at, status) VALUES (?, ?::timestamptz, ?::timestamptz, ?) RETURNING id",
                UUID.class, "Temporada " + UUID.randomUUID(), start, end, status);
    }

    /** Temporada que ya empezó y todavía no termina: el informe es parcial hasta hoy. */
    private UUID runningSeason() {
        return season("2024-01-01T00:00:00Z", "2099-12-31T00:00:00Z", "ACTIVE");
    }

    private UUID category() {
        int year = NEXT_YEAR.getAndAdd(3);
        return jdbc.queryForObject("""
                INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 30) RETURNING id
                """, UUID.class, "Sub-" + UUID.randomUUID().toString().substring(0, 8), year, year + 1);
    }

    private UUID player(UUID category, UUID coach, String firstName, String status) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES (?, 'Ruiz', 'TI', ?, DATE '2012-03-01', ?) RETURNING id
                """, UUID.class, firstName, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), status);
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "closure-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "SC-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        return player;
    }

    private UUID venue() {
        return jdbc.queryForObject("INSERT INTO venues (name) VALUES (?) RETURNING id", UUID.class, "Cancha " + UUID.randomUUID());
    }

    private UUID event(UUID category, UUID venue, String type, String status, String start) {
        return jdbc.queryForObject("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at, status, cancellation_reason)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz + interval '1 hour', ?, CASE WHEN ? = 'CANCELLED' THEN 'lluvia' END)
                RETURNING id
                """, UUID.class, type, category, venue, start, start, status, status);
    }

    /** Sesión cerrada con una marca del jugador (los registros se insertan antes del cierre: el disparador CT002). */
    private void closedSession(UUID event, UUID player, String status, boolean excuseAccepted, UUID by) {
        jdbc.update("""
                INSERT INTO attendance_records (event_id, player_id, status, excuse_accepted, client_uuid, recorded_by)
                VALUES (?, ?, ?, ?, gen_random_uuid(), ?)
                """, event, player, status, excuseAccepted, by);
        jdbc.update("INSERT INTO attendance_sessions (event_id, closed_at, closed_by) VALUES (?, now(), ?)", event, by);
    }

    private void closedRound(UUID player, UUID openedBy, List<UUID> evaluators, String closedAt) {
        UUID round = jdbc.queryForObject("""
                INSERT INTO evaluation_rounds (player_id, period_start, period_end, status, opened_by)
                VALUES (?, DATE '2024-02-01', DATE '2024-05-31', 'OPEN', ?) RETURNING id
                """, UUID.class, player, openedBy);
        for (UUID evaluator : evaluators) {
            jdbc.update("""
                    INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, period_start, period_end)
                    VALUES (?, ?, 'FAVORABLE', DATE '2024-02-01', DATE '2024-05-31')
                    """, round, evaluator);
        }
        jdbc.update("UPDATE evaluation_rounds SET status = 'CLOSED', result = 'APPROVED', closed_at = ?::timestamptz WHERE id = ?",
                closedAt, round);
    }

    private void playedMatch(UUID category, UUID venue, UUID player, UUID by, String start, int minutes, int goals) {
        UUID event = event(category, venue, "FRIENDLY_MATCH", "COMPLETED", start);
        UUID match = jdbc.queryForObject("""
                INSERT INTO matches (event_id, opponent, score_for, score_against, result_recorded_by, result_recorded_at)
                VALUES (?, 'Halcones del Norte', 2, 1, ?, ?::timestamptz + interval '3 hours') RETURNING id
                """, UUID.class, event, by, start);
        jdbc.update("""
                INSERT INTO callup_entries (match_id, player_id, status, proposed_by, confirmed_by, confirmed_at, minutes_played, goals)
                VALUES (?, ?, 'CONFIRMED', ?, ?, now(), ?, ?)
                """, match, player, by, by, minutes, goals);
    }

    private String statusOf(UUID player) {
        return jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, player);
    }

    private long audits(String action, UUID entityId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND entity_id = ?", Long.class, action, entityId);
    }

    private JsonNode generate(Account coordinator, UUID player, UUID season, String reason) throws Exception {
        Map<String, Object> request = reason == null ? Map.of("seasonId", season) : Map.of("seasonId", season, "reason", reason);
        return body(postJson("/players/" + player + "/season-reports", request, coordinator.token()).andExpect(status().isCreated()));
    }

    private static List<String> fields(JsonNode problem) {
        return StreamSupport.stream(problem.get("errors").spliterator(), false).map(e -> e.get("field").asText()).toList();
    }

    /** Jugador activo con trayectoria completa: dos sesiones (asistió y faltó), una ronda cerrada y un partido. */
    private record Scene(Account coordinator, Account coach, UUID season, UUID category, UUID venue, UUID player,
                         String playerCode) {
    }

    private Scene scene() throws Exception {
        Account coordinator = staff("COORDINATOR");
        Account coach = staff("COACH");
        UUID season = runningSeason();
        UUID category = category();
        UUID venue = venue();
        UUID player = player(category, coach.id(), "Ana", "ACTIVE");

        closedSession(event(category, venue, "TRAINING", "CONFIRMED", "2024-03-02T10:00:00Z"), player, "PRESENT", false, coach.id());
        closedSession(event(category, venue, "TRAINING", "CONFIRMED", "2024-03-09T10:00:00Z"), player, "ABSENT", false, coach.id());
        closedSession(event(category, venue, "TRAINING", "CANCELLED", "2024-03-16T10:00:00Z"), player, "PRESENT", false, coach.id()); // cancelado: no cuenta
        closedSession(event(category, venue, "TRAINING", "CONFIRMED", "2023-12-20T10:00:00Z"), player, "PRESENT", false, coach.id()); // antes de la temporada
        List<UUID> evaluators = List.of(staff("EVALUATOR", "Ana Evaluadora").id(), staff("EVALUATOR", "Beto Evaluador").id(),
                staff("EVALUATOR", "Carla Evaluadora").id());
        closedRound(player, coordinator.id(), evaluators, "2024-06-01T15:00:00Z");
        playedMatch(category, venue, player, coordinator.id(), "2024-04-06T15:00:00Z", 60, 1);

        String code = jdbc.queryForObject("SELECT code FROM players WHERE id = ?", String.class, player);
        return new Scene(coordinator, coach, season, category, venue, player, code);
    }

    // ---------- generación del informe ----------

    @Test
    void generatingAReportProducesAStoredPdfWithUniqueNumberCodeAndMatchingHash() throws Exception {
        Scene s = scene();

        JsonNode report = generate(s.coordinator(), s.player(), s.season(), null);

        assertThat(report.get("number").asText()).matches("IT-\\d{6}");
        assertThat(report.get("version").asInt()).isEqualTo(1);
        assertThat(report.has("previousReportId")).isFalse();
        assertThat(report.get("verificationCode").asText()).matches("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}");
        assertThat(report.get("fileHash").asText()).matches("[0-9a-f]{64}");
        assertThat(report.get("templateVersion").asText()).isEqualTo("plantilla-1");
        assertThat(report.get("playerId").asText()).isEqualTo(s.player().toString());

        // El PDF se descarga con el endpoint de archivos y su huella coincide con la registrada.
        byte[] pdf = mvc.perform(get(API + "/files/" + report.get("fileId").asText()).header("Authorization", "Bearer " + s.coordinator().token()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pdf))).isEqualTo(report.get("fileHash").asText());
        String raw = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(raw).contains(report.get("number").asText(), report.get("verificationCode").asText(), s.playerCode());

        // Auditoría de la generación.
        assertThat(audits("SEASON_REPORT_GENERATED", UUID.fromString(report.get("id").asText()))).isEqualTo(1L);
    }

    @Test
    void theReportFreezesAttendanceEvaluationsAndMatchesOfThePeriodOnly() throws Exception {
        Scene s = scene();

        JsonNode report = generate(s.coordinator(), s.player(), s.season(), null);

        JsonNode snapshot = json.readTree(jdbc.queryForObject("SELECT snapshot::text FROM season_reports WHERE id = ?", String.class,
                UUID.fromString(report.get("id").asText())));
        JsonNode attendance = snapshot.get("attendance");
        assertThat(attendance.get("sessions").asInt()).as("el cancelado y el anterior a la temporada no cuentan").isEqualTo(2);
        assertThat(attendance.get("present").asInt()).isEqualTo(1);
        assertThat(attendance.get("absent").asInt()).isEqualTo(1);
        assertThat(attendance.get("percentage").decimalValue()).isEqualByComparingTo("50.0");

        JsonNode rounds = snapshot.get("evaluations");
        assertThat(rounds).hasSize(1);
        assertThat(rounds.get(0).get("result").asText()).isEqualTo("APPROVED");
        assertThat(rounds.get(0).get("concepts")).hasSize(3);
        assertThat(StreamSupport.stream(rounds.get(0).get("concepts").spliterator(), false).map(c -> c.get("verdict").asText()).distinct().toList())
                .containsExactly("FAVORABLE");

        assertThat(snapshot.get("matches")).hasSize(1);
        assertThat(snapshot.get("participation").get("minutes").asInt()).isEqualTo(60);
        assertThat(snapshot.get("participation").get("goals").asInt()).isEqualTo(1);
        assertThat(snapshot.get("period").get("partial").asBoolean()).as("la temporada no ha terminado").isTrue();
        // Sin documento de identidad ni datos de salud.
        assertThat(snapshot.toString()).doesNotContain("document").doesNotContain("TI-");
    }

    @Test
    void regeneratingKeepsEveryVersionLinksThemAndRequiresAReason() throws Exception {
        Scene s = scene();
        JsonNode first = generate(s.coordinator(), s.player(), s.season(), null);

        JsonNode missing = body(postJson("/players/" + s.player() + "/season-reports", Map.of("seasonId", s.season()),
                s.coordinator().token()).andExpect(status().isUnprocessableEntity()));
        assertThat(fields(missing)).containsExactly("reason");
        postJson("/players/" + s.player() + "/season-reports", Map.of("seasonId", s.season(), "reason", "   "),
                s.coordinator().token()).andExpect(status().isUnprocessableEntity());

        JsonNode second = generate(s.coordinator(), s.player(), s.season(), "Se corrigió la asistencia del 9 de marzo");

        assertThat(second.get("version").asInt()).isEqualTo(2);
        assertThat(second.get("previousReportId").asText()).isEqualTo(first.get("id").asText());
        assertThat(second.get("reason").asText()).contains("asistencia");
        assertThat(second.get("consecutive").asLong()).isGreaterThan(first.get("consecutive").asLong());
        assertThat(second.get("verificationCode").asText()).isNotEqualTo(first.get("verificationCode").asText());
        assertThat(second.get("fileId").asText()).isNotEqualTo(first.get("fileId").asText());

        JsonNode list = body(getJson("/players/" + s.player() + "/season-reports?seasonId=" + s.season(), s.coordinator().token())
                .andExpect(status().isOk()));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("version").asInt()).as("el más reciente primero").isEqualTo(2);
        assertThat(body(getJson("/season-reports/" + first.get("id").asText(), s.coordinator().token()).andExpect(status().isOk()))
                .get("version").asInt()).isEqualTo(1);

        // La primera versión no se puede alterar.
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("UPDATE season_reports SET reason = 'x' WHERE id = ?", UUID.fromString(first.get("id").asText())));
    }

    @Test
    void verifyingACodeAcceptsHandwrittenFormsAndSaysWhetherItIsTheCurrentVersion() throws Exception {
        Scene s = scene();
        JsonNode first = generate(s.coordinator(), s.player(), s.season(), null);
        JsonNode second = generate(s.coordinator(), s.player(), s.season(), "Nueva versión");

        String handwritten = first.get("verificationCode").asText().toLowerCase().replace("-", "");
        JsonNode old = body(getJson("/season-reports/verify?code=" + handwritten, s.coordinator().token())
                .andExpect(status().isOk()));
        assertThat(old.get("current").asBoolean()).isFalse();
        assertThat(old.get("report").get("id").asText()).isEqualTo(first.get("id").asText());

        JsonNode current = body(getJson("/season-reports/verify?code=" + second.get("verificationCode").asText(), s.coordinator().token())
                .andExpect(status().isOk()));
        assertThat(current.get("current").asBoolean()).isTrue();

        getJson("/season-reports/verify?code=AAAA-AAAA-AAAA", s.coordinator().token()).andExpect(status().isNotFound());
        getJson("/season-reports/verify?code=no-es-un-codigo", s.coordinator().token()).andExpect(status().isNotFound());
        getJson("/season-reports/verify", s.coordinator().token()).andExpect(status().isBadRequest());
    }

    @Test
    void reportsCannotBeGeneratedForUnknownOrNotYetStartedSeasonsOrPlayersWithoutTrackRecord() throws Exception {
        Scene s = scene();

        postJson("/players/" + UUID.randomUUID() + "/season-reports", Map.of("seasonId", s.season()), s.coordinator().token())
                .andExpect(status().isNotFound());
        JsonNode unknown = body(postJson("/players/" + s.player() + "/season-reports", Map.of("seasonId", UUID.randomUUID()),
                s.coordinator().token()).andExpect(status().isUnprocessableEntity()));
        assertThat(fields(unknown)).containsExactly("seasonId");
        JsonNode missing = body(postJson("/players/" + s.player() + "/season-reports", Map.of(), s.coordinator().token())
                .andExpect(status().isUnprocessableEntity()));
        assertThat(fields(missing)).containsExactly("seasonId");

        UUID planned = season("2098-01-01T00:00:00Z", "2098-12-31T00:00:00Z", "PLANNED");
        JsonNode notStarted = body(postJson("/players/" + s.player() + "/season-reports", Map.of("seasonId", planned),
                s.coordinator().token()).andExpect(status().isUnprocessableEntity()));
        assertThat(fields(notStarted)).containsExactly("seasonId");

        UUID applicant = player(s.category(), s.coach().id(), "Nuevo", "SUBMITTED");
        postJson("/players/" + applicant + "/season-reports", Map.of("seasonId", s.season()), s.coordinator().token())
                .andExpect(status().isConflict());
    }

    // ---------- cierre ----------

    @Test
    void closingNeedsTheCurrentReportAndMovesThePlayerToSeasonClosed() throws Exception {
        Scene s = scene();
        Account other = staff("COORDINATOR");
        UUID otherPlayer = player(s.category(), s.coach().id(), "Luis", "ACTIVE");
        JsonNode first = generate(s.coordinator(), s.player(), s.season(), null);
        JsonNode strangerReport = generate(other, otherPlayer, s.season(), null);

        // Campos obligatorios y un informe ajeno.
        assertThat(fields(body(postJson("/players/" + s.player() + "/season-closure", Map.of(), s.coordinator().token())
                .andExpect(status().isUnprocessableEntity())))).containsExactlyInAnyOrder("seasonId", "reportId");
        assertThat(fields(body(postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", strangerReport.get("id").asText()), s.coordinator().token())
                .andExpect(status().isUnprocessableEntity())))).containsExactly("reportId");

        // Una versión más reciente anula a la anterior.
        JsonNode second = generate(s.coordinator(), s.player(), s.season(), "Revisión previa al cierre");
        JsonNode stale = body(postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", first.get("id").asText()), s.coordinator().token())
                .andExpect(status().isConflict()));
        assertThat(stale.get("detail").asText()).contains("versión más reciente");
        assertThat(statusOf(s.player())).isEqualTo("ACTIVE");

        JsonNode closure = body(postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", second.get("id").asText()), s.coordinator().token())
                .andExpect(status().isCreated()));

        assertThat(closure.get("open").asBoolean()).isTrue();
        assertThat(closure.get("reportId").asText()).isEqualTo(second.get("id").asText());
        assertThat(closure.get("closedBy").asText()).isEqualTo(s.coordinator().id().toString());
        assertThat(statusOf(s.player())).isEqualTo("SEASON_CLOSED");
        Map<String, Object> history = jdbc.queryForMap(
                "SELECT transition_code, from_state, to_state, actor_id FROM state_history WHERE entity_id = ?", s.player());
        assertThat(history).containsEntry("transition_code", "T-11").containsEntry("from_state", "ACTIVE")
                .containsEntry("to_state", "SEASON_CLOSED").containsEntry("actor_id", s.coordinator().id());
        assertThat(audits("PLAYER_SEASON_CLOSED", s.player())).isEqualTo(1L);
        assertThat(body(getJson("/players/" + s.player() + "/season-closure", s.coordinator().token()).andExpect(status().isOk()))
                .get("id").asText()).isEqualTo(closure.get("id").asText());

        // Cerrar dos veces no es posible, y el categoría y el historial siguen ahí.
        postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", second.get("id").asText()), s.coordinator().token())
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM category_assignments WHERE player_id = ? AND valid_to IS NULL",
                Long.class, s.player())).as("la categoría se conserva").isEqualTo(1L);
    }

    @Test
    void closingIsRefusedWhenTheSportingRecordChangedAfterTheReportUntilANewVersionIsGenerated() throws Exception {
        Scene s = scene();
        JsonNode report = generate(s.coordinator(), s.player(), s.season(), null);

        // Una sesión más se cierra después de generar el informe.
        closedSession(event(s.category(), s.venue(), "TRAINING", "CONFIRMED", "2024-03-23T10:00:00Z"), s.player(), "PRESENT", false,
                s.coach().id());

        JsonNode outdated = body(postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", report.get("id").asText()), s.coordinator().token())
                .andExpect(status().isConflict()));
        assertThat(outdated.get("detail").asText()).contains("cambió después de generar el informe");
        assertThat(statusOf(s.player())).isEqualTo("ACTIVE");

        JsonNode fresh = generate(s.coordinator(), s.player(), s.season(), "Incluye la sesión del 23 de marzo");
        postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", fresh.get("id").asText()), s.coordinator().token())
                .andExpect(status().isCreated());
        assertThat(statusOf(s.player())).isEqualTo("SEASON_CLOSED");
    }

    @Test
    void aPlayerInEvaluationOrWithAPendingPromotionCannotCloseTheSeasonYet() throws Exception {
        Scene s = scene();
        for (String state : List.of("IN_EVALUATION", "PROMOTION_APPROVED")) {
            UUID busy = player(s.category(), s.coach().id(), "Pendiente", state);
            JsonNode report = generate(s.coordinator(), busy, s.season(), null); // el informe sí se puede emitir

            JsonNode refused = body(postJson("/players/" + busy + "/season-closure",
                    Map.of("seasonId", s.season(), "reportId", report.get("id").asText()), s.coordinator().token())
                    .andExpect(status().isConflict()));

            assertThat(refused.get("code").asText()).isEqualTo("PLAYER_STATE_CONFLICT");
            assertThat(statusOf(busy)).isEqualTo(state);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM season_closures WHERE player_id = ?", Long.class, busy)).isZero();
        }
    }

    @Test
    void twoSimultaneousClosuresOfTheSamePlayerLeaveExactlyOne() throws Exception {
        Scene s = scene();
        JsonNode report = generate(s.coordinator(), s.player(), s.season(), null);
        Map<String, Object> request = Map.of("seasonId", s.season(), "reportId", report.get("id").asText());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return postJson("/players/" + s.player() + "/season-closure", request, s.coordinator().token())
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(statuses.stream().filter(code -> code == 201).count()).isEqualTo(1L);
            assertThat(statuses.stream().filter(code -> code == 409).count()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_closures WHERE player_id = ?", Long.class, s.player())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM state_history WHERE entity_id = ?", Long.class, s.player())).isEqualTo(1L);
    }

    // ---------- reapertura ----------

    @Test
    void reopeningNeedsReasonDetailAndTheReinforcedConfirmationAndKeepsTheWholeHistory() throws Exception {
        Scene s = scene();
        JsonNode report = generate(s.coordinator(), s.player(), s.season(), null);
        JsonNode closed = body(postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", report.get("id").asText()), s.coordinator().token())
                .andExpect(status().isCreated()));
        String path = "/players/" + s.player() + "/season-closure/reopen";

        // Todo en blanco, o la confirmación equivocada: 422 con los errores acumulados.
        assertThat(fields(body(postJson(path, Map.of("reason", " ", "detail", "", "confirmation", ""), s.coordinator().token())
                .andExpect(status().isUnprocessableEntity())))).containsExactlyInAnyOrder("reason", "detail", "confirmation");
        JsonNode wrong = body(postJson(path, Map.of("reason", "Error de captura", "detail", "Se corrige la asistencia del 9 de marzo",
                "confirmation", "JG-999999"), s.coordinator().token()).andExpect(status().isUnprocessableEntity()));
        assertThat(fields(wrong)).containsExactly("confirmation");
        assertThat(wrong.get("errors").get(0).get("message").asText()).contains(s.playerCode());
        assertThat(statusOf(s.player())).isEqualTo("SEASON_CLOSED");

        JsonNode reopened = body(postJson(path, Map.of("reason", "Error de captura", "detail", "Se corrige la asistencia del 9 de marzo",
                "confirmation", s.playerCode().toLowerCase()), s.coordinator().token()).andExpect(status().isOk()));

        assertThat(reopened.get("id").asText()).isEqualTo(closed.get("id").asText());
        assertThat(reopened.get("open").asBoolean()).isFalse();
        assertThat(reopened.get("reopenReason").asText()).isEqualTo("Error de captura");
        assertThat(reopened.get("reopenedBy").asText()).isEqualTo(s.coordinator().id().toString());
        assertThat(statusOf(s.player())).isEqualTo("ACTIVE");
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT transition_code, reason FROM state_history WHERE entity_id = ? ORDER BY occurred_at, transition_code", s.player());
        assertThat(history).extracting(row -> row.get("transition_code")).containsExactly("T-11", "T-12");
        assertThat(history.get(1).get("reason")).isEqualTo("Error de captura");
        assertThat(audits("PLAYER_SEASON_REOPENED", s.player())).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT reason FROM audit_events WHERE action = 'PLAYER_SEASON_REOPENED' AND entity_id = ?",
                String.class, s.player())).isEqualTo("Error de captura");

        // Nada se borró: el cierre sigue ahí, marcado como reabierto, y no hay nada más que reabrir.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_closures WHERE player_id = ?", Long.class, s.player())).isEqualTo(1L);
        postJson(path, Map.of("reason", "Otra vez", "detail", "No procede", "confirmation", s.playerCode()), s.coordinator().token())
                .andExpect(status().isConflict());

        // El expediente puede volver a cerrarse con el mismo informe, mientras siga vigente y sin cambios.
        postJson("/players/" + s.player() + "/season-closure",
                Map.of("seasonId", s.season(), "reportId", report.get("id").asText()), s.coordinator().token())
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM season_closures WHERE player_id = ?", Long.class, s.player())).isEqualTo(2L);
    }

    @Test
    void reopeningAPlayerThatWasNeverClosedIsAConflictAndAnUnknownPlayerIsNotFound() throws Exception {
        Scene s = scene();

        postJson("/players/" + s.player() + "/season-closure/reopen",
                Map.of("reason", "x", "detail", "y", "confirmation", s.playerCode()), s.coordinator().token())
                .andExpect(status().isConflict());
        getJson("/players/" + s.player() + "/season-closure", s.coordinator().token()).andExpect(status().isNotFound());
        postJson("/players/" + UUID.randomUUID() + "/season-closure/reopen",
                Map.of("reason", "x", "detail", "y", "confirmation", "JG-1"), s.coordinator().token())
                .andExpect(status().isNotFound());
    }

    // ---------- acceso ----------

    @Test
    void onlyTheCoordinatorGeneratesClosesAndReopensWhileTheCommitteeCanOnlyRead() throws Exception {
        Scene s = scene();
        JsonNode report = generate(s.coordinator(), s.player(), s.season(), null);
        String reportId = report.get("id").asText();
        Map<String, Object> generate = Map.of("seasonId", s.season());
        Map<String, Object> close = Map.of("seasonId", s.season(), "reportId", reportId);
        Map<String, Object> reopen = Map.of("reason", "x", "detail", "y", "confirmation", s.playerCode());

        postJson("/players/" + s.player() + "/season-reports", generate, null).andExpect(status().isUnauthorized());
        getJson("/season-reports/" + reportId, null).andExpect(status().isUnauthorized());
        getJson("/season-reports/verify?code=AAAA-AAAA-AAAA", null).andExpect(status().isUnauthorized());

        for (String role : List.of("GUARDIAN", "COACH", "EVALUATOR", "COMMITTEE")) {
            String token = staff(role).token();
            postJson("/players/" + s.player() + "/season-reports", generate, token).andExpect(status().isForbidden());
            postJson("/players/" + s.player() + "/season-closure", close, token).andExpect(status().isForbidden());
            postJson("/players/" + s.player() + "/season-closure/reopen", reopen, token).andExpect(status().isForbidden());
        }
        for (String role : List.of("GUARDIAN", "COACH", "EVALUATOR")) {
            String token = staff(role).token();
            getJson("/players/" + s.player() + "/season-reports", token).andExpect(status().isForbidden());
            getJson("/season-reports/" + reportId, token).andExpect(status().isForbidden());
            getJson("/season-reports/verify?code=" + report.get("verificationCode").asText(), token).andExpect(status().isForbidden());
            getJson("/players/" + s.player() + "/season-closure", token).andExpect(status().isForbidden());
        }
        String committee = staff("COMMITTEE").token();
        getJson("/players/" + s.player() + "/season-reports", committee).andExpect(status().isOk());
        getJson("/season-reports/" + reportId, committee).andExpect(status().isOk());
        getJson("/season-reports/verify?code=" + report.get("verificationCode").asText(), committee).andExpect(status().isOk());
        assertThat(statusOf(s.player())).isEqualTo("ACTIVE");
    }
}
