package co.edu.unipamplona.canchatrack.players;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

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
 * Categorías con rango de nacimiento y cupo contra PostgreSQL real: administración por API, restricción de exclusión de
 * rangos solapados (V14) y sugerencia por año de nacimiento (RF-046). Las pruebas no son transaccionales y comparten la
 * base, así que cada categoría usa años propios (no solapados) para no estorbarse entre sí.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CategoriesIT {

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

    private ResultActions patchJson(String path, Object body, String bearer) throws Exception {
        return send(patch(API + path), body, bearer);
    }

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String login(String email) throws Exception {
        return body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null)
                .andExpect(status().isOk())).get("accessToken").asText();
    }

    private String staffToken(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return login(email);
    }

    private UUID userIdOf(String token) throws Exception {
        return UUID.fromString(body(getJson("/auth/me", token).andExpect(status().isOk())).get("id").asText());
    }

    private record Guardian(String token, UUID userId, UUID guardianId) {
    }

    private Guardian registerGuardian() throws Exception {
        String email = "g-" + UUID.randomUUID() + "@club.test";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", email);
        request.put("fullName", "Ana Acudiente");
        request.put("password", PASSWORD);
        request.put("documentType", "CC");
        request.put("documentNumber", String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        UUID userId = UUID.fromString(body(postJson("/guardians", request, null).andExpect(status().isCreated())).get("id").asText());
        UUID guardianId = jdbc.queryForObject("SELECT id FROM guardians WHERE user_id = ?", UUID.class, userId);
        return new Guardian(login(email), userId, guardianId);
    }

    /** Años de nacimiento exclusivos por categoría: dos años por bloque, sin cruces entre pruebas. */
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(1950);

    private static int[] freshRange() {
        int first = NEXT_YEAR.getAndAdd(3);
        return new int[] {first, first + 1};
    }

    private Map<String, Object> categoryRequest(String name, int[] years, int capacity) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("birthYearMin", years[0]);
        request.put("birthYearMax", years[1]);
        request.put("capacity", capacity);
        return request;
    }

    private String createCategory(String coordinator, int[] years, int capacity) throws Exception {
        return body(postJson("/categories", categoryRequest("Cat " + UUID.randomUUID(), years, capacity), coordinator)
                .andExpect(status().isCreated())).get("id").asText();
    }

    private UUID newPlayer(int birthYear) {
        return jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date)
                VALUES ('Juan', 'Pérez', 'TI', ?, make_date(?, 6, 1)) RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), birthYear);
    }

    private long countAudit(String action, UUID actor) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND actor_id = ?",
                Long.class, action, actor);
        return total == null ? 0 : total;
    }

    @Test
    void onlyTheCoordinatorWritesAndOnlyCoordinatorAndCommitteeRead() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        int[] years = freshRange();
        String categoryId = createCategory(coordinator, years, 20);
        Map<String, Object> request = categoryRequest("Otra", freshRange(), 10);

        postJson("/categories", request, null).andExpect(status().isUnauthorized());
        getJson("/categories", null).andExpect(status().isUnauthorized());

        for (String token : new String[] {registerGuardian().token(), staffToken("COACH"), staffToken("EVALUATOR")}) {
            postJson("/categories", request, token).andExpect(status().isForbidden());
            patchJson("/categories/" + categoryId, Map.of("capacity", 30), token).andExpect(status().isForbidden());
            getJson("/categories", token).andExpect(status().isForbidden());
            getJson("/categories/" + categoryId, token).andExpect(status().isForbidden());
            getJson("/players/" + newPlayer(years[0]) + "/category-suggestion", token).andExpect(status().isForbidden());
        }
        String committee = staffToken("COMMITTEE");
        postJson("/categories", request, committee).andExpect(status().isForbidden());
        patchJson("/categories/" + categoryId, Map.of("capacity", 30), committee).andExpect(status().isForbidden());
        getJson("/categories", committee).andExpect(status().isOk());
        getJson("/categories/" + categoryId, committee).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT capacity FROM categories WHERE id = ?::uuid", Integer.class, categoryId)).isEqualTo(20);
    }

    @Test
    void aCategoryIsCreatedWithItsCapacityAndNothingOccupied() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID coordinatorId = userIdOf(coordinator);
        int[] years = freshRange();

        postJson("/categories", categoryRequest("Sub-12 " + UUID.randomUUID(), years, 18), coordinator)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.birthYearMin").value(years[0]))
                .andExpect(jsonPath("$.birthYearMax").value(years[1]))
                .andExpect(jsonPath("$.capacity").value(18))
                .andExpect(jsonPath("$.occupied").value(0))
                .andExpect(jsonPath("$.available").value(18))
                .andExpect(jsonPath("$.active").value(true));

        assertThat(countAudit("CATEGORY_CREATED", coordinatorId)).isEqualTo(1L);
    }

    @Test
    void invalidDataDuplicatesAndOverlapsAreReportedAsFieldErrors() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        int[] years = freshRange();
        String name = "Cat " + UUID.randomUUID();
        postJson("/categories", categoryRequest(name, years, 20), coordinator).andExpect(status().isCreated());

        postJson("/categories", categoryRequest(" ", new int[] {2016, 2015}, 0), coordinator)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'birthYearMax')]").isNotEmpty())
                .andExpect(jsonPath("$.errors[?(@.field == 'capacity')]").isNotEmpty());
        postJson("/categories", categoryRequest(name, freshRange(), 20), coordinator)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
        postJson("/categories", categoryRequest("Solapada " + UUID.randomUUID(), new int[] {years[1], years[1] + 1}, 20), coordinator)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'birthYearMin')]").isNotEmpty());
        postJson("/categories", Map.of("name", "Sin años"), coordinator).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theDatabaseItselfRefusesOverlappingActiveRangesEvenIfTheApplicationIsBypassed() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        int[] years = freshRange();
        createCategory(coordinator, years, 20);

        try {
            jdbc.update("INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 10)",
                    "Directa " + UUID.randomUUID(), years[0], years[1]);
            throw new AssertionError("la base debía rechazar el solape");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertThat(expected.getMostSpecificCause().getMessage().contains("ex_categories_active_birth_years")).isTrue();
        }
    }

    @Test
    void aCategoryIsUpdatedDeactivatedAndItsRangeReleased() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID coordinatorId = userIdOf(coordinator);
        int[] years = freshRange();
        String categoryId = createCategory(coordinator, years, 20);

        patchJson("/categories/" + categoryId, Map.of("capacity", 25, "name", "Renombrada " + UUID.randomUUID()), coordinator)
                .andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(25)).andExpect(jsonPath("$.available").value(25));
        patchJson("/categories/" + categoryId, Map.of(), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'body')]").isNotEmpty());

        patchJson("/categories/" + categoryId, Map.of("active", false), coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        String replacement = createCategory(coordinator, years, 12); // el rango quedó libre
        patchJson("/categories/" + categoryId, Map.of("active", true), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'birthYearMin')]").isNotEmpty());

        assertThat(body(getJson("/categories", coordinator).andExpect(status().isOk())).findValuesAsText("id"))
                .contains(replacement).doesNotContain(categoryId);
        assertThat(body(getJson("/categories?includeInactive=true", coordinator).andExpect(status().isOk())).findValuesAsText("id"))
                .contains(replacement, categoryId);
        assertThat(countAudit("CATEGORY_UPDATED", coordinatorId)).isEqualTo(2L);
    }

    @Test
    void theCapacityCannotDropBelowTheAssignedPlayersAndLeftPlayersDoNotCount() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        int[] years = freshRange();
        String categoryId = createCategory(coordinator, years, 20);
        UUID coach = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, 'Entrenador', ?) RETURNING id",
                UUID.class, "coach-" + UUID.randomUUID() + "@club.test", encoder.encode(PASSWORD));
        UUID minute = committeeMinute(coach);
        // dos jugadores asignados a la categoría y uno retirado, que ya no ocupa plaza
        for (String status : new String[] {"ACTIVE", "CATEGORY_ASSIGNED", "WITHDRAWN"}) {
            UUID player = newPlayer(years[0]);
            jdbc.update("UPDATE players SET status = ? WHERE id = ?", status, player);
            jdbc.update("""
                    INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id)
                    VALUES (?, ?::uuid, ?, 'COMMITTEE', ?)
                    """, player, categoryId, coach, minute);
        }

        getJson("/categories/" + categoryId, coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.occupied").value(2)).andExpect(jsonPath("$.available").value(18));
        patchJson("/categories/" + categoryId, Map.of("capacity", 1), coordinator).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'capacity')]").isNotEmpty());
        patchJson("/categories/" + categoryId, Map.of("capacity", 2), coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(0));
    }

    /** Acta mínima para poder asignar jugadores por SQL (el endpoint del comité llega en el siguiente incremento). */
    private UUID committeeMinute(UUID creator) {
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'acta.pdf', 'application/pdf', 10, ?) RETURNING id
                """, UUID.class, "k-" + UUID.randomUUID(), "a".repeat(64));
        UUID player = newPlayer(1990);
        return jdbc.queryForObject("""
                INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id, created_by)
                VALUES (?, current_date, ?, 'ASSIGNED', ?, ?) RETURNING id
                """, UUID.class, "ACTA-" + UUID.randomUUID().toString().substring(0, 8), player, file, creator);
    }

    @Test
    void theNextCategoryMustExistAndChainsCannotLoop() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        String a = createCategory(coordinator, freshRange(), 20);
        String b = createCategory(coordinator, freshRange(), 20);

        patchJson("/categories/" + a, Map.of("nextCategoryId", UUID.randomUUID()), coordinator)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[?(@.field == 'nextCategoryId')]").isNotEmpty());
        patchJson("/categories/" + a, Map.of("nextCategoryId", b), coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCategoryId").value(b));
        patchJson("/categories/" + b, Map.of("nextCategoryId", a), coordinator).andExpect(status().isUnprocessableEntity());
        patchJson("/categories/" + a, Map.of("clearNextCategory", true), coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextCategoryId").doesNotExist());
    }

    @Test
    void theSuggestionFollowsTheBirthYearAndShowsAvailability() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        String committee = staffToken("COMMITTEE");
        int[] years = freshRange();
        String categoryId = createCategory(coordinator, years, 15);

        getJson("/players/" + newPlayer(years[1]) + "/category-suggestion", committee).andExpect(status().isOk())
                .andExpect(jsonPath("$.birthYear").value(years[1]))
                .andExpect(jsonPath("$.suggested.id").value(categoryId))
                .andExpect(jsonPath("$.suggested.available").value(15));
        getJson("/players/" + newPlayer(years[1] + 1) + "/category-suggestion", coordinator).andExpect(status().isOk())
                .andExpect(jsonPath("$.suggested").doesNotExist());
        getJson("/players/" + UUID.randomUUID() + "/category-suggestion", coordinator).andExpect(status().isNotFound());
    }

    @Test
    void unknownCategoriesAreNotFound() throws Exception {
        String coordinator = staffToken("COORDINATOR");

        getJson("/categories/" + UUID.randomUUID(), coordinator).andExpect(status().isNotFound());
        patchJson("/categories/" + UUID.randomUUID(), Map.of("capacity", 5), coordinator).andExpect(status().isNotFound());
    }
}
