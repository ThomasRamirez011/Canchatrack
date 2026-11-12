package co.edu.unipamplona.canchatrack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * CORS con la cadena de seguridad real: solo el origen de la lista blanca puede llamar desde un navegador, la
 * verificación previa no exige token y nunca se publica el comodín ni se aceptan credenciales de navegador.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CorsIT {

    private static final String ALLOWED = "https://app.canchatrack.test";
    private static final String OTHER = "https://evil.example";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PROTECTED = "/api/v1/players";

    @Autowired
    private MockMvc mvc;

    @Test
    void preflightFromAllowedOriginSucceedsWithoutToken() throws Exception {
        MvcResult result = mvc.perform(options(PROTECTED)
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andReturn();

        var response = result.getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST").contains("PATCH");
        assertThat(response.getHeader("Access-Control-Allow-Headers").toLowerCase())
                .contains("authorization").contains("idempotency-key");
        assertThat(response.getHeader("Access-Control-Max-Age")).isEqualTo("3600");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void preflightFromAnotherOriginIsRejected() throws Exception {
        MvcResult result = mvc.perform(options(PROTECTED)
                        .header("Origin", OTHER)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void preflightAskingForAnUnlistedHeaderOrMethodIsRejected() throws Exception {
        mvc.perform(options(PROTECTED)
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "x-admin-override"))
                .andExpect(status().isForbidden());
        mvc.perform(options(PROTECTED)
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "TRACE"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedErrorIsReadableByTheAllowedOriginAndExposesTheCorrelationId() throws Exception {
        MvcResult result = mvc.perform(get(PROTECTED).header("Origin", ALLOWED))
                .andExpect(status().isUnauthorized())
                .andReturn();
        var response = result.getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("X-Correlation-Id");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void requestFromAnotherOriginGetsNoCorsHeaders() throws Exception {
        MvcResult result = mvc.perform(get(PROTECTED).header("Origin", OTHER)).andReturn();
        assertThat(result.getResponse().getStatus()).isIn(401, 403);
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void neverAnswersWithTheWildcard() throws Exception {
        MvcResult result = mvc.perform(options(LOGIN)
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "POST"))
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNotEqualTo("*");
    }

    @Test
    void requestsWithoutAnOriginAreUnaffected() throws Exception {
        MvcResult result = mvc.perform(get(PROTECTED)).andExpect(status().isUnauthorized()).andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }
}
