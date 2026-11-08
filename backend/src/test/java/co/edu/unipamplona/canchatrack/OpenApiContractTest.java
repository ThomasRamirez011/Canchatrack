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

    @SuppressWarnings("unchecked")
    private Map<String, Object> load() throws Exception {
        try (InputStream in = getClass().getResourceAsStream(SPEC)) {
            assertThat(in).as("%s en el classpath", SPEC).isNotNull();
            return (Map<String, Object>) new Yaml().load(in);
        }
    }
}
