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
