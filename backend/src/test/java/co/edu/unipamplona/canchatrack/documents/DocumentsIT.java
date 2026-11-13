package co.edu.unipamplona.canchatrack.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;
import co.edu.unipamplona.canchatrack.documents.application.ExpiryAlertService;

/**
 * Catálogo, ventanas, versiones inmutables, acceso a documentos de salud y alertas de vencimiento contra PostgreSQL real
 * (CU-13). La lógica fina está en {@code DocumentRulesTest} y {@code PlayerDocumentServiceTest}; aquí se comprueba lo que
 * solo la base y la seguridad real demuestran: los triggers de solo inserción, el cierre de la vía {@code /files} para
 * documentos de salud, la auditoría y la deduplicación de avisos.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DocumentsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final byte[] PDF = "%PDF-1.4 certificado medico".getBytes();
    private static final AtomicInteger NEXT_YEAR = new AtomicInteger(4200);

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ExpiryAlertService alerts;

    // ---------- utilidades ----------

    private record Account(UUID id, String email, String token) {
    }

    /** Un acudiente vinculado a un jugador y, opcionalmente, el entrenador titular de su categoría. */
    private record Family(UUID player, Account guardian, Account coach) {
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

    private Account account(String roleCode) throws Exception {
        String email = roleCode.toLowerCase() + "-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Usuario de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        String token = body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null).andExpect(status().isOk()))
                .get("accessToken").asText();
        return new Account(id, email, token);
    }

    private UUID newPlayer(String status) {
        return jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Ana', 'Gómez', 'TI', ?, DATE '2014-06-01', ?) RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), status);
    }

    private Family family(boolean withCoach) throws Exception {
        UUID player = newPlayer("ACTIVE");
        Account guardian = account("GUARDIAN");
        UUID guardianId = jdbc.queryForObject(
                "INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                UUID.class, guardian.id(), "cc-" + UUID.randomUUID().toString().substring(0, 20));
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", player, guardianId);
        Account coach = null;
        if (withCoach) {
            coach = account("COACH");
            int year = NEXT_YEAR.getAndAdd(3);
            UUID category = jdbc.queryForObject(
                    "INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES (?, ?, ?, 30) RETURNING id",
                    UUID.class, "Docs " + UUID.randomUUID(), year, year + 1);
            UUID file = jdbc.queryForObject("""
                    INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                    VALUES (?, 'acta.pdf', 'application/pdf', 10, repeat('a', 64)) RETURNING id
                    """, UUID.class, "docs-it/" + UUID.randomUUID());
            UUID minute = jdbc.queryForObject("""
                    INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
                    VALUES (?, current_date, ?, 'ASSIGNED', ?) RETURNING id
                    """, UUID.class, "DOC-" + UUID.randomUUID().toString().substring(0, 8), player, file);
            jdbc.update("INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (?, ?, ?, 'COMMITTEE', ?)",
                    player, category, coach.id(), minute);
        }
        return new Family(player, guardian, coach);
    }

    private UUID medicalType() {
        return jdbc.queryForObject("SELECT id FROM document_types WHERE code = 'MEDICAL_CERTIFICATE'", UUID.class);
    }

    private ResultActions uploadDocument(UUID player, UUID type, LocalDate expiresOn, String bearer) throws Exception {
        MockHttpServletRequestBuilder request = multipart(API + "/players/" + player + "/documents")
                .file(new MockMultipartFile("file", "certificado.pdf", "application/pdf", PDF))
                .param("documentTypeId", type.toString());
        if (expiresOn != null) {
            request.param("expiresOn", expiresOn.toString());
        }
        return mvc.perform(request.header("Authorization", "Bearer " + bearer));
    }

    private int auditCount(String action, UUID actor) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND actor_id = ?", Integer.class, action, actor);
    }

    // ---------- catálogo y ventanas ----------

    @Test
    void theCoordinatorManagesTheCatalogAndWindowsAndOthersCannot() throws Exception {
        Account coordinator = account("COORDINATOR");
        Account guardian = account("GUARDIAN");
        String code = "DOC_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace("-", "");
        Map<String, Object> request = Map.of("code", code, "name", "Carné de EPS", "mandatory", false, "requiresExpiry", true,
                "validityDays", 365, "allowedContentTypes", List.of("application/pdf", "image/png"));

        postJson("/document-types", request, guardian.token()).andExpect(status().isForbidden());
        JsonNode created = body(postJson("/document-types", request, coordinator.token()).andExpect(status().isCreated()));
        assertThat(created.get("containsHealthData").asBoolean()).isFalse();
        assertThat(created.get("allowedContentTypes")).hasSize(2);
        postJson("/document-types", request, coordinator.token()).andExpect(status().isUnprocessableEntity());
        postJson("/document-types", Map.of("code", "malo", "name", " ", "validityDays", 10), coordinator.token())
                .andExpect(status().isUnprocessableEntity());

        // Cualquier usuario autenticado ve qué se exige; solo la coordinación ve los desactivados.
        JsonNode seen = body(getJson("/document-types", guardian.token()).andExpect(status().isOk()));
        assertThat(seen.findValuesAsText("code")).contains("MEDICAL_CERTIFICATE", code);

        String typeId = created.get("id").asText();
        JsonNode updated = body(patchJson("/document-types/" + typeId, Map.of("active", false), coordinator.token())
                .andExpect(status().isOk()));
        assertThat(updated.get("active").asBoolean()).isFalse();
        assertThat(body(getJson("/document-types", guardian.token())).findValuesAsText("code")).doesNotContain(code);
        assertThat(body(getJson("/document-types?includeInactive=true", coordinator.token())).findValuesAsText("code")).contains(code);
        patchJson("/document-types/" + typeId, Map.of("validityDays", 0), coordinator.token()).andExpect(status().isUnprocessableEntity());
        patchJson("/document-types/" + UUID.randomUUID(), Map.of("active", true), coordinator.token()).andExpect(status().isNotFound());
        assertThat(auditCount("DOCUMENT_TYPE_CREATED", coordinator.id())).isEqualTo(1);
        assertThat(auditCount("DOCUMENT_TYPE_UPDATED", coordinator.id())).isEqualTo(1);

        // Ventana: rango coherente, tipo activo y jugador existente.
        UUID medical = medicalType();
        Instant now = Instant.now();
        Map<String, Object> window = Map.of("documentTypeId", medical, "opensAt", now.minus(1, ChronoUnit.HOURS).toString(),
                "closesAt", now.plus(7, ChronoUnit.DAYS).toString(), "reason", "Renovación anual");
        postJson("/document-windows", window, guardian.token()).andExpect(status().isForbidden());
        postJson("/document-windows", window, coordinator.token()).andExpect(status().isCreated());
        postJson("/document-windows", Map.of("documentTypeId", medical, "opensAt", now.plus(2, ChronoUnit.DAYS).toString(),
                "closesAt", now.plus(1, ChronoUnit.DAYS).toString()), coordinator.token()).andExpect(status().isUnprocessableEntity());
        postJson("/document-windows", Map.of("documentTypeId", typeId, "opensAt", now.toString(),
                "closesAt", now.plus(1, ChronoUnit.DAYS).toString()), coordinator.token()).andExpect(status().isUnprocessableEntity());
        postJson("/document-windows", Map.of("documentTypeId", medical, "playerId", UUID.randomUUID(), "opensAt", now.toString(),
                "closesAt", now.plus(1, ChronoUnit.DAYS).toString()), coordinator.token()).andExpect(status().isUnprocessableEntity());
        assertThat(body(getJson("/document-windows?documentTypeId=" + medical, coordinator.token()).andExpect(status().isOk())).size())
                .isGreaterThanOrEqualTo(1);
        assertThat(auditCount("DOCUMENT_WINDOW_OPENED", coordinator.id())).isEqualTo(1);
    }

    // ---------- versiones ----------

    @Test
    void versionsAccumulateNeverChangeAndNewOnesNeedAWindowWhileTheDocumentIsValid() throws Exception {
        Account coordinator = account("COORDINATOR");
        Family family = family(false);
        UUID medical = medicalType();
        String guardian = family.guardian().token();

        // La primera versión siempre se puede cargar; un vencimiento ausente o ya pasado se rechaza.
        uploadDocument(family.player(), medical, null, guardian).andExpect(status().isUnprocessableEntity());
        // «Ayer» se calcula en la zona del club, que es la que usa el servicio: en UTC fallaba entre las 19:00 y la medianoche de Bogotá.
        uploadDocument(family.player(), medical, LocalDate.now(ZoneId.of("America/Bogota")).minusDays(1), guardian)
                .andExpect(status().isUnprocessableEntity());
        JsonNode first = body(uploadDocument(family.player(), medical, LocalDate.now().plusDays(300), guardian)
                .andExpect(status().isCreated()));
        assertThat(first.get("versionNumber").asInt()).isEqualTo(1);

        // Vigente por 300 días y sin ventana: no se puede reemplazar (RN-05).
        assertThat(body(uploadDocument(family.player(), medical, LocalDate.now().plusDays(500), guardian)
                .andExpect(status().isConflict())).get("code").asText()).isEqualTo("DOCUMENT_UPLOAD_NOT_ALLOWED");

        // La coordinación habilita una ventana para este jugador y entonces sí.
        Instant now = Instant.now();
        postJson("/document-windows", Map.of("documentTypeId", medical, "playerId", family.player(),
                "opensAt", now.minus(1, ChronoUnit.HOURS).toString(), "closesAt", now.plus(1, ChronoUnit.DAYS).toString()),
                coordinator.token()).andExpect(status().isCreated());
        JsonNode second = body(uploadDocument(family.player(), medical, LocalDate.now().plusDays(500), guardian)
                .andExpect(status().isCreated()));
        assertThat(second.get("versionNumber").asInt()).isEqualTo(2);
        assertThat(second.get("fileName").asText()).isEqualTo("certificado.pdf");

        // Historial completo, la más reciente primero; el estado actual refleja la última versión.
        JsonNode history = body(getJson("/players/" + family.player() + "/document-types/" + medical + "/versions", guardian)
                .andExpect(status().isOk()));
        assertThat(history.findValuesAsText("versionNumber")).containsExactly("2", "1");
        JsonNode current = body(getJson("/players/" + family.player() + "/documents", guardian).andExpect(status().isOk()));
        JsonNode certificate = null;
        for (JsonNode item : current) {
            if ("MEDICAL_CERTIFICATE".equals(item.get("type").get("code").asText())) {
                certificate = item;
            }
        }
        assertThat(certificate.get("status").asText()).isEqualTo("VALID");
        assertThat(certificate.get("current").get("versionNumber").asInt()).isEqualTo(2);

        // El contenido se descarga íntegro, como adjunto y sin que el navegador lo reinterprete.
        byte[] downloaded = getJson("/players/" + family.player() + "/documents/versions/" + second.get("id").asText() + "/file", guardian)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PDF);

        // La base rechaza cualquier edición o borrado de una versión (RF-008).
        UUID versionId = UUID.fromString(first.get("id").asText());
        assertThatThrownBy(() -> jdbc.update("UPDATE document_versions SET notes = 'x' WHERE id = ?", versionId))
                .hasMessageContaining("IMMUTABLE_RECORD");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM document_versions WHERE id = ?", versionId))
                .hasMessageContaining("IMMUTABLE_RECORD");
        assertThat(auditCount("DOCUMENT_VERSION_UPLOADED", family.guardian().id())).isEqualTo(2);

        // Un acudiente ajeno no ve ni siquiera que el jugador exista.
        Account stranger = account("GUARDIAN");
        getJson("/players/" + family.player() + "/documents", stranger.token()).andExpect(status().isNotFound());
        uploadDocument(family.player(), medical, LocalDate.now().plusDays(100), stranger.token()).andExpect(status().isNotFound());
    }

    @Test
    void aCertificateAboutToExpireCanBeRenewedByTheGuardianWithoutAWindow() throws Exception {
        Family family = family(false);
        UUID medical = medicalType();
        addCertificate(family.player(), family.guardian().id(), 1, "current_date + 20");

        JsonNode renewed = body(uploadDocument(family.player(), medical, LocalDate.now().plusDays(365), family.guardian().token())
                .andExpect(status().isCreated()));

        assertThat(renewed.get("versionNumber").asInt()).isEqualTo(2);
    }

    // ---------- documentos de salud ----------

    @Test
    void healthDocumentsAreRestrictedAuditedAndNotReachableThroughTheGenericFileEndpoint() throws Exception {
        Account coordinator = account("COORDINATOR");
        Account committee = account("COMMITTEE");
        Family family = family(true);
        Account otherCoach = account("COACH");
        UUID medical = medicalType();
        JsonNode version = body(uploadDocument(family.player(), medical, LocalDate.now().plusDays(300), family.guardian().token())
                .andExpect(status().isCreated()));
        String versionPath = "/players/" + family.player() + "/documents/versions/" + version.get("id").asText() + "/file";
        UUID fileId = jdbc.queryForObject("SELECT file_id FROM document_versions WHERE id = ?::uuid", UUID.class, version.get("id").asText());

        // El comité ve al jugador, pero ni los documentos de salud en el listado ni su contenido.
        JsonNode committeeView = body(getJson("/players/" + family.player() + "/documents", committee.token()).andExpect(status().isOk()));
        assertThat(committeeView.findValuesAsText("code")).doesNotContain("MEDICAL_CERTIFICATE");
        getJson(versionPath, committee.token()).andExpect(status().isForbidden());
        getJson("/players/" + family.player() + "/document-types/" + medical + "/versions", committee.token())
                .andExpect(status().isForbidden());

        // La vía genérica /files tampoco lo entrega al comité (404), pero sí a la coordinación y a quien lo subió.
        getJson("/files/" + fileId, committee.token()).andExpect(status().isNotFound());
        getJson("/files/" + fileId, coordinator.token()).andExpect(status().isOk());
        getJson("/files/" + fileId, family.guardian().token()).andExpect(status().isOk());

        // El entrenador titular lo consulta (auditado); otro entrenador no ve al jugador.
        getJson(versionPath, family.coach().token()).andExpect(status().isOk());
        getJson(versionPath, otherCoach.token()).andExpect(status().isNotFound());
        getJson(versionPath, coordinator.token()).andExpect(status().isOk());
        assertThat(auditCount("DOCUMENT_HEALTH_VIEWED", family.coach().id())).isEqualTo(1);
        assertThat(auditCount("DOCUMENT_HEALTH_VIEWED", coordinator.id())).isEqualTo(1);
        assertThat(auditCount("DOCUMENT_HEALTH_VIEWED", committee.id())).isEqualTo(0);
        // La evidencia no guarda el nombre del archivo ni su contenido.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'DOCUMENT_HEALTH_VIEWED' AND after_state::text LIKE '%certificado%'",
                Integer.class)).isEqualTo(0);
    }

    // ---------- alertas de vencimiento ----------

    @Test
    void expiryAlertsGoToTheGuardianAndTheHeadCoachOncePerStageWithoutHealthData() throws Exception {
        Family family = family(true);
        addCertificate(family.player(), family.guardian().id(), 1, "current_date + 20");
        UUID version = currentVersion(family.player());

        alerts.notifyExpiring();
        alerts.notifyExpiring(); // repetir el proceso no duplica

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT recipient_user_id, payload::text AS payload FROM notifications WHERE related_entity_id = ? AND notification_type = 'DOCUMENT_EXPIRY'",
                version);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> (UUID) r.get("recipient_user_id")).toList())
                .containsExactlyInAnyOrder(family.guardian().id(), family.coach().id());
        String payload = (String) rows.get(0).get("payload");
        assertThat(payload).contains("Certificado médico", "Ana Gómez");
        assertThat(payload).doesNotContain("certificado.pdf");

        // Cuando falta una semana o menos corresponde el segundo aviso, con su propio ámbito.
        UUID closing = newPlayer("ACTIVE");
        jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) SELECT ?, guardian_id, false FROM player_guardians WHERE player_id = ?",
                closing, family.player());
        addCertificate(closing, family.guardian().id(), 1, "current_date + 5");
        UUID closingVersion = currentVersion(closing);
        alerts.notifyExpiring();
        alerts.notifyExpiring();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE related_entity_id = ?", Integer.class, closingVersion))
                .isEqualTo(1);

        // Lo que no está en la ventana o ya venció no genera aviso.
        UUID far = newPlayer("ACTIVE");
        addCertificate(far, family.guardian().id(), 1, "current_date + 90");
        UUID expired = newPlayer("ACTIVE");
        addCertificate(expired, family.guardian().id(), 1, "current_date - 2");
        alerts.notifyExpiring();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE related_entity_id IN (?, ?)", Integer.class,
                currentVersion(far), currentVersion(expired))).isEqualTo(0);
    }

    private UUID currentVersion(UUID player) {
        return jdbc.queryForObject("SELECT id FROM current_document_versions WHERE player_id = ?", UUID.class, player);
    }

    /** Certificado médico con el vencimiento dado como expresión SQL (la tabla es de solo inserción). */
    private void addCertificate(UUID player, UUID uploadedBy, int version, String expiresOnSql) {
        UUID file = jdbc.queryForObject("""
                INSERT INTO stored_files (storage_key, original_name, content_type, size_bytes, sha256)
                VALUES (?, 'certificado.pdf', 'application/pdf', 10, repeat('b', 64)) RETURNING id
                """, UUID.class, "docs-it/" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO document_versions (player_id, document_type_id, version_number, file_id, expires_on, uploaded_by)
                SELECT ?, id, ?, ?, %s, ? FROM document_types WHERE code = 'MEDICAL_CERTIFICATE'
                """.formatted(expiresOnSql), player, version, file, uploadedBy);
    }
}
