package co.edu.unipamplona.canchatrack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/** La lista blanca de orígenes CORS rechaza todo lo que abra la API de más. */
class CorsOriginsTest {

    @Test
    void acceptsExactHttpsOriginsAndLoopbackHttp() {
        assertThat(CorsOrigins.violations(List.of(
                "https://app.club.example",
                "https://app.club.example:8443",
                "http://localhost:3000",
                "http://127.0.0.1:5173",
                "http://[::1]:3000"))).isEmpty();
    }

    @Test
    void emptyOrMissingListMeansNoCors() {
        assertThat(CorsOrigins.violations(List.of())).isEmpty();
        assertThat(CorsOrigins.violations(null)).isEmpty();
    }

    @Test
    void rejectsWildcards() {
        assertThat(CorsOrigins.violations(List.of("*"))).hasSize(1);
        assertThat(CorsOrigins.violations(List.of("https://*.club.example"))).hasSize(1);
    }

    @Test
    void rejectsPathsTrailingSlashQueryAndCredentials() {
        assertThat(CorsOrigins.violations(List.of("https://app.club.example/"))).hasSize(1);
        assertThat(CorsOrigins.violations(List.of("https://app.club.example/app"))).hasSize(1);
        assertThat(CorsOrigins.violations(List.of("https://app.club.example?x=1"))).hasSize(1);
        assertThat(CorsOrigins.violations(List.of("https://user:pw@app.club.example"))).hasSize(1);
    }

    @Test
    void rejectsPlainHttpOutsideLoopbackAndOtherSchemes() {
        assertThat(CorsOrigins.violations(List.of("http://app.club.example"))).hasSize(1);
        assertThat(CorsOrigins.violations(List.of("ftp://app.club.example"))).hasSize(1);
        assertThat(CorsOrigins.violations(List.of("app.club.example"))).hasSize(1);
    }

    @Test
    void rejectsBlankEntries() {
        assertThat(CorsOrigins.violations(Arrays.asList("https://app.club.example", " ", null))).hasSize(2);
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThat(CorsOrigins.violations(List.of("*", "http://app.club.example", "https://ok.club.example")))
                .hasSize(2);
    }

    @Test
    void propertiesFailFastWithTheFullMessageAndTrimValues() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*", "http://app.club.example")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("comodines")
                .hasMessageContaining("https");
        assertThat(new CorsProperties(List.of(" https://app.club.example ")).allowedOrigins())
                .containsExactly("https://app.club.example");
        assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
    }
}
