package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.domain.MfaStep;

/** Puerto de salida: factor TOTP, códigos de respaldo y desafíos. Todo exige una transacción activa. */
public interface MfaStore {

    /** @param lastUsedStep último intervalo aceptado; {@code null} si aún no se usó */
    record Factor(UUID userId, byte[] sealedSecret, boolean active, Long lastUsedStep) {
    }

    record NewChallenge(UUID id, UUID userId, String tokenHash, MfaStep purpose, Instant createdAt, Instant expiresAt,
                        ClientOrigin origin) {
    }

    record Challenge(UUID id, UUID userId, MfaStep purpose, Instant expiresAt, int attempts, Instant consumedAt) {

        public boolean usableAt(Instant now) {
            return consumedAt == null && expiresAt.isAfter(now);
        }
    }

    /** Bloquea la fila: dos verificaciones simultáneas de la misma cuenta se serializan. */
    Optional<Factor> findFactorForUpdate(UUID userId);

    Optional<Factor> findFactor(UUID userId);

    /**
     * Guarda (o reemplaza) un factor sin confirmar.
     *
     * @return {@code false} si ya hay uno activo, que no se toca
     */
    boolean savePendingFactor(UUID userId, byte[] sealedSecret, Instant at);

    void activateFactor(UUID userId, long usedStep, Instant at);

    void recordUsedStep(UUID userId, long step);

    /** Borra el factor y sus códigos de respaldo (reinicio por la coordinación). */
    void deleteFactorAndCodes(UUID userId);

    void insertChallenge(NewChallenge challenge);

    Optional<Challenge> findChallengeForUpdate(String tokenHash);

    void recordChallengeAttempt(UUID challengeId);

    void consumeChallenge(UUID challengeId, Instant at);

    /** Invalida los desafíos aún abiertos de la cuenta (un desafío nuevo reemplaza a los anteriores). */
    void consumeOpenChallenges(UUID userId, Instant at);

    /** Reemplaza todos los códigos de respaldo por los dados. */
    void replaceBackupCodes(UUID userId, List<String> fingerprints, Instant at);

    /** @return {@code true} si el código existía y estaba sin usar; lo marca usado en el mismo paso */
    boolean consumeBackupCode(UUID userId, String fingerprint, Instant at);

    int countUnusedBackupCodes(UUID userId);

    /** Borra desafíos vencidos o consumidos anteriores al corte. */
    int deleteChallengesBefore(Instant cutoff);
}
