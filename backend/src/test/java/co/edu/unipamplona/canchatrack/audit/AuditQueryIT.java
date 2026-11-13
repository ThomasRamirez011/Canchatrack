package co.edu.unipamplona.canchatrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

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

/** Consulta de auditoría (CU-14) contra PostgreSQL real: acceso, filtros, paginación por cursor y rastro de la consulta. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuditQueryIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";

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

    private Account staff(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        String token = body(send(post(API + "/auth/login"), Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isOk())).get("accessToken").asText();
        return new Account(id, token);
    }

    /** Inserta un evento con fecha fija (la bitácora es append-only: no hay forma de limpiarla, por eso cada prueba usa claves únicas). */
    private UUID event(String action, UUID entityId, UUID actorId, String outcome, String occurredAt) {
        return jdbc.queryForObject("""
                INSERT INTO audit_events (occurred_at, actor_id, actor_role, action, entity_type, entity_id, outcome,
                                          before_state, after_state, reason, ip, user_agent, correlation_id)
                VALUES (?::timestamptz, ?, 'COORDINATOR', ?, 'it_entity', ?, ?, '{"status":"ACTIVE"}'::jsonb,
                        '{"status":"SUSPENDED"}'::jsonb, 'motivo de prueba', '10.1.2.3'::inet, 'AgenteSecreto/1.0', 'corr-it-1234')
                RETURNING id
                """, UUID.class, occurredAt, actorId, action, entityId, outcome);
    }

    private long queried(UUID actorId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'AUDIT_QUERIED' AND actor_id = ?",
                Long.class, actorId);
    }

    private static List<String> ids(JsonNode page) {
        return StreamSupport.stream(page.get("items").spliterator(), false).map(i -> i.get("id").asText()).toList();
    }

    // ---------- pruebas ----------

    @Test
    void onlyTheCoordinatorCanConsultTheAudit() throws Exception {
        getJson("/audit/events", null).andExpect(status().isUnauthorized());
        for (String role : List.of("GUARDIAN", "COMMITTEE", "COACH", "EVALUATOR")) {
            getJson("/audit/events", staff(role).token()).andExpect(status().isForbidden());
        }
        getJson("/audit/events?limit=1", staff("COORDINATOR").token()).andExpect(status().isOk());
    }

    @Test
    void filtersNarrowTheResultAndEventsComeNewestFirst() throws Exception {
        Account coordinator = staff("COORDINATOR");
        String action = "IT_ACTION_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID entity = UUID.randomUUID();
        UUID otherEntity = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, full_name, password_hash) VALUES (?, ?, 'Otro actor', 'x')", actor,
                "actor-" + actor + "@club.test");
        UUID oldest = event(action, entity, null, "SUCCESS", "2031-01-01T10:00:00Z");
        UUID middle = event(action, entity, actor, "DENIED", "2031-01-01T11:00:00Z");
        UUID newest = event(action, otherEntity, actor, "SUCCESS", "2031-01-01T12:00:00Z");
        event("IT_OTHER_" + UUID.randomUUID(), entity, null, "SUCCESS", "2031-01-01T13:00:00Z");

        // Por acción: los tres, del más reciente al más antiguo.
        assertThat(ids(body(getJson("/audit/events?action=" + action, coordinator.token()).andExpect(status().isOk()))))
                .containsExactly(newest.toString(), middle.toString(), oldest.toString());
        // Por entidad y por actor, por resultado.
        assertThat(ids(body(getJson("/audit/events?action=" + action + "&entityType=it_entity&entityId=" + entity,
                coordinator.token()).andExpect(status().isOk())))).containsExactly(middle.toString(), oldest.toString());
        assertThat(ids(body(getJson("/audit/events?action=" + action + "&actorId=" + actor, coordinator.token())
                .andExpect(status().isOk())))).containsExactly(newest.toString(), middle.toString());
        assertThat(ids(body(getJson("/audit/events?action=" + action + "&outcome=denied", coordinator.token())
                .andExpect(status().isOk())))).containsExactly(middle.toString());
        // Ventana semiabierta: `from` incluye el límite, `to` lo excluye.
        assertThat(ids(body(getJson("/audit/events?action=" + action + "&from=2031-01-01T11:00:00Z&to=2031-01-01T12:00:00Z",
                coordinator.token()).andExpect(status().isOk())))).containsExactly(middle.toString());
        // Sin coincidencias: página vacía y sin cursor.
        JsonNode empty = body(getJson("/audit/events?action=NO_EXISTE_" + UUID.randomUUID(), coordinator.token())
                .andExpect(status().isOk()));
        assertThat(empty.get("items")).isEmpty();
        assertThat(empty.has("nextCursor")).isFalse();
    }

    @Test
    void theResponseCarriesSafeSummariesAndNoTechnicalOrigin() throws Exception {
        Account coordinator = staff("COORDINATOR");
        String action = "IT_SHAPE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        event(action, UUID.randomUUID(), null, "SUCCESS", "2031-02-01T10:00:00Z");

        JsonNode item = body(getJson("/audit/events?action=" + action, coordinator.token()).andExpect(status().isOk()))
                .get("items").get(0);

        assertThat(item.get("action").asText()).isEqualTo(action);
        assertThat(item.get("actorRole").asText()).isEqualTo("COORDINATOR");
        assertThat(item.get("before").get("status").asText()).isEqualTo("ACTIVE");
        assertThat(item.get("after").get("status").asText()).isEqualTo("SUSPENDED");
        assertThat(item.get("reason").asText()).isEqualTo("motivo de prueba");
        assertThat(item.get("correlationId").asText()).isEqualTo("corr-it-1234");
        assertThat(item.has("actorId")).as("un evento del sistema no inventa actor").isFalse();
        assertThat(item.has("ip")).isFalse();
        assertThat(item.has("userAgent")).isFalse();
        assertThat(item.toString()).doesNotContain("10.1.2.3").doesNotContain("AgenteSecreto");
    }

    @Test
    void cursorPaginationWalksEveryEventOnceEvenWithEqualTimestamps() throws Exception {
        Account coordinator = staff("COORDINATOR");
        String action = "IT_PAGE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID entity = UUID.randomUUID();
        // Tres eventos comparten la misma marca de tiempo: el desempate por id evita saltos y repeticiones.
        event(action, entity, null, "SUCCESS", "2031-03-01T10:00:00Z");
        event(action, entity, null, "SUCCESS", "2031-03-01T11:00:00Z");
        event(action, entity, null, "SUCCESS", "2031-03-01T11:00:00Z");
        event(action, entity, null, "SUCCESS", "2031-03-01T11:00:00Z");
        event(action, entity, null, "SUCCESS", "2031-03-01T12:00:00Z");

        List<String> walked = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            String path = "/audit/events?limit=2&action=" + action + (cursor == null ? "" : "&cursor=" + cursor);
            JsonNode page = body(getJson(path, coordinator.token()).andExpect(status().isOk()));
            walked.addAll(ids(page));
            pageSizes.add(page.get("items").size());
            cursor = page.has("nextCursor") ? page.get("nextCursor").asText() : null;
        } while (cursor != null);

        List<String> expected = jdbc.queryForList(
                "SELECT id::text FROM audit_events WHERE action = ? ORDER BY occurred_at DESC, id DESC", String.class, action);
        assertThat(pageSizes).containsExactly(2, 2, 1);
        assertThat(walked).hasSize(5).doesNotHaveDuplicates().containsExactlyElementsOf(expected);
    }

    @Test
    void theFirstPageOfEachSearchIsAuditedAndTheFollowingPagesAreNot() throws Exception {
        Account coordinator = staff("COORDINATOR");
        String action = "IT_TRACE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        UUID entity = UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            event(action, entity, null, "SUCCESS", "2031-04-01T1" + i + ":00:00Z");
        }
        assertThat(queried(coordinator.id())).isZero();

        JsonNode first = body(getJson("/audit/events?limit=2&action=" + action + "&entityId=" + entity, coordinator.token())
                .andExpect(status().isOk()));
        assertThat(queried(coordinator.id())).isEqualTo(1);

        Map<String, Object> trace = jdbc.queryForMap("""
                SELECT actor_role, entity_type, after_state ->> 'action' AS action, after_state ->> 'entityId' AS entity_id,
                       before_state AS before_state
                FROM audit_events WHERE action = 'AUDIT_QUERIED' AND actor_id = ?
                """, coordinator.id());
        assertThat(trace).containsEntry("actor_role", "COORDINATOR").containsEntry("entity_type", "audit")
                .containsEntry("action", action).containsEntry("entity_id", entity.toString());
        assertThat(trace.get("before_state")).isNull();

        // Pedir la segunda página continúa la misma búsqueda: no genera otro rastro.
        getJson("/audit/events?limit=2&action=" + action + "&entityId=" + entity + "&cursor=" + first.get("nextCursor").asText(),
                coordinator.token()).andExpect(status().isOk());
        assertThat(queried(coordinator.id())).isEqualTo(1);
    }

    @Test
    void invalidParametersAreRejectedTogetherAndLeaveNoTrace() throws Exception {
        Account coordinator = staff("COORDINATOR");

        JsonNode problem = body(getJson("/audit/events?outcome=MAYBE&limit=0&cursor=basura&from=2031-05-02T00:00:00Z&to=2031-05-01T00:00:00Z",
                coordinator.token()).andExpect(status().isUnprocessableEntity()));

        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        Set<String> fields = StreamSupport.stream(problem.get("errors").spliterator(), false)
                .map(e -> e.get("field").asText()).collect(Collectors.toSet());
        assertThat(fields).containsExactlyInAnyOrder("outcome", "limit", "cursor", "to");
        assertThat(queried(coordinator.id())).isZero();

        // Un identificador mal formado lo rechaza el propio framework antes de llegar al caso de uso.
        getJson("/audit/events?entityId=no-es-uuid", coordinator.token()).andExpect(status().isBadRequest());
        getJson("/audit/events?limit=201", coordinator.token()).andExpect(status().isUnprocessableEntity());
    }
}
