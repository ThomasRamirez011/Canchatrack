package co.edu.unipamplona.canchatrack.identity.application;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.domain.AccountToken;
import co.edu.unipamplona.canchatrack.identity.domain.AccountTokenPurpose;

/** Puerto de salida: tokens de un solo uso (solo hashes). Exige una transacción activa. */
public interface AccountTokenStore {

    void insert(NewAccountToken token);

    /** Bloquea la fila: dos canjes simultáneos del mismo token no pueden ambos tener éxito. */
    Optional<AccountToken> findByHashForUpdate(String tokenHash);

    void markUsed(UUID id, Instant at);

    /** Invalida los tokens aún utilizables del usuario para ese propósito (al emitir uno nuevo o al canjear). */
    void revokeOutstanding(UUID userId, AccountTokenPurpose purpose, Instant at);

    /** Borra los tokens ya usados, revocados o vencidos antes de {@code cutoff}. @return filas borradas */
    int deleteFinishedBefore(Instant cutoff);

    record NewAccountToken(
            UUID id,
            UUID userId,
            AccountTokenPurpose purpose,
            String tokenHash,
            Instant createdAt,
            Instant expiresAt,
            InetAddress ip) {
    }
}
