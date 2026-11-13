package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Exportaciones CSV del ERS 7.3 (plantilla, asistencia, convocatorias y resultados, seguimiento de evaluación) contra
 * PostgreSQL real. Cada prueba arma su propia categoría (con años de nacimiento propios) y filtra por ella, de modo que
 * los datos de otras pruebas no entran en los archivos. Los eventos caen en febrero de 2030.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReportExportsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final String BOM = "﻿";
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(8000);

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

    private MockHttpServletResponse download(String path, String bearer) throws Exception {
        return getJson(path, bearer).andExpect(status().isOk()).andReturn().getResponse();
    }

    /** Líneas del CSV sin la marca BOM y sin la línea final vacía. */
    private static List<String> lines(MockHttpServletResponse response) {
        String csv = new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith(BOM);
        return List.of(csv.substring(BOM.length()).split("\r\n"));
    }

    private static List<String> fields(JsonNode problem) {
        return StreamSupport.stream(problem.get("errors").spliterator(), false).map(e -> e.get("field").asText()).toList();
    }

    private record Club(Account coordinator, Account coach, UUID season, UUID category, String categoryName, UUID venue,
                        String venueName, UUID ana, UUID luis) {
    }

    private Club club() throws Exception {
        Account coordinator = staff("COORDINATOR", "Coordinación");
        Account coach = staff("COACH", "Coach Uno");
        UUID season = jdbc.queryForObject("INSERT INTO seasons (name, start_at, end_at, status) VALUES (?, ?::timestamptz, ?::timestamptz, 'ACTIVE') RETURNING id",
                UUID.class, "Temporada " + UUID.randomUUID(), "2030-01-01T05:00:00Z", "2031-01-01T05:00:00Z");
        int year = NEXT_YEAR.getAndAdd(3);
        String categoryName = "Export " + UUID.randomUUID().toString().substring(0, 6);
        UUID category = jdbc.queryForObject("INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 20) RETURNING id",
                UUID.class, categoryName, year, year + 1);
        String venueName = "Cancha " + UUID.randomUUID().toString().substring(0, 6);
        UUID venue = jdbc.queryForObject("INSERT INTO venues (name) VALUES (?) RETURNING id", UUID.class, venueName);
        UUID ana = player(category, coach.id(), "Ana", "Ruiz", "=Madre Ruiz");
        UUID luis = player(category, coach.id(), "Luis", "Paz", "Padre Paz");
        return new Club(coordinator, coach, season, category, categoryName, venue, venueName, ana, luis);
    }

    private UUID player(UUID category, UUID coach, String firstName, String lastName, String guardianName) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES (?, ?, 'TI', ?, DATE '2012-03-01', 'ACTIVE') RETURNING id
                """, UUID.class, firstName, lastName, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                """, UUID.class, "exports-it/" + UUID.randomUUID());
        UUID minute = jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                """, UUID.class, "EX-" + UUID.randomUUID().toString().substring(0, 8), player, file);
        jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                player, category, coach, minute);
        UUID guardianUser = user("guardian-" + UUID.randomUUID() + "@familia.test", guardianName, "GUARDIAN");
        UUID guardian = jdbc.queryForObject("INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                UUID.class, guardianUser, "cc-" + UUID.randomUUID().toString().substring(0, 20));
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", player, guardian);
        return player;
    }

    private UUID event(Club club, String type, String status, String start) {
        return jdbc.queryForObject("""
                INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at, status, cancellation_reason)
                VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz + interval '1 hour', ?, CASE WHEN ? = 'CANCELLED' THEN 'lluvia' END)
                RETURNING id
                """, UUID.class, type, club.category(), club.venue(), start, start, status, status);
    }

    /** Sesión con las marcas dadas (jugador → estado); se cierra al final porque el trigger impide marcar una sesión cerrada. */
    private void session(UUID event, Club club, boolean close, Map<UUID, String> marks) {
        jdbc.update("INSERT INTO attendance_sessions (event_id) VALUES (?)", event);
        marks.forEach((player, status) -> jdbc.update("""
                INSERT INTO attendance_records (event_id, player_id, status, excuse_accepted, client_uuid, recorded_by)
                VALUES (?, ?, ?, ?, gen_random_uuid(), ?)
                """, event, player, status, "EXCUSED".equals(status), club.coach().id()));
        if (close) {
            jdbc.update("UPDATE attendance_sessions SET closed_at = now(), closed_by = ? WHERE event_id = ?", club.coach().id(), event);
        }
    }

    private UUID match(UUID event, UUID tournament, String opponent, Integer scoreFor, Integer scoreAgainst) {
        return jdbc.queryForObject("""
                INSERT INTO matches (event_id, tournament_id, opponent, score_for, score_against, result_recorded_at)
                VALUES (?, ?, ?, ?, ?, CASE WHEN ?::int IS NULL THEN NULL ELSE now() END) RETURNING id
                """, UUID.class, event, tournament, opponent, scoreFor, scoreAgainst, scoreFor);
    }

    private void callup(UUID match, UUID player, String status, UUID by, Integer minutes, Integer goals) {
        jdbc.update("""
                INSERT INTO callup_entries (match_id, player_id, status, proposed_by, confirmed_by, confirmed_at, minutes_played, goals)
                VALUES (?, ?, ?, ?, CASE WHEN ? = 'CONFIRMED' THEN ?::uuid END, CASE WHEN ? = 'CONFIRMED' THEN now() END, ?, ?)
                """, match, player, status, by, status, by, status, minutes, goals);
    }

    // ---------- plantilla ----------

    @Test
    void theRosterExportListsTheCategorySquadWithoutIdentityData() throws Exception {
        Club club = club();

        MockHttpServletResponse response = download("/reports/roster/export?categoryId=" + club.category(), club.coordinator().token());

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").contains("plantilla-");
        assertThat(response.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        List<String> lines = lines(response);
        assertThat(lines.get(0)).isEqualTo("Categoría,Código,Jugador,Fecha de nacimiento,Estado,Entrenador titular,Acudiente principal,En la categoría desde");
        assertThat(lines).hasSize(3);
        // orden por apellido: Paz antes que Ruiz
        assertThat(lines.get(1)).startsWith(club.categoryName() + ",JG-").contains(",Luis Paz,2012-03-01,ACTIVE,Coach Uno,Padre Paz,");
        // el nombre del acudiente lo escribe un acudiente: una fórmula no llega viva al Excel
        assertThat(lines.get(2)).contains(",Ana Ruiz,2012-03-01,ACTIVE,Coach Uno,'=Madre Ruiz,");
        assertThat(String.join("\n", lines)).doesNotContain("TI-");

        Map<String, Object> trace = jdbc.queryForMap("""
                SELECT actor_role, after_state ->> 'report' AS report, after_state ->> 'rows' AS rows, after_state ->> 'categoryId' AS category_id
                FROM audit_events WHERE action = 'REPORT_EXPORTED' AND actor_id = ?
                """, club.coordinator().id());
        assertThat(trace).containsEntry("actor_role", "COORDINATOR").containsEntry("report", "roster")
                .containsEntry("rows", "2").containsEntry("category_id", club.category().toString());
    }

    // ---------- asistencia ----------

    @Test
    void theAttendanceExportCountsOnlyClosedSessionsOfActiveEventsInTheWindow() throws Exception {
        Club club = club();
        // 22:00 UTC = 17:00 en Bogotá
        UUID closed = event(club, "TRAINING", "COMPLETED", "2030-02-04T22:00:00Z");
        session(closed, club, true, Map.of(club.ana(), "PRESENT", club.luis(), "EXCUSED"));
        UUID cancelled = event(club, "TRAINING", "CANCELLED", "2030-02-06T22:00:00Z");
        session(cancelled, club, true, Map.of(club.ana(), "ABSENT"));
        UUID open = event(club, "TRAINING", "CONFIRMED", "2030-02-08T22:00:00Z");
        session(open, club, false, Map.of(club.ana(), "PRESENT"));
        UUID march = event(club, "TRAINING", "COMPLETED", "2030-03-04T22:00:00Z");
        session(march, club, true, Map.of(club.ana(), "PRESENT"));

        MockHttpServletResponse response = download("/reports/attendance/export?categoryId=" + club.category()
                + "&from=2030-02-01&to=2030-02-28", club.coordinator().token());

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("asistencia-2030-02-01_2030-02-28.csv");
        List<String> lines = lines(response);
        assertThat(lines.get(0)).isEqualTo("Fecha,Hora,Tipo,Categoría,Escenario,Código,Jugador,Asistencia,Excusa aceptada");
        assertThat(lines).hasSize(3);
        String prefix = "2030-02-04,17:00,TRAINING," + club.categoryName() + "," + club.venueName() + ",JG-";
        assertThat(lines.get(1)).startsWith(prefix).endsWith(",Luis Paz,EXCUSED,Sí");
        assertThat(lines.get(2)).startsWith(prefix).endsWith(",Ana Ruiz,PRESENT,No");

        // la misma categoría con la temporada completa suma marzo
        List<String> season = lines(download("/reports/attendance/export?categoryId=" + club.category() + "&seasonId=" + club.season(),
                club.coordinator().token()));
        assertThat(season).hasSize(4);
    }

    @Test
    void anExportOutsideAnyActivityHasOnlyTheHeader() throws Exception {
        Club club = club();
        List<String> lines = lines(download("/reports/attendance/export?categoryId=" + club.category()
                + "&from=2031-06-01&to=2031-06-30", club.coordinator().token()));
        assertThat(lines).hasSize(1);
    }

    // ---------- convocatorias y resultados ----------

    @Test
    void theMatchesExportListsCalledPlayersWithResultsAndSkipsRemovedOnes() throws Exception {
        Club club = club();
        UUID tournament = jdbc.queryForObject("INSERT INTO tournaments (season_id, name) VALUES (?, ?) RETURNING id", UUID.class,
                club.season(), "Copa " + UUID.randomUUID().toString().substring(0, 6));
        String tournamentName = jdbc.queryForObject("SELECT name FROM tournaments WHERE id = ?", String.class, tournament);
        // 14:30 UTC = 09:30 en Bogotá
        UUID played = match(event(club, "TOURNAMENT_MATCH", "COMPLETED", "2030-02-10T14:30:00Z"), tournament, "Rival FC", 3, 1);
        callup(played, club.ana(), "CONFIRMED", club.coach().id(), 60, 2);
        callup(played, club.luis(), "REMOVED", club.coach().id(), null, null);
        match(event(club, "FRIENDLY_MATCH", "CONFIRMED", "2030-02-17T14:30:00Z"), null, "Otro FC", null, null);

        List<String> lines = lines(download("/reports/matches/export?categoryId=" + club.category() + "&from=2030-02-01&to=2030-02-28",
                club.coordinator().token()));

        assertThat(lines.get(0)).isEqualTo("Fecha,Hora,Categoría,Tipo,Torneo,Rival,Escenario,Estado del partido,Puntos a favor,Puntos en contra,Código,Jugador,Convocatoria,Minutos,Puntos");
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith("2030-02-10,09:30," + club.categoryName() + ",TOURNAMENT_MATCH," + tournamentName
                + ",Rival FC," + club.venueName() + ",COMPLETED,3,1,JG-").endsWith(",Ana Ruiz,CONFIRMED,60,2");
        assertThat(lines.get(2)).isEqualTo("2030-02-17,09:30," + club.categoryName() + ",FRIENDLY_MATCH,,Otro FC," + club.venueName()
                + ",CONFIRMED,,,,,,,");

        // filtrar por torneo deja solo el partido del torneo
        List<String> onlyTournament = lines(download("/reports/matches/export?categoryId=" + club.category() + "&tournamentId=" + tournament
                + "&seasonId=" + club.season(), club.coordinator().token()));
        assertThat(onlyTournament).hasSize(2);
        assertThat(onlyTournament.get(1)).contains("Rival FC");
    }

    // ---------- seguimiento de evaluación ----------

    @Test
    void theEvaluationExportCountsOnlyTheConceptsOfTheCurrentPeriod() throws Exception {
        Club club = club();
        Account first = staff("EVALUATOR", "Evaluadora Uno");
        Account second = staff("EVALUATOR", "Evaluador Dos");
        UUID round = jdbc.queryForObject("""
                INSERT INTO evaluation_rounds (player_id, period_start, period_end, due_at, status, opened_by)
                VALUES (?, DATE '2030-02-01', DATE '2030-02-28', '2030-03-05T05:00:00Z', 'OPEN', ?) RETURNING id
                """, UUID.class, club.ana(), club.coordinator().id());
        for (Account evaluator : List.of(first, second)) {
            jdbc.update("""
                    INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, period_start, period_end)
                    VALUES (?, ?, 'FAVORABLE', DATE '2030-02-01', DATE '2030-02-28')
                    """, round, evaluator.id());
        }
        // un concepto emitido sobre otro periodo (cambio de periodo, RN-09) no cuenta
        jdbc.update("""
                INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, justification, period_start, period_end)
                VALUES (?, ?, 'UNFAVORABLE', 'x', DATE '2030-01-01', DATE '2030-01-31')
                """, round, club.coach().id());
        jdbc.update("UPDATE evaluation_rounds SET status = 'CLOSED', result = 'APPROVED', closed_at = '2030-03-04T15:00:00Z' WHERE id = ?", round);

        MockHttpServletResponse response = download("/reports/evaluations/export?categoryId=" + club.category()
                + "&from=2030-02-01&to=2030-02-28", club.coordinator().token());

        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("evaluaciones-2030-02-01_2030-02-28.csv");
        List<String> lines = lines(response);
        assertThat(lines.get(0)).isEqualTo("Categoría,Código,Jugador,Periodo desde,Periodo hasta,Vence,Estado de la ronda,Resultado,Fecha de cierre,Conceptos emitidos,Favorables,Seguimiento,Desfavorables");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).startsWith(club.categoryName() + ",JG-").endsWith(",Ana Ruiz,2030-02-01,2030-02-28,2030-03-05,CLOSED,APPROVED,2030-03-04,2,2,0,0");
        // no se publica quién dio cada concepto
        assertThat(String.join("\n", lines)).doesNotContain("Evaluadora Uno").doesNotContain("Evaluador Dos");

        // un periodo que no toca la ronda no la lista
        assertThat(lines(download("/reports/evaluations/export?categoryId=" + club.category() + "&from=2030-04-01&to=2030-04-30",
                club.coordinator().token()))).hasSize(1);
    }

    // ---------- validación, acceso y auditoría ----------

    @Test
    void invalidParametersAreReportedTogetherAndLeaveNoTrace() throws Exception {
        Account coordinator = staff("COORDINATOR", "Coordinación");

        JsonNode noDates = body(getJson("/reports/attendance/export", coordinator.token()).andExpect(status().isUnprocessableEntity()));
        assertThat(fields(noDates)).containsExactly("from", "to");

        JsonNode unknown = body(getJson("/reports/matches/export?seasonId=" + UUID.randomUUID() + "&categoryId=" + UUID.randomUUID()
                + "&tournamentId=" + UUID.randomUUID(), coordinator.token()).andExpect(status().isUnprocessableEntity()));
        assertThat(fields(unknown)).containsExactlyInAnyOrder("seasonId", "categoryId", "tournamentId");

        JsonNode backwards = body(getJson("/reports/evaluations/export?from=2030-03-01&to=2030-02-01", coordinator.token())
                .andExpect(status().isUnprocessableEntity()));
        assertThat(fields(backwards)).containsExactly("to");

        JsonNode tooLong = body(getJson("/reports/attendance/export?from=2030-01-01&to=2031-06-01", coordinator.token())
                .andExpect(status().isUnprocessableEntity()));
        assertThat(fields(tooLong)).containsExactly("to");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REPORT_EXPORTED' AND actor_id = ?",
                Long.class, coordinator.id())).isZero();
    }

    @Test
    void onlyCoordinatorAndCommitteeCanDownloadAndTheCommitteeIsAuditedWithItsOwnRole() throws Exception {
        List<String> paths = List.of("/reports/roster/export", "/reports/attendance/export?from=2030-02-01&to=2030-02-28",
                "/reports/matches/export?from=2030-02-01&to=2030-02-28", "/reports/evaluations/export?from=2030-02-01&to=2030-02-28");
        for (String path : paths) {
            getJson(path, null).andExpect(status().isUnauthorized());
            for (String role : List.of("GUARDIAN", "COACH", "EVALUATOR")) {
                getJson(path, staff(role, "Sin acceso").token()).andExpect(status().isForbidden());
            }
        }
        Account committee = staff("COMMITTEE", "Comité");
        for (String path : paths) {
            download(path, committee.token());
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT actor_role FROM audit_events WHERE action = 'REPORT_EXPORTED' AND actor_id = ?",
                String.class, committee.id())).containsExactly("COMMITTEE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'REPORT_EXPORTED' AND actor_id = ?",
                Long.class, committee.id())).isEqualTo(4L);
    }
}
