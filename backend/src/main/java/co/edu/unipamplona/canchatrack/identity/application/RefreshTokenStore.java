package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.domain.RefreshToken;

/** Puerto de salida: almacén de refresh tokens (solo hashes). Exige una transacción activa. */
public interface RefreshTokenStore {

    void insert(NewRefreshToken token);

    /** Bloquea la fila: dos canjes simultáneos del mismo token no pueden ambos tener éxito. */
    Optional<RefreshToken> findByHashForUpdate(String tokenHash);

    void markUsed(UUID id, Instant at);

    /** Revoca todos los tokens aún vigentes de la sesión (familia). Idempotente. */
    void revokeFamily(UUID familyId, Instant at, String reason);

    /** Revoca todas las sesiones vigentes de la cuenta (cambio o restablecimiento de contraseña). Idempotente. */
    void revokeAllOfUser(UUID userId, Instant at, String reason);

    /**
     * Borra los tokens que ya no sirven de nada: de sesiones vencidas o revocadas antes de {@code cutoff}. Un token
     * borrado se rechaza igual que uno desconocido, así que no se pierde ninguna defensa.
     *
     * @return filas borradas
     */
    int deleteInactiveBefore(Instant cutoff);

    record NewRefreshToken(
            UUID id,
            UUID userId,
            UUID familyId,
            String tokenHash,
            Instant issuedAt,
            Instant expiresAt,
            Instant familyExpiresAt,
            ClientOrigin origin) {
    }
}
