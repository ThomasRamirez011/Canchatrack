package co.edu.unipamplona.canchatrack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

/**
 * Mantiene sincronizados el contrato OpenAPI y el código: "cada endpoint se documenta en OpenAPI antes de
 * implementarse" (CLAUDE.md). Las pruebas de contrato de cada endpoint se añaden con el endpoint.
 */
class OpenApiContractTest {

    private static final String SPEC = "/openapi/canchatrack-v1.yaml";

    @Test
    void specIsOpenApi31WithTheVersionedPrefix() throws Exception {
        Map<String, Object> spec = load();

        assertThat(String.valueOf(spec.get("openapi"))).startsWith("3.1");
        List<?> servers = (List<?>) spec.get("servers");
        assertThat(((Map<?, ?>) servers.get(0)).get("url")).isEqualTo(ApiPaths.V1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void errorCodeCatalogMatchesTheJavaEnum() throws Exception {
        Map<String, Object> components = (Map<String, Object>) load().get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> errorCode = (Map<String, Object>) schemas.get("ErrorCode");
        List<String> documented = (List<String>) errorCode.get("enum");

        List<String> implemented = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();

        assertThat(documented).containsExactlyInAnyOrderElementsOf(implemented);
    }

    @Test
    @SuppressWarnings("unchecked")
    void identityEndpointsAreDocumentedAndOnlySessionOpeningOnesArePublic() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) load().get("paths");

        assertThat(paths.keySet()).contains("/auth/login", "/auth/refresh", "/auth/logout", "/auth/me", "/users");
        for (String publicPath : List.of("/auth/login", "/auth/refresh", "/auth/logout", "/guardians")) {
            Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) paths.get(publicPath)).get("post");
            assertThat(post.get("security")).as("%s no exige token", publicPath).isEqualTo(List.of());
        }
        Map<String, Object> me = (Map<String, Object>) ((Map<String, Object>) paths.get("/auth/me")).get("get");
        Map<String, Object> createUser = (Map<String, Object>) ((Map<String, Object>) paths.get("/users")).get("post");
        assertThat(me).doesNotContainKey("security"); // hereda el esquema global (bearerAuth)
        assertThat(createUser).doesNotContainKey("security");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrollmentAndPlayerEndpointsAreDocumentedAndRequireAuthentication() throws Exception {
        Map<String, Object> paths = (Map<String, Object>) load().get("paths");

        assertThat(paths.keySet()).contains("/guardians", "/calls/open", "/enrollments", "/players", "/players/{playerId}");
        Map<String, Object> enrollment = (Map<String, Object>) ((Map<String, Object>) paths.get("/enrollments")).get("post");
        Map<String, Object> calls = (Map<String, Object>) ((Map<String, Object>) paths.get("/calls/open")).get("get");
        Map<String, Object> players = (Map<String, Object>) ((Map<String, Object>) paths.get("/players")).get("get");
        Map<String, Object> player = (Map<String, Object>) ((Map<String, Object>) paths.get("/players/{playerId}")).get("get");
        assertThat(enrollment).doesNotContainKey("security");
        assertThat(calls).doesNotContainKey("security");
        assertThat(players).doesNotContainKey("security");
        assertThat(player).doesNotContainKey("security");
    }

    @Test
    @SuppressWarnings("unchecked")
    void callAdministrationEndpointsAreDocumentedAndEnumsMatchTheDomain() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/seasons", "/seasons/{seasonId}/activate", "/calls", "/calls/{callId}",
                "/calls/{callId}/publish", "/calls/{callId}/cancel", "/calls/{callId}/exceptions");
        for (String path : List.of("/seasons", "/calls", "/calls/{callId}/exceptions")) {
            assertThat(((Map<String, Object>) paths.get(path)).keySet()).contains("get", "post");
        }
        assertThat(((Map<String, Object>) paths.get("/calls/{callId}")).keySet()).contains("get", "patch");

        assertThat((List<String>) ((Map<String, Object>) schemas.get("CallStatus")).get("enum"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(
                        co.edu.unipamplona.canchatrack.players.domain.CallStatus.values()).map(Enum::name).toList());
        assertThat((List<String>) ((Map<String, Object>) schemas.get("SeasonStatus")).get("enum"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(
                        co.edu.unipamplona.canchatrack.players.domain.SeasonStatus.values()).map(Enum::name).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void categoryEndpointsAreDocumentedAndKeepTheirPublicShape() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/categories", "/categories/{categoryId}", "/players/{playerId}/category-suggestion");
        assertThat(((Map<String, Object>) paths.get("/categories")).keySet()).contains("get", "post");
        assertThat(((Map<String, Object>) paths.get("/categories/{categoryId}")).keySet()).contains("get", "patch");
        Map<String, Object> category = (Map<String, Object>) schemas.get("CategoryResponse");
        assertThat(((Map<String, Object>) category.get("properties")).keySet())
                .contains("id", "name", "birthYearMin", "birthYearMax", "capacity", "occupied", "available", "nextCategoryId", "active");
    }

    @Test
    @SuppressWarnings("unchecked")
    void committeeEndpointsAreDocumentedAndTheirResponsesMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/players/{playerId}/assignments", "/players/{playerId}/rejections",
                "/players/{playerId}/assignment");
        assertThat(((Map<String, Object>) paths.get("/players/{playerId}/assignments")).keySet()).contains("post");
        assertThat(((Map<String, Object>) paths.get("/players/{playerId}/rejections")).keySet()).contains("post");
        assertThat(((Map<String, Object>) paths.get("/players/{playerId}/assignment")).keySet()).contains("get");

        // Las respuestas documentadas deben coincidir campo a campo con los DTO reales.
        for (Class<?> dto : List.of(
                co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.AssignmentResponse.class,
                co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.DecisionResponse.class)) {
            Map<String, Object> documented = (Map<String, Object>) schemas.get(dto.getSimpleName());
            List<String> actual = Arrays.stream(dto.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
            assertThat(((Map<String, Object>) documented.get("properties")).keySet()).containsExactlyInAnyOrderElementsOf(actual);
        }
        // Las solicitudes se componen del acta (MinuteFields) más los campos propios de cada decisión.
        for (String request : List.of("AssignCategoryRequest", "RejectEnrollmentRequest")) {
            assertThat(((Map<String, Object>) schemas.get(request)).get("allOf") != null).isTrue();
        }
        List<String> assignFields = Arrays.stream(
                co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.AssignCategoryRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        Map<String, Object> minute = (Map<String, Object>) schemas.get("MinuteFields");
        Map<String, Object> own = (Map<String, Object>) ((List<Map<String, Object>>) ((Map<String, Object>) schemas.get("AssignCategoryRequest")).get("allOf")).get(1);
        java.util.Set<String> documentedFields = new java.util.HashSet<>(((Map<String, Object>) minute.get("properties")).keySet());
        documentedFields.addAll(((Map<String, Object>) own.get("properties")).keySet());
        assertThat(documentedFields).containsExactlyInAnyOrderElementsOf(assignFields);
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentsAndFilesEndpointsAreDocumentedAndTheirResponsesMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/files", "/files/{fileId}", "/fees", "/players/{playerId}/registration",
                "/players/{playerId}/activation", "/players/{playerId}/payments", "/payments/{paymentId}/void",
                "/players/{playerId}/account", "/billing/monthly");
        assertThat(((Map<String, Object>) paths.get("/fees")).keySet()).contains("get", "post");

        String payments = "co.edu.unipamplona.canchatrack.payments.infrastructure.PaymentsDtos$";
        String players = "co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos$";
        for (String dto : List.of(payments + "FeeResponse", payments + "ChargeResponse", payments + "PaymentResponse",
                payments + "AccountResponse", payments + "CreateFeeRequest", payments + "RecordPaymentRequest",
                payments + "VoidPaymentRequest", players + "RegistrationResponse", players + "StartRegistrationRequest",
                players + "MonthlyBillingRequest", players + "MonthlyBillingResponse",
                "co.edu.unipamplona.canchatrack.documents.infrastructure.FileController$FileResponse")) {
            Class<?> type = Class.forName(dto);
            Map<String, Object> documented = (Map<String, Object>) schemas.get(type.getSimpleName());
            assertThat(documented).as("esquema %s", type.getSimpleName()).isNotNull();
            List<String> actual = Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
            assertThat(((Map<String, Object>) documented.get("properties")).keySet())
                    .as("campos de %s", type.getSimpleName()).containsExactlyInAnyOrderElementsOf(actual);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schedulingAndAttendanceEndpointsAreDocumentedAndTheirResponsesMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/venues", "/events", "/events/{eventId}", "/events/{eventId}/cancel",
                "/events/{eventId}/attendance", "/events/{eventId}/attendance/close",
                "/events/{eventId}/attendance/corrections", "/players/{playerId}/attendance");
        assertThat(((Map<String, Object>) paths.get("/events/{eventId}/attendance")).keySet()).contains("get", "post");

        String scheduling = "co.edu.unipamplona.canchatrack.scheduling.infrastructure.SchedulingDtos$";
        String attendance = "co.edu.unipamplona.canchatrack.attendance.infrastructure.AttendanceDtos$";
        for (String dto : List.of(scheduling + "CreateVenueRequest", scheduling + "VenueResponse", scheduling + "ScheduleEventRequest",
                scheduling + "CancelEventRequest", scheduling + "EventResponse", attendance + "MarkRequest", attendance + "SyncRequest",
                attendance + "SyncResponse", attendance + "RejectionResponse", attendance + "RosterEntryResponse",
                attendance + "AttendanceResponse", attendance + "CloseResponse", attendance + "CorrectionRequest",
                attendance + "CorrectionResponse", attendance + "SummaryResponse")) {
            Class<?> type = Class.forName(dto);
            Map<String, Object> documented = (Map<String, Object>) schemas.get(type.getSimpleName());
            assertThat(documented).as("esquema %s", type.getSimpleName()).isNotNull();
            List<String> actual = Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
            assertThat(((Map<String, Object>) documented.get("properties")).keySet())
                    .as("campos de %s", type.getSimpleName()).containsExactlyInAnyOrderElementsOf(actual);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void evaluationEndpointsAreDocumentedAndSchemasMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/players/{playerId}/evaluation-rounds", "/evaluation-rounds/{roundId}",
                "/evaluation-rounds/{roundId}/decision", "/evaluation-rounds/{roundId}/period",
                "/evaluation-rounds/{roundId}/close", "/players/{playerId}/promotion");
        Map<String, Object> decision = (Map<String, Object>) ((Map<String, Object>) paths.get("/evaluation-rounds/{roundId}/decision")).get("put");
        assertThat(decision).doesNotContainKey("security");

        Map<String, Object> verdict = (Map<String, Object>) schemas.get("EvaluationVerdict");
        assertThat((List<String>) verdict.get("enum")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(co.edu.unipamplona.canchatrack.evaluations.domain.Verdict.values()).map(Enum::name).toList());

        String dtos = "co.edu.unipamplona.canchatrack.evaluations.infrastructure.EvaluationDtos$";
        for (String name : List.of("OpenRoundRequest", "ChangePeriodRequest", "DecisionRequest", "CloseRoundRequest",
                "PromoteRequest", "DecisionResponse", "RoundResponse", "PromotionResponse")) {
            Class<?> type = Class.forName(dtos + name);
            Map<String, Object> documented = (Map<String, Object>) schemas.get(name);
            assertThat(documented).as("esquema %s", name).isNotNull();
            List<String> actual = Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
            assertThat(((Map<String, Object>) documented.get("properties")).keySet())
                    .as("campos de %s", name).containsExactlyInAnyOrderElementsOf(actual);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerStatusEnumMatchesTheDomain() throws Exception {
        Map<String, Object> components = (Map<String, Object>) load().get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> playerStatus = (Map<String, Object>) schemas.get("PlayerStatus");

        List<String> domain = Arrays.stream(co.edu.unipamplona.canchatrack.players.domain.PlayerStatus.values())
                .map(Enum::name).toList();
        assertThat((List<String>) playerStatus.get("enum")).containsExactlyInAnyOrderElementsOf(domain);
    }

    @Test
    @SuppressWarnings("unchecked")
    void guardianRoleCannotBeAssignedThroughTheUsersEndpoint() throws Exception {
        Map<String, Object> components = (Map<String, Object>) load().get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> staffRole = (Map<String, Object>) schemas.get("StaffRole");

        assertThat((List<String>) staffRole.get("enum")).doesNotContain("GUARDIAN");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SPEC)) {
            assertThat(in).as("%s en el classpath", SPEC).isNotNull();
            return (Map<String, Object>) new Yaml().load(in);
        }
    }
}
