package co.edu.unipamplona.canchatrack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;

/**
 * Modo de demostración: con {@code canchatrack.security.cors.allow-any-origin=true} el front de vista previa puede llamar
 * desde cualquier origen (la autenticación es un token Bearer, sin cookies) y los navegadores reciben el permiso de red
 * privada para llegar a un servidor en {@code localhost}.
 */
@SpringBootTest(properties = "canchatrack.security.cors.allow-any-origin=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class CorsAnyOriginIT {

    private static final String ANY_ORIGIN = "https://vista-previa.figma.test";

    @Autowired
    private MockMvc mvc;

    @Test
    void preflightFromAnyOriginSucceedsAndAllowsPrivateNetworkAccess() throws Exception {
        MvcResult result = mvc.perform(options("/api/v1/dashboard/club")
                        .header("Origin", ANY_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization")
                        .header("Access-Control-Request-Private-Network", "true"))
                .andExpect(status().isOk())
                .andReturn();

        var response = result.getResponse();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ANY_ORIGIN);
        assertThat(response.getHeader("Access-Control-Allow-Private-Network")).isEqualTo("true");
        assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void actualRequestFromAnyOriginIsReadableByTheBrowser() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/dashboard/club").header("Origin", ANY_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andReturn();
        assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin")).isEqualTo(ANY_ORIGIN);
    }
}
