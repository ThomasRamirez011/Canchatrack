package co.edu.unipamplona.canchatrack.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh token de una sesión (el valor opaco no se guarda, solo su hash). Todos los tokens rotados de una misma
 * sesión comparten {@code familyId}.
 */
public record RefreshToken(
        UUID id,
        UUID userId,
        UUID familyId,
        Instant issuedAt,
        Instant expiresAt,
        Instant familyExpiresAt,
        Instant usedAt,
        Instant revokedAt) {

    public enum Status {
        ACTIVE,
        /** Vencido por su propia vigencia o por el tope absoluto de la sesión. */
        EXPIRED,
        /** Ya fue canjeado por uno nuevo: presentarlo otra vez indica reutilización (posible robo). */
        USED,
        REVOKED
    }

    public RefreshToken {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(familyId, "familyId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(familyExpiresAt, "familyExpiresAt");
    }

    public Status statusAt(Instant now) {
        if (revokedAt != null) {
            return Status.REVOKED;
        }
        if (usedAt != null) {
            return Status.USED;
        }
        if (!expiresAt.isAfter(now) || !familyExpiresAt.isAfter(now)) {
            return Status.EXPIRED;
        }
        return Status.ACTIVE;
    }
}
