package co.edu.unipamplona.canchatrack.players;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Documentos iniciales dentro de la radicación (CU-02, RF-006) contra PostgreSQL real. La lógica fina está en
 * {@code EnrollmentDocumentServiceTest} y {@code EnrollmentServiceTest}; aquí se comprueba lo que solo la base y la
 * seguridad reales demuestran: que la radicación y sus versiones son una sola transacción, que los documentos de salud
 * adjuntos quedan cerrados a quien no corresponde y que un archivo respalda un único documento.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class EnrollmentDocumentsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final byte[] PDF = "%PDF-1.4 certificado medico".getBytes();
    /** «Hoy» del club: la fecha de emisión no puede ser futura en esa zona. */
    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("America/Bogota"));

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ObjectMapper json;

    // ---------- utilidades ----------

    private record Guardian(String token, UUID userId) {
    }

    private ResultActions postJson(String path, Object body, String bearer) throws Exception {
        var request = post(API + path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (bearer != null) {
            request = request.header("Authorization", "Bearer " + bearer);
        }
        return mvc.perform(request);
    }

    private ResultActions getAny(String path, String bearer) throws Exception {
        return mvc.perform(get(API + path).header("Authorization", "Bearer " + bearer));
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String login(String email) throws Exception {
        return body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null).andExpect(status().isOk()))
                .get("accessToken").asString();
    }

    private Guardian registerGuardian() throws Exception {
        String email = "g-" + UUID.randomUUID() + "@club.test";
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", email);
        request.put("fullName", "Ana Acudiente");
        request.put("password", PASSWORD);
        request.put("documentType", "CC");
        request.put("documentNumber", String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        request.put("phone", "300 123 4567");
        UUID userId = UUID.fromString(body(postJson("/guardians", request, null).andExpect(status().isCreated())).get("id").asString());
        return new Guardian(login(email), userId);
    }

    private String staffToken(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return login(email);
    }

    private UUID openCall() {
        UUID season = jdbc.queryForObject(
                "INSERT INTO seasons (name, start_at, end_at) VALUES (?, now() - interval '30 days', now() + interval '1 year') RETURNING id",
                UUID.class, "T-" + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO calls (season_id, name, start_at, end_at, status)
                VALUES (?, ?, now() - interval '1 day', now() + interval '1 day', 'PUBLISHED') RETURNING id
                """, UUID.class, season, "Convocatoria " + UUID.randomUUID());
    }

    private static String newPlayerDocument() {
        return "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private UUID medicalType() {
        return jdbc.queryForObject("SELECT id FROM document_types WHERE code = 'MEDICAL_CERTIFICATE'", UUID.class);
    }

    /** Tipo opcional de PDF creado por la API de la coordinación; no obligatorio, para no alterar la activación de otras pruebas. */
    private UUID optionalType(String coordinatorToken) throws Exception {
        String code = "ADJ_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace("-", "");
        return UUID.fromString(body(postJson("/document-types", Map.of("code", code, "name", "Soporte " + code, "mandatory", false,
                "requiresExpiry", false, "allowedContentTypes", List.of("application/pdf")), coordinatorToken)
                .andExpect(status().isCreated())).get("id").asString());
    }

    private UUID uploadFile(String token) throws Exception {
        return UUID.fromString(body(mvc.perform(multipart(API + "/files").file(new MockMultipartFile("file", "soporte.pdf",
                        "application/pdf", PDF)).header("Authorization", "Bearer " + token)).andExpect(status().isCreated()))
                .get("id").asString());
    }

    private Map<String, Object> attachment(UUID type, UUID file, LocalDate expiresOn) {
        Map<String, Object> attachment = new LinkedHashMap<>();
        attachment.put("documentTypeId", type);
        attachment.put("fileId", file);
        attachment.put("issuedOn", TODAY.minusDays(2).toString());
        if (expiresOn != null) {
            attachment.put("expiresOn", expiresOn.toString());
        }
        attachment.put("notes", "Cargado al radicar");
        return attachment;
    }

    private Map<String, Object> request(UUID callId, String documentNumber, List<Map<String, Object>> documents) {
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("firstName", "Juan");
        player.put("lastName", "Pérez");
        player.put("documentType", "TI");
        player.put("documentNumber", documentNumber);
        player.put("birthDate", "2014-03-09");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("callId", callId);
        request.put("player", player);
        request.put("relationship", "Madre");
        request.put("acceptDataProcessing", true);
        request.put("acceptParticipation", true);
        if (documents != null) {
            request.put("documents", documents);
        }
        return request;
    }

    private long versionsOf(UUID playerId) {
        return jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE player_id = ?", Long.class, playerId);
    }

    private long audits(String action, UUID actor, String reason) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = ? AND actor_id = ? AND (?::text IS NULL OR reason = ?)",
                Long.class, action, actor, reason, reason);
    }

    // ---------- radicación con documentos ----------

    @Test
    void theAttachedFilesBecomeTheFirstImmutableVersionsAndTheHealthOneStaysClosedToOutsiders() throws Exception {
        Guardian guardian = registerGuardian();
        Guardian stranger = registerGuardian();
        String coordinator = staffToken("COORDINATOR");
        UUID support = optionalType(coordinator);
        UUID certificateFile = uploadFile(guardian.token());
        UUID supportFile = uploadFile(guardian.token());

        JsonNode receipt = body(postJson("/enrollments", request(openCall(), newPlayerDocument(), List.of(
                attachment(medicalType(), certificateFile, LocalDate.now().plusDays(300)),
                attachment(support, supportFile, null))), guardian.token()).andExpect(status().isCreated()));

        UUID playerId = UUID.fromString(receipt.get("playerId").asString());
        assertThat(receipt.get("attachedDocuments").asInt()).isEqualTo(2);
        List<String> missing = new ArrayList<>();
        receipt.get("missingDocuments").forEach(n -> missing.add(n.asString()));
        assertThat(missing).doesNotContain("Certificado médico");

        List<Map<String, Object>> versions = jdbc.queryForList("""
                SELECT version_number, uploaded_by, notes FROM document_versions WHERE player_id = ? ORDER BY uploaded_at, id
                """, playerId);
        assertThat(versions).hasSize(2);
        assertThat(versions).allSatisfy(v -> {
            assertThat(v.get("version_number")).isEqualTo(1);
            assertThat(v.get("uploaded_by")).isEqualTo(guardian.userId());
            assertThat(v.get("notes")).isEqualTo("Cargado al radicar");
        });
        assertThat(audits("DOCUMENT_VERSION_UPLOADED", guardian.userId(), null)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT after_state::text FROM audit_events WHERE action = 'ENROLLMENT_SUBMITTED' AND actor_id = ?",
                String.class, guardian.userId())).contains("\"attachedDocuments\": 2");

        // Las versiones son inmutables como cualquier otra (RF-008): la base rechaza el cambio.
        try {
            jdbc.update("UPDATE document_versions SET notes = 'cambiado' WHERE player_id = ?", playerId);
            throw new AssertionError("la base debía rechazar la modificación");
        } catch (org.springframework.dao.DataAccessException expected) {
            assertThat(versionsOf(playerId)).isEqualTo(2L);
        }

        // El acudiente ve su certificado vigente y puede descargarlo; el estado del documento lo refleja.
        JsonNode documents = body(getAny("/players/" + playerId + "/documents", guardian.token()).andExpect(status().isOk()));
        String medicalStatus = null;
        UUID medicalVersion = null;
        for (JsonNode item : documents) {
            if ("MEDICAL_CERTIFICATE".equals(item.get("type").get("code").asString())) {
                medicalStatus = item.get("status").asString();
                medicalVersion = UUID.fromString(item.get("current").get("id").asString());
            }
        }
        assertThat(medicalStatus).isEqualTo("VALID");
        getAny("/players/" + playerId + "/documents/versions/" + medicalVersion + "/file", guardian.token())
                .andExpect(status().isOk());

        // El archivo ya es un documento de salud: ni otro acudiente ni el comité lo descargan por /files; la coordinación sí.
        getAny("/files/" + certificateFile, stranger.token()).andExpect(status().isNotFound());
        getAny("/files/" + certificateFile, staffToken("COMMITTEE")).andExpect(status().isNotFound());
        getAny("/files/" + certificateFile, coordinator).andExpect(status().isOk());
        getAny("/players/" + playerId + "/documents", stranger.token()).andExpect(status().isNotFound());
    }

    @Test
    void withoutDocumentsTheFilingStillSucceedsAndReportsTheMandatoryOnesPending() throws Exception {
        Guardian guardian = registerGuardian();

        JsonNode receipt = body(postJson("/enrollments", request(openCall(), newPlayerDocument(), null), guardian.token())
                .andExpect(status().isCreated()));

        assertThat(receipt.get("attachedDocuments").asInt()).isZero();
        List<String> missing = new ArrayList<>();
        receipt.get("missingDocuments").forEach(n -> missing.add(n.asString()));
        assertThat(missing).contains("Certificado médico");
        assertThat(versionsOf(UUID.fromString(receipt.get("playerId").asString()))).isZero();
    }

    @Test
    void invalidAttachmentsAreReportedTogetherAndNothingIsSaved() throws Exception {
        Guardian guardian = registerGuardian();
        Guardian other = registerGuardian();
        UUID foreignFile = uploadFile(other.token());
        String document = newPlayerDocument();

        JsonNode problem = body(postJson("/enrollments", request(openCall(), document, List.of(
                attachment(medicalType(), foreignFile, null),                       // archivo ajeno y falta el vencimiento
                attachment(UUID.randomUUID(), UUID.randomUUID(), null))),           // tipo y archivo inexistentes
                guardian.token()).andExpect(status().isUnprocessableEntity()));

        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = problem.get("errors").findValuesAsText("field");
        assertThat(fields).containsExactlyInAnyOrder("documents[0].fileId", "documents[0].expiresOn",
                "documents[1].documentTypeId", "documents[1].fileId");
        // Ajeno e inexistente responden igual: no se revela qué archivos existen.
        assertThat(problem.get("errors").findValuesAsText("message")).contains("El archivo no existe.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM players WHERE document_number = ?", Long.class, document)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE guardian_id = (SELECT id FROM guardians WHERE user_id = ?)",
                Long.class, guardian.userId())).isZero();
    }

    @Test
    void aFileBacksASingleDocumentAndATypeCannotRepeatInOneFiling() throws Exception {
        Guardian guardian = registerGuardian();
        String coordinator = staffToken("COORDINATOR");
        UUID support = optionalType(coordinator);
        UUID file = uploadFile(guardian.token());
        UUID second = uploadFile(guardian.token());

        postJson("/enrollments", request(openCall(), newPlayerDocument(), List.of(attachment(support, file, null))), guardian.token())
                .andExpect(status().isCreated());

        // Reutilizar el mismo archivo en otra radicación se rechaza.
        JsonNode reuse = body(postJson("/enrollments", request(openCall(), newPlayerDocument(), List.of(attachment(support, file, null))),
                guardian.token()).andExpect(status().isUnprocessableEntity()));
        assertThat(reuse.get("errors").findValuesAsText("field")).containsExactly("documents[0].fileId");

        // Dos adjuntos del mismo tipo en la misma radicación también.
        JsonNode repeated = body(postJson("/enrollments", request(openCall(), newPlayerDocument(), List.of(
                attachment(support, second, null), attachment(support, uploadFile(guardian.token()), null))),
                guardian.token()).andExpect(status().isUnprocessableEntity()));
        assertThat(repeated.get("errors").findValuesAsText("field")).containsExactly("documents[1].documentTypeId");
    }

    @Test
    void aReusedRecordNeedsAWindowForADocumentItAlreadyHasAndTheWholeFilingRollsBackWithoutIt() throws Exception {
        Guardian guardian = registerGuardian();
        String coordinator = staffToken("COORDINATOR");
        String document = newPlayerDocument();
        UUID medical = medicalType();
        JsonNode first = body(postJson("/enrollments", request(openCall(), document, List.of(
                attachment(medical, uploadFile(guardian.token()), LocalDate.now().plusDays(300)))), guardian.token())
                .andExpect(status().isCreated()));
        UUID playerId = UUID.fromString(first.get("playerId").asString());
        jdbc.update("UPDATE players SET status = 'REJECTED' WHERE id = ?", playerId);

        // El expediente reutilizado ya tiene un certificado vigente por 300 días y no hay ventana: se rechaza todo (RN-05).
        UUID renewal = uploadFile(guardian.token());
        JsonNode refused = body(postJson("/enrollments", request(openCall(), document, List.of(
                attachment(medical, renewal, LocalDate.now().plusDays(600)))), guardian.token()).andExpect(status().isConflict()));
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_UPLOAD_NOT_ALLOWED");
        assertThat(jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, playerId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM enrollments WHERE player_id = ?", Long.class, playerId)).isEqualTo(1L);
        assertThat(versionsOf(playerId)).isEqualTo(1L);
        assertThat(audits("ENROLLMENT_REJECTED", guardian.userId(), "DOCUMENT_UPLOAD_NOT_ALLOWED")).isEqualTo(1L);

        // La coordinación habilita una ventana para este jugador y el mismo archivo ya sirve: la versión 2 nace con la radicación.
        Instant now = Instant.now();
        postJson("/document-windows", Map.of("documentTypeId", medical, "playerId", playerId,
                "opensAt", now.minus(1, ChronoUnit.HOURS).toString(), "closesAt", now.plus(1, ChronoUnit.DAYS).toString()), coordinator)
                .andExpect(status().isCreated());
        body(postJson("/enrollments", request(openCall(), document, List.of(attachment(medical, renewal, LocalDate.now().plusDays(600)))),
                guardian.token()).andExpect(status().isCreated()));

        assertThat(jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, playerId)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForList("SELECT version_number FROM document_versions WHERE player_id = ? ORDER BY version_number",
                Integer.class, playerId)).containsExactly(1, 2);
    }
}
