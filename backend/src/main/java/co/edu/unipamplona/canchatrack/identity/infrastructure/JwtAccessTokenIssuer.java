package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.identity.application.AccessTokenIssuer;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.shared.security.JwtProperties;

/**
 * Emite el access token: {@code sub} = id de la cuenta, {@code roles} = códigos de rol vigentes, {@code jti} único.
 * Los roles viajan en el token, así que un cambio de roles se refleja al renovar o al vencer (≤ {@code accessTtl}).
 */
@Component
class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder encoder;
    private final JwtProperties properties;

    JwtAccessTokenIssuer(JwtEncoder encoder, JwtProperties properties) {
        this.encoder = encoder;
        this.properties = properties;
    }

    @Override
    public IssuedAccessToken issue(UUID userId, Set<RoleCode> roles, Instant now) {
        Instant expiresAt = now.plus(properties.accessTtl());
        List<String> roleCodes = roles.stream().map(RoleCode::name).sorted(Comparator.naturalOrder()).toList();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("roles", roleCodes)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(token, expiresAt);
    }
}
