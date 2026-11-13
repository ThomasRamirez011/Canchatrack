package co.edu.unipamplona.canchatrack.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Archivos, tarifas, matrícula, pagos inmutables, activación y cobro mensual contra PostgreSQL real. Un solo recorrido
 * de punta a punta más los rechazos que importan; la lógica fina está en las pruebas de reglas.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PaymentsIT {

    private static final String PASSWORD = "Clave-Segura-123";
    private static final String API = "/api/v1";
    private static final byte[] PDF = "%PDF-1.4 comprobante".getBytes();

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

    private ResultActions getJson(String path, String bearer) throws Exception {
        return send(get(API + path), null, bearer);
    }

    private JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private String staffToken(String roleCode) throws Exception {
        String email = "staff-" + UUID.randomUUID() + "@club.test";
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Personal de prueba", encoder.encode(PASSWORD));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, roleCode);
        return body(postJson("/auth/login", Map.of("email", email, "password", PASSWORD), null).andExpect(status().isOk()))
                .get("accessToken").asText();
    }

    private UUID newSeason() {
        return jdbc.queryForObject("INSERT INTO seasons (name, start_at, end_at) VALUES (?, now(), now() + interval '1 year') RETURNING id",
                UUID.class, "Temporada " + UUID.randomUUID());
    }

    private UUID newPlayer(String status) {
        return jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Ana', 'Gómez', 'TI', ?, DATE '2014-06-01', ?) RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), status);
    }

    private String createFee(String coordinator, UUID season, String concept, double amount) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("seasonId", season);
        request.put("concept", concept);
        request.put("name", concept + " " + UUID.randomUUID());
        request.put("amount", amount);
        request.put("validFrom", LocalDate.now().toString());
        return body(postJson("/fees", request, coordinator).andExpect(status().isCreated())).get("id").asText();
    }

    private String uploadReceipt(String token) throws Exception {
        return body(mvc.perform(multipart(API + "/files").file(new MockMultipartFile("file", "recibo.pdf", "application/pdf", PDF))
                .header("Authorization", "Bearer " + token)).andExpect(status().isCreated())).get("id").asText();
    }

    // ---------- pruebas ----------

    @Test
    void enrollmentFlowGoesFromFeeToActivePlayerAndVoidRestoresTheDebt() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID season = newSeason();
        UUID player = newPlayer("CATEGORY_ASSIGNED");
        String fee = createFee(coordinator, season, "ENROLLMENT", 100000);

        // Matrícula: genera el cargo y deja al jugador pendiente; repetirla ya no procede.
        JsonNode started = body(postJson("/players/" + player + "/registration",
                Map.of("feeId", fee, "dueDate", LocalDate.now().plusDays(15).toString()), coordinator).andExpect(status().isCreated()));
        assertThat(started.get("playerStatus").asText()).isEqualTo("PENDING_REGISTRATION");
        String chargeId = started.get("chargeId").asText();
        postJson("/players/" + player + "/registration", Map.of("feeId", fee, "dueDate", LocalDate.now().plusDays(15).toString()),
                coordinator).andExpect(status().isConflict());

        // Con deuda pendiente no se puede activar.
        postJson("/players/" + player + "/activation", null, coordinator).andExpect(status().isConflict());

        // Pago parcial con comprobante; un pago mayor que la deuda restante se rechaza.
        String receipt = uploadReceipt(coordinator);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("chargeId", chargeId);
        first.put("amount", 40000);
        first.put("method", "TRANSFER");
        first.put("reference", "TX-1");
        first.put("receiptFileId", receipt);
        String firstPayment = body(postJson("/players/" + player + "/payments", first, coordinator).andExpect(status().isCreated()))
                .get("id").asText();
        JsonNode tooMuch = body(postJson("/players/" + player + "/payments",
                Map.of("chargeId", chargeId, "amount", 70000, "method", "CASH"), coordinator).andExpect(status().isUnprocessableEntity()));
        assertThat(tooMuch.get("errors").get(0).get("field").asText()).isEqualTo("amount");

        JsonNode partial = body(getJson("/players/" + player + "/account", coordinator).andExpect(status().isOk()));
        assertThat(partial.get("balance").decimalValue()).isEqualByComparingTo("60000");
        assertThat(partial.get("charges").get(0).get("status").asText()).isEqualTo("PARTIAL");

        // Se salda: ya se puede activar.
        String secondPayment = body(postJson("/players/" + player + "/payments",
                Map.of("chargeId", chargeId, "amount", 60000, "method", "CASH"), coordinator).andExpect(status().isCreated())).get("id").asText();
        JsonNode settled = body(getJson("/players/" + player + "/account", coordinator).andExpect(status().isOk()));
        assertThat(settled.get("balance").decimalValue()).isEqualByComparingTo("0");
        assertThat(settled.get("charges").get(0).get("status").asText()).isEqualTo("SETTLED");
        postJson("/players/" + player + "/payments", Map.of("chargeId", chargeId, "amount", 1, "method", "CASH"), coordinator)
                .andExpect(status().isConflict());

        // Con la matrícula saldada pero sin el certificado médico obligatorio tampoco se activa (RF-048, CA-02).
        JsonNode missing = body(postJson("/players/" + player + "/activation", null, coordinator).andExpect(status().isConflict()));
        assertThat(missing.get("missingDocuments").get(0).asText()).isEqualTo("Certificado médico");
        UUID medicalCertificate = jdbc.queryForObject("SELECT id FROM document_types WHERE code = 'MEDICAL_CERTIFICATE'", UUID.class);
        mvc.perform(multipart(API + "/players/" + player + "/documents")
                .file(new MockMultipartFile("file", "certificado.pdf", "application/pdf", PDF))
                .param("documentTypeId", medicalCertificate.toString())
                .param("expiresOn", LocalDate.now().plusDays(200).toString())
                .header("Authorization", "Bearer " + coordinator)).andExpect(status().isCreated());

        JsonNode active = body(postJson("/players/" + player + "/activation", null, coordinator).andExpect(status().isOk()));
        assertThat(active.get("playerStatus").asText()).isEqualTo("ACTIVE");

        // Anular el segundo pago: la deuda vuelve; anularlo otra vez es un conflicto; el pago original no cambia.
        JsonNode voided = body(postJson("/payments/" + secondPayment + "/void", Map.of("reason", "Error de captura"), coordinator)
                .andExpect(status().isOk()));
        assertThat(voided.get("voided").asBoolean()).isTrue();
        assertThat(voided.get("netAmount").decimalValue()).isEqualByComparingTo("0");
        postJson("/payments/" + secondPayment + "/void", Map.of("reason", "otra vez"), coordinator).andExpect(status().isConflict());
        postJson("/payments/" + firstPayment + "/void", Map.of("reason", " "), coordinator).andExpect(status().isUnprocessableEntity());
        JsonNode afterVoid = body(getJson("/players/" + player + "/account", coordinator).andExpect(status().isOk()));
        assertThat(afterVoid.get("balance").decimalValue()).isEqualByComparingTo("60000");

        // Auditoría de los hechos relevantes.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE entity_id = ? AND action IN ('PLAYER_REGISTRATION_STARTED', 'PLAYER_ACTIVATED')",
                Integer.class, player)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE action = 'PAYMENT_VOIDED' AND entity_id = ?",
                Integer.class, UUID.fromString(secondPayment))).isEqualTo(1);
    }

    @Test
    void registrationAndFeesRejectWrongStatesAndInvalidData() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID season = newSeason();
        String enrollmentFee = createFee(coordinator, season, "ENROLLMENT", 90000);
        String monthlyFee = createFee(coordinator, season, "MONTHLY", 50000);

        // Fuera de CATEGORY_ASSIGNED y sin jugador.
        UUID submitted = newPlayer("SUBMITTED");
        postJson("/players/" + submitted + "/registration", Map.of("feeId", enrollmentFee, "dueDate", LocalDate.now().toString()),
                coordinator).andExpect(status().isConflict());
        postJson("/players/" + UUID.randomUUID() + "/registration", Map.of("feeId", enrollmentFee, "dueDate", LocalDate.now().toString()),
                coordinator).andExpect(status().isNotFound());

        // Tarifa de otro concepto o inexistente, y fecha ausente: errores juntos y el jugador no cambia.
        UUID assigned = newPlayer("CATEGORY_ASSIGNED");
        JsonNode errors = body(postJson("/players/" + assigned + "/registration", Map.of("feeId", monthlyFee), coordinator)
                .andExpect(status().isUnprocessableEntity()));
        List<String> fields = errors.get("errors").findValuesAsText("field");
        assertThat(fields).contains("feeId");
        assertThat(jdbc.queryForObject("SELECT status FROM players WHERE id = ?", String.class, assigned)).isEqualTo("CATEGORY_ASSIGNED");

        // Tarifa inválida: todos los errores a la vez.
        JsonNode badFee = body(postJson("/fees", Map.of("concept", "XX", "name", " ", "amount", -5), coordinator)
                .andExpect(status().isUnprocessableEntity()));
        assertThat(badFee.get("errors").findValuesAsText("field")).contains("concept", "name", "amount", "validFrom", "seasonId");

        // Solo el coordinador cobra; un evaluador no puede ver tarifas ni estados de cuenta.
        String evaluator = staffToken("EVALUATOR");
        postJson("/payments/" + UUID.randomUUID() + "/void", Map.of("reason", "x"), evaluator).andExpect(status().isForbidden());
        getJson("/fees", evaluator).andExpect(status().isForbidden());
    }

    @Test
    void monthlyBillingChargesEveryActivePlayerOnceAndIsIdempotent() throws Exception {
        String coordinator = staffToken("COORDINATOR");
        UUID season = newSeason();
        String fee = createFee(coordinator, season, "MONTHLY", 60000);
        UUID active = newPlayer("ACTIVE");
        UUID inactive = newPlayer("WITHDRAWN");
        LocalDate period = LocalDate.now().withDayOfMonth(1);
        Map<String, Object> request = Map.of("feeId", fee, "period", period.toString(), "dueDate", period.plusDays(9).toString());

        JsonNode first = body(postJson("/billing/monthly", request, coordinator).andExpect(status().isOk()));
        assertThat(first.get("created").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode again = body(postJson("/billing/monthly", request, coordinator).andExpect(status().isOk()));
        assertThat(again.get("created").asInt()).isZero();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM charges WHERE fee_id = ? AND player_id = ?", Integer.class,
                UUID.fromString(fee), active)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM charges WHERE fee_id = ? AND player_id = ?", Integer.class,
                UUID.fromString(fee), inactive)).isZero();
        postJson("/billing/monthly", Map.of("feeId", fee, "period", period.plusDays(3).toString(), "dueDate", period.plusDays(9).toString()),
                coordinator).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void filesAreValidatedStoredAndDownloadableOnlyByAuthorizedUsers() throws Exception {
        String uploader = staffToken("EVALUATOR");
        String id = uploadReceipt(uploader);

        // El contenido debe corresponder al tipo declarado.
        mvc.perform(multipart(API + "/files").file(new MockMultipartFile("file", "falso.pdf", "application/pdf", "no soy pdf".getBytes()))
                .header("Authorization", "Bearer " + uploader)).andExpect(status().isUnprocessableEntity());
        mvc.perform(multipart(API + "/files").file(new MockMultipartFile("file", "x.exe", "application/x-msdownload", PDF))
                .header("Authorization", "Bearer " + uploader)).andExpect(status().isUnprocessableEntity());

        // Descarga: quien lo subió y el coordinador sí; otro usuario recibe 404.
        byte[] downloaded = getJson("/files/" + id, uploader).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(PDF);
        getJson("/files/" + id, staffToken("COORDINATOR")).andExpect(status().isOk());
        getJson("/files/" + id, staffToken("EVALUATOR")).andExpect(status().isNotFound());
        mvc.perform(multipart(API + "/files").file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF)))
                .andExpect(status().isUnauthorized());
    }
}
