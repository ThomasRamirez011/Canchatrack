package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;

/** Puerto de salida: persistencia de cuentas. Los métodos "ForUpdate" y de escritura exigen una transacción activa. */
public interface UserAccountRepository {

    /** Bloquea la fila para serializar intentos concurrentes de login sobre la misma cuenta. */
    Optional<UserAccount> findByEmailForUpdate(String normalizedEmail);

    Optional<UserAccount> findById(UUID id);

    /** Bloquea la fila: el cambio de contraseña y el contador de intentos de una misma cuenta se serializan. */
    Optional<UserAccount> findByIdForUpdate(UUID id);

    boolean existsByEmail(String normalizedEmail);

    long count();

    void recordFailedLogin(UUID id, int failedAttempts, Instant lockedUntil);

    void recordSuccessfulLogin(UUID id, Instant at);

    /**
     * Fija una contraseña nueva y levanta el bloqueo por intentos fallidos. Quien demuestra que controla el correo
     * (enlace de restablecimiento) también lo deja confirmado.
     */
    void updatePassword(UUID id, String passwordHash, Instant at);

    /** Confirma el correo; si ya estaba confirmado no cambia la fecha. */
    void markEmailVerified(UUID id, Instant at);

    /** @throws DuplicateEmailException si el correo ya existe (índice único sobre {@code lower(email)}) */
    UserAccount create(NewUser user);

    /**
     * @param emailVerified {@code true} si el correo ya es de fiar (lo dio la coordinación); {@code false} en el
     *                      autorregistro, que debe confirmarlo
     */
    record NewUser(String email, String fullName, String passwordHash, Set<RoleCode> roles, UUID createdBy,
                   boolean emailVerified) {

        /** Cuenta creada por la coordinación o por el arranque: el correo se da por confirmado. */
        public NewUser(String email, String fullName, String passwordHash, Set<RoleCode> roles, UUID createdBy) {
            this(email, fullName, passwordHash, roles, createdBy, true);
        }
    }
}
