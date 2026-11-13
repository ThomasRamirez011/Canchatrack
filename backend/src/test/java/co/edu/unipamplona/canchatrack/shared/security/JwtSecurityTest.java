package co.edu.unipamplona.canchatrack.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;

/** El access token se valida con algoritmo, emisor y vigencia fijados; cualquier otra cosa se rechaza. */
class JwtSecurityTest {

    private static final String SECRET = base64("0123456789abcdef0123456789abcdef");
    private static final String OTHER_SECRET = base64("ffffffffffffffffffffffffffffffff");

    private final JwtProperties properties = new JwtProperties(SECRET, "canchatrack", Duration.ofMinutes(15));
    private final JwtConfig config = new JwtConfig();
    private final SecretKey key = config.jwtSecretKey(properties);
    private final JwtEncoder encoder = config.jwtEncoder(key);
    private final JwtDecoder decoder = config.jwtDecoder(key, properties);

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String token(JwtEncoder withEncoder, String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("5f1c3c3e-8a5e-4c43-9c43-2a3a8b0a1f10")
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of("COORDINATOR", "COACH"))
                .build();
        return withEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String validToken() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        return token(encoder, "canchatrack", now, now.plus(Duration.ofMinutes(15)));
    }

    @Test
    void validTokenRoundTrips() {
        Jwt jwt = decoder.decode(validToken());

        assertThat(jwt.getSubject()).isEqualTo("5f1c3c3e-8a5e-4c43-9c43-2a3a8b0a1f10");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("COORDINATOR", "COACH");
    }

    @Test
    void expiredTokenIsRejected() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String expired = token(encoder, "canchatrack", now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(30)));

        assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String foreign = token(encoder, "otro-emisor", now, now.plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> decoder.decode(foreign)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        JwtProperties other = new JwtProperties(OTHER_SECRET, "canchatrack", Duration.ofMinutes(15));
        JwtEncoder otherEncoder = config.jwtEncoder(config.jwtSecretKey(other));
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String forged = token(otherEncoder, "canchatrack", now, now.plus(Duration.ofMinutes(15)));

        assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = validToken().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"5f1c3c3e-8a5e-4c43-9c43-2a3a8b0a1f10\",\"roles\":[\"COORDINATOR\"],\"iss\":\"canchatrack\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void unsignedTokenWithAlgNoneIsRejected() {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"x\",\"roles\":[\"COORDINATOR\"],\"iss\":\"canchatrack\",\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> decoder.decode(header + "." + payload + ".")).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageIsRejected() {
        assertThatThrownBy(() -> decoder.decode("esto-no-es-un-jwt")).isInstanceOf(JwtException.class);
    }

    @Test
    void rolesClaimBecomesRoleAuthorities() {
        Jwt jwt = decoder.decode(validToken());

        List<String> authorities = SecurityConfig.jwtAuthenticationConverter().convert(jwt)
                .getAuthorities().stream().map(GrantedAuthority::getAuthority)
                // Spring Security 7 añade autoridades FACTOR_* (p. ej. FACTOR_BEARER); aquí solo importan los roles.
                .filter(authority -> authority.startsWith("ROLE_")).toList();

        assertThat(authorities).containsExactlyInAnyOrder("ROLE_COORDINATOR", "ROLE_COACH");
    }

    @Test
    void secretMustBePresentBase64AndAtLeast256Bits() {
        assertThatThrownBy(() -> JwtConfig.secretKey(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.secretKey("  ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.secretKey("###no-es-base64###")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.secretKey(base64("demasiado-corta"))).isInstanceOf(IllegalStateException.class);
        assertThat(JwtConfig.secretKey(SECRET).getAlgorithm()).isEqualTo("HmacSHA256");
    }

    @Test
    void propertiesNeverPrintTheSecret() {
        assertThat(properties.toString()).doesNotContain(SECRET);
    }
}
