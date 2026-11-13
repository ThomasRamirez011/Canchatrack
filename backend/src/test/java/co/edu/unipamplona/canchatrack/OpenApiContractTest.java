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
import co.edu.unipamplona.canchatrack.reports.domain.ExportRules;

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
        for (String name : List.of("OpenRoundRequest", "ChangePeriodRequest", "EvaluatorDecisionRequest", "CloseRoundRequest",
                "PromoteRequest", "EvaluatorDecisionResponse", "RoundResponse", "PromotionResponse")) {
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
    void competitionEndpointsAreDocumentedAndSchemasMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/tournaments", "/matches", "/matches/{matchId}", "/matches/{matchId}/cancel",
                "/matches/{matchId}/callup", "/matches/{matchId}/callup/{playerId}", "/matches/{matchId}/callup/confirm",
                "/matches/{matchId}/players/{playerId}/authorization", "/matches/{matchId}/result",
                "/players/{playerId}/matches");

        Map<String, Object> causes = (Map<String, Object>) schemas.get("IneligibilityCause");
        assertThat((List<String>) causes.get("enum")).containsExactlyInAnyOrder(
                co.edu.unipamplona.canchatrack.competitions.domain.Eligibility.NOT_ACTIVE,
                co.edu.unipamplona.canchatrack.competitions.domain.Eligibility.MEDICAL_CERTIFICATE_INVALID,
                co.edu.unipamplona.canchatrack.competitions.domain.Eligibility.AUTHORIZATION_INVALID,
                co.edu.unipamplona.canchatrack.competitions.domain.Eligibility.ARREARS);

        String dtos = "co.edu.unipamplona.canchatrack.competitions.infrastructure.CompetitionDtos$";
        for (String name : List.of("CreateTournamentRequest", "TournamentResponse", "ScheduleMatchRequest", "CancelMatchRequest",
                "MatchResponse", "CallupPlayersRequest", "CallupResponse", "MatchDetailResponse", "PlayerResultRequest",
                "MatchResultRequest", "MatchAuthorizationResponse", "PlayerMatchResponse")) {
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
    void documentEndpointsAreDocumentedAndSchemasMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/document-types", "/document-types/{typeId}", "/document-windows",
                "/players/{playerId}/documents", "/players/{playerId}/document-types/{typeId}/versions",
                "/players/{playerId}/documents/versions/{versionId}/file");
        assertThat(((Map<String, Object>) paths.get("/document-types")).keySet()).contains("get", "post");
        assertThat(((Map<String, Object>) paths.get("/document-windows")).keySet()).contains("get", "post");
        assertThat(((Map<String, Object>) paths.get("/players/{playerId}/documents")).keySet()).contains("get", "post");

        assertThat((List<String>) ((Map<String, Object>) schemas.get("DocumentStatus")).get("enum"))
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(
                        co.edu.unipamplona.canchatrack.documents.domain.DocumentRules.Status.values()).map(Enum::name).toList());

        String dtos = "co.edu.unipamplona.canchatrack.documents.infrastructure.DocumentDtos$";
        for (String name : List.of("CreateDocumentTypeRequest", "UpdateDocumentTypeRequest", "DocumentTypeResponse",
                "OpenDocumentWindowRequest", "DocumentWindowResponse", "DocumentVersionResponse", "PlayerDocumentResponse")) {
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
    void ersExportsAreDocumentedAsCsvWithTheDomainLimits() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, List<String>> expectedParameters = Map.of(
                "/reports/roster/export", List.of("categoryId"),
                "/reports/attendance/export", List.of("seasonId", "categoryId", "from", "to"),
                "/reports/matches/export", List.of("seasonId", "categoryId", "tournamentId", "from", "to"),
                "/reports/evaluations/export", List.of("seasonId", "categoryId", "from", "to"));

        expectedParameters.forEach((path, names) -> {
            Map<String, Object> get = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("get");
            Map<String, Object> ok = (Map<String, Object>) ((Map<String, Object>) get.get("responses")).get("200");
            assertThat(((Map<String, Object>) ok.get("content")).keySet()).containsExactly("text/csv");
            assertThat(((Map<String, Object>) get.get("responses")).keySet()).contains("401", "403", "422");
            List<Map<String, Object>> parameters = (List<Map<String, Object>>) get.get("parameters");
            assertThat(parameters.stream().map(p -> (String) p.get("name")).toList()).containsExactlyElementsOf(names);
            assertThat(parameters).allSatisfy(p -> assertThat(p.get("required")).isEqualTo(false));
            // Los límites que el contrato anuncia son los del dominio.
            assertThat((String) get.get("description")).contains(ExportRules.MAX_ROWS + " filas");
            if (names.contains("from")) {
                assertThat((String) get.get("description")).contains(ExportRules.MAX_RANGE_DAYS + " días");
            }
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void auditAndReportEndpointsAreDocumentedAndSchemasMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/audit/events", "/reports/receivables", "/reports/receivables/export",
                "/reports/season-summary");

        // Los límites de la consulta de auditoría documentados son los del dominio.
        Map<String, Object> audit = (Map<String, Object>) ((Map<String, Object>) paths.get("/audit/events")).get("get");
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) audit.get("parameters");
        Map<String, Object> limit = (Map<String, Object>) parameters.stream().filter(p -> "limit".equals(p.get("name")))
                .findFirst().orElseThrow().get("schema");
        assertThat(limit.get("maximum")).isEqualTo(co.edu.unipamplona.canchatrack.audit.domain.AuditQueryRules.MAX_LIMIT);
        assertThat(limit.get("default")).isEqualTo(co.edu.unipamplona.canchatrack.audit.domain.AuditQueryRules.DEFAULT_LIMIT);
        Map<String, Object> eventProperties = (Map<String, Object>) ((Map<String, Object>) schemas.get("AuditEventResponse")).get("properties");
        assertThat((List<String>) ((Map<String, Object>) eventProperties.get("outcome")).get("enum"))
                .containsExactlyInAnyOrderElementsOf(co.edu.unipamplona.canchatrack.audit.domain.AuditQueryRules.OUTCOMES);

        // La exportación declara CSV y la consulta, JSON.
        Map<String, Object> export = (Map<String, Object>) ((Map<String, Object>) paths.get("/reports/receivables/export")).get("get");
        Map<String, Object> ok = (Map<String, Object>) ((Map<String, Object>) export.get("responses")).get("200");
        assertThat(((Map<String, Object>) ok.get("content")).keySet()).containsExactly("text/csv");

        record Group(String dtos, List<String> names) {
        }
        List<Group> groups = List.of(
                new Group("co.edu.unipamplona.canchatrack.audit.infrastructure.AuditDtos$",
                        List.of("AuditEventResponse", "AuditEventPageResponse")),
                new Group("co.edu.unipamplona.canchatrack.reports.infrastructure.ReportDtos$",
                        List.of("ReceivableLineResponse", "CategoryReceivableResponse", "ReceivablesReportResponse",
                                "SeasonInfoResponse", "SeasonRosterLineResponse", "SeasonEventCountResponse",
                                "SeasonAttendanceResponse", "SeasonMatchesResponse", "SeasonBillingResponse",
                                "SeasonSummaryResponse")));
        for (Group group : groups) {
            for (String name : group.names()) {
                Class<?> type = Class.forName(group.dtos() + name);
                Map<String, Object> documented = (Map<String, Object>) schemas.get(name);
                assertThat(documented).as("esquema %s", name).isNotNull();
                List<String> actual = Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
                assertThat(((Map<String, Object>) documented.get("properties")).keySet())
                        .as("campos de %s", name).containsExactlyInAnyOrderElementsOf(actual);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void seasonReportAndClosureEndpointsAreDocumentedAndSchemasMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/players/{playerId}/season-reports", "/season-reports/verify",
                "/season-reports/{reportId}", "/players/{playerId}/season-closure",
                "/players/{playerId}/season-closure/reopen");

        // Los límites documentados de la reapertura son los del dominio.
        Map<String, Object> reopen = (Map<String, Object>) schemas.get("SeasonReopenRequest");
        Map<String, Object> reopenProperties = (Map<String, Object>) reopen.get("properties");
        assertThat(((Map<String, Object>) reopenProperties.get("reason")).get("maxLength"))
                .isEqualTo(co.edu.unipamplona.canchatrack.reports.domain.ReportRules.MAX_REASON);
        assertThat(((Map<String, Object>) reopenProperties.get("detail")).get("maxLength"))
                .isEqualTo(co.edu.unipamplona.canchatrack.reports.domain.ReportRules.MAX_DETAIL);

        String dtos = "co.edu.unipamplona.canchatrack.reports.infrastructure.SeasonReportDtos$";
        for (String name : List.of("SeasonReportRequest", "SeasonReportResponse", "SeasonReportVerificationResponse",
                "SeasonClosureRequest", "SeasonReopenRequest", "SeasonClosureResponse")) {
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

    @Test
    @SuppressWarnings("unchecked")
    void eventSeriesAndCalendarFiltersAreDocumentedAndMatchTheDomain() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        assertThat(paths.keySet()).contains("/event-series", "/event-series/{seriesId}", "/event-series/{seriesId}/cancel");
        assertThat(((Map<String, Object>) paths.get("/event-series")).keySet()).containsExactly("post");
        Map<String, Object> schedule = (Map<String, Object>) ((Map<String, Object>) paths.get("/event-series")).get("post");
        assertThat(((Map<String, Object>) schedule.get("responses")).keySet()).contains("201", "409", "422");

        Map<String, Object> listEvents = (Map<String, Object>) ((Map<String, Object>) paths.get("/events")).get("get");
        List<String> filters = ((List<Map<String, Object>>) listEvents.get("parameters")).stream().map(p -> (String) p.get("name")).toList();
        assertThat(filters).containsExactlyInAnyOrder("categoryId", "venueId", "headCoachId", "eventType", "status", "seriesId", "from", "to");

        // Los tipos y estados del filtro y los días de la serie son los del dominio.
        List<Map<String, Object>> parameters = (List<Map<String, Object>>) listEvents.get("parameters");
        List<String> typeEnum = (List<String>) ((Map<String, Object>) parameters.stream().filter(p -> "eventType".equals(p.get("name")))
                .findFirst().orElseThrow().get("schema")).get("enum");
        assertThat(typeEnum).containsExactlyInAnyOrderElementsOf(co.edu.unipamplona.canchatrack.scheduling.domain.SchedulingRules.ALL_EVENT_TYPES);
        List<String> statusEnum = (List<String>) ((Map<String, Object>) parameters.stream().filter(p -> "status".equals(p.get("name")))
                .findFirst().orElseThrow().get("schema")).get("enum");
        assertThat(statusEnum).containsExactlyInAnyOrderElementsOf(co.edu.unipamplona.canchatrack.scheduling.domain.SchedulingRules.EVENT_STATUSES);
        Map<String, Object> request = (Map<String, Object>) schemas.get("ScheduleSeriesRequest");
        Map<String, Object> weekdays = (Map<String, Object>) ((Map<String, Object>) request.get("properties")).get("weekdays");
        List<String> days = (List<String>) ((Map<String, Object>) weekdays.get("items")).get("enum");
        assertThat(days).containsExactlyElementsOf(Arrays.stream(java.time.DayOfWeek.values()).map(Enum::name).toList());
        Map<String, Object> seriesType = (Map<String, Object>) ((Map<String, Object>) request.get("properties")).get("eventType");
        assertThat((List<String>) seriesType.get("enum")).containsExactlyInAnyOrderElementsOf(
                co.edu.unipamplona.canchatrack.scheduling.domain.SchedulingRules.EVENT_TYPES);
        Map<String, Object> count = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) schemas.get("SeriesResponse"))
                .get("properties")).get("occurrenceCount");
        assertThat(count.get("maximum")).isEqualTo(co.edu.unipamplona.canchatrack.scheduling.domain.RecurrenceRules.MAX_OCCURRENCES);
    }

    @Test
    @SuppressWarnings("unchecked")
    void enrollmentDocumentsAreDocumentedAndMatchTheDomain() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        Map<String, Object> request = (Map<String, Object>) schemas.get("SubmitEnrollmentRequest");
        Map<String, Object> documents = (Map<String, Object>) ((Map<String, Object>) request.get("properties")).get("documents");
        assertThat(documents.get("maxItems")).isEqualTo(co.edu.unipamplona.canchatrack.documents.application.EnrollmentDocumentService.MAX_ATTACHMENTS);
        assertThat(((Map<String, Object>) documents.get("items")).get("$ref")).isEqualTo("#/components/schemas/EnrollmentDocument");
        // Los documentos son opcionales: no figuran entre los campos obligatorios de la radicación.
        assertThat((List<String>) request.get("required")).doesNotContain("documents");

        Map<String, Object> attachment = (Map<String, Object>) schemas.get("EnrollmentDocument");
        assertThat((List<String>) attachment.get("required")).containsExactlyInAnyOrder("documentTypeId", "fileId");
        Map<String, Object> notes = (Map<String, Object>) ((Map<String, Object>) attachment.get("properties")).get("notes");
        assertThat(notes.get("maxLength")).isEqualTo(co.edu.unipamplona.canchatrack.documents.domain.DocumentRules.MAX_NOTES);

        // La constancia informa cuántos se adjuntaron y qué obligatorios faltan.
        Map<String, Object> receipt = (Map<String, Object>) schemas.get("EnrollmentResponse");
        assertThat((List<String>) receipt.get("required")).contains("attachedDocuments", "missingDocuments");
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountSecurityEndpointsAreDocumentedAndSchemasMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> responses = (Map<String, Object>) components.get("responses");

        List<String> publicPaths = List.of("/auth/password-reset/request", "/auth/password-reset/confirm",
                "/auth/email-verification/request", "/auth/email-verification/confirm");
        for (String path : publicPaths) {
            Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("post");
            assertThat(post.get("security")).as("%s no exige token", path).isEqualTo(List.of());
            assertThat(((Map<String, Object>) post.get("responses")).keySet()).as("respuestas de %s", path)
                    .contains("422", "429");
        }
        // Cambiar la contraseña exige sesión (hereda el esquema global).
        Map<String, Object> change = (Map<String, Object>) ((Map<String, Object>) paths.get("/auth/password")).get("post");
        assertThat(change).doesNotContainKey("security");
        assertThat(((Map<String, Object>) change.get("responses")).keySet()).contains("204", "401", "422", "429");
        // Las solicitudes de correo responden 202 siempre; los canjes, 204.
        for (String path : List.of("/auth/password-reset/request", "/auth/email-verification/request")) {
            Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("post");
            assertThat(((Map<String, Object>) post.get("responses")).keySet()).contains("202");
        }

        // El inicio de sesión documenta el 403 de correo sin confirmar y el 429 con Retry-After.
        Map<String, Object> login = (Map<String, Object>) ((Map<String, Object>) paths.get("/auth/login")).get("post");
        assertThat(((Map<String, Object>) login.get("responses")).keySet()).contains("403", "429");
        Map<String, Object> tooMany = (Map<String, Object>) responses.get("TooManyRequests");
        assertThat(((Map<String, Object>) tooMany.get("headers")).keySet()).containsExactly("Retry-After");
        assertThat(responses).containsKey("EmailNotVerified");

        // Las solicitudes y la cuenta documentadas coinciden campo a campo con los DTO.
        String dtos = "co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos$";
        for (String name : List.of("EmailRequest", "TokenRequest", "ResetPasswordRequest", "ChangePasswordRequest",
                "UserResponse")) {
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
    void mfaEndpointsAreDocumentedAndSchemasMatchTheDtos() throws Exception {
        Map<String, Object> spec = load();
        Map<String, Object> paths = (Map<String, Object>) spec.get("paths");
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("schemas");

        // Antes de tener sesión se usan con el desafío del inicio de sesión: no exigen token.
        for (String path : List.of("/auth/mfa/enrollment", "/auth/mfa/enrollment/confirm", "/auth/mfa/verify")) {
            Map<String, Object> post = (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get("post");
            assertThat(post.get("security")).as("%s no exige token", path).isEqualTo(List.of());
            assertThat(((Map<String, Object>) post.get("responses")).keySet()).as("respuestas de %s", path)
                    .contains("200", "401", "422", "429");
        }
        // El resto hereda el esquema global (bearerAuth).
        Map<String, Object> status = (Map<String, Object>) ((Map<String, Object>) paths.get("/auth/mfa")).get("get");
        Map<String, Object> backup = (Map<String, Object>) ((Map<String, Object>) paths.get("/auth/mfa/backup-codes")).get("post");
        Map<String, Object> reset = (Map<String, Object>) ((Map<String, Object>) paths.get("/users/{userId}/mfa/reset")).get("post");
        assertThat(status).doesNotContainKey("security");
        assertThat(backup).doesNotContainKey("security");
        assertThat(reset).doesNotContainKey("security");
        assertThat(((Map<String, Object>) backup.get("responses")).keySet()).contains("200", "401", "409", "422", "429");
        assertThat(((Map<String, Object>) reset.get("responses")).keySet()).contains("204", "401", "403", "404", "409");

        // El inicio de sesión documenta el 202 con el desafío.
        Map<String, Object> login = (Map<String, Object>) ((Map<String, Object>) paths.get("/auth/login")).get("post");
        assertThat(((Map<String, Object>) login.get("responses")).keySet()).contains("200", "202");

        // Los pasos del desafío son exactamente los del dominio.
        Map<String, Object> challenge = (Map<String, Object>) schemas.get("MfaChallengeResponse");
        Map<String, Object> step = (Map<String, Object>) ((Map<String, Object>) challenge.get("properties")).get("step");
        assertThat((List<String>) step.get("enum")).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(co.edu.unipamplona.canchatrack.identity.domain.MfaStep.values()).map(Enum::name).toList());

        // Cada esquema documentado coincide campo a campo con su DTO.
        String dtos = "co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos$";
        for (String name : List.of("MfaChallengeResponse", "MfaChallengeRequest", "MfaVerifyRequest", "MfaCodeRequest",
                "MfaEnrollmentResponse", "MfaEnrolledResponse", "MfaStatusResponse", "BackupCodesResponse")) {
            Class<?> type = Class.forName(dtos + name);
            Map<String, Object> documented = (Map<String, Object>) schemas.get(name);
            assertThat(documented).as("esquema %s", name).isNotNull();
            List<String> actual = Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
            assertThat(((Map<String, Object>) documented.get("properties")).keySet())
                    .as("campos de %s", name).containsExactlyInAnyOrderElementsOf(actual);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SPEC)) {
            assertThat(in).as("%s en el classpath", SPEC).isNotNull();
            return (Map<String, Object>) new Yaml().load(in);
        }
    }
}
