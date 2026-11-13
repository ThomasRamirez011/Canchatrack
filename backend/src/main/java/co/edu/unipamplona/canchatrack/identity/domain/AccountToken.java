package co.edu.unipamplona.canchatrack.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Token de un solo uso ya guardado (solo se conoce su hash, nunca el valor). Sirve una única vez y mientras no haya
 * vencido ni haya sido reemplazado por uno más reciente.
 */
public record AccountToken(
        UUID id,
        UUID userId,
        AccountTokenPurpose purpose,
        Instant expiresAt,
        Instant usedAt,
        Instant revokedAt) {

    public AccountToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** Aún se puede canjear: sin usar, sin revocar y con el vencimiento por delante. */
    public boolean isUsableAt(Instant now) {
        return usedAt == null && revokedAt == null && expiresAt.isAfter(now);
    }
}
