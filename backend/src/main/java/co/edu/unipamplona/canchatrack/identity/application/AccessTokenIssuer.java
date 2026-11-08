package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;

/** Puerto de salida: emite el access token (JWT corto, sin estado) con los roles vigentes al momento de emitirlo. */
public interface AccessTokenIssuer {

    IssuedAccessToken issue(UUID userId, Set<RoleCode> roles, Instant now);

    record IssuedAccessToken(String token, Instant expiresAt) {
    }
}
