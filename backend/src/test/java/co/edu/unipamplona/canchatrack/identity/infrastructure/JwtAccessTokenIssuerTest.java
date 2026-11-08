package co.edu.unipamplona.canchatrack.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import co.edu.unipamplona.canchatrack.identity.application.AccessTokenIssuer.IssuedAccessToken;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.shared.security.JwtProperties;

class JwtAccessTokenIssuerTest {

    @Test
    void issuesAVerifiableTokenWithSubjectIssuerRolesAndLifetime() {
        SecretKey key = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtProperties properties = new JwtProperties("ignorado", "canchatrack", Duration.ofMinutes(15));
        JwtAccessTokenIssuer issuer = new JwtAccessTokenIssuer(new NimbusJwtEncoder(new ImmutableSecret<>(key)), properties);
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        IssuedAccessToken issued = issuer.issue(userId, EnumSet.of(RoleCode.EVALUATOR, RoleCode.COACH), now);

        Jwt jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().decode(issued.token());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("canchatrack");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("COACH", "EVALUATOR");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(issued.expiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
        assertThat(jwt.getExpiresAt()).isEqualTo(issued.expiresAt());
    }
}
