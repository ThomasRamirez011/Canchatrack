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

    boolean existsByEmail(String normalizedEmail);

    long count();

    void recordFailedLogin(UUID id, int failedAttempts, Instant lockedUntil);

    void recordSuccessfulLogin(UUID id, Instant at);

    /** @throws DuplicateEmailException si el correo ya existe (índice único sobre {@code lower(email)}) */
    UserAccount create(NewUser user);

    record NewUser(String email, String fullName, String passwordHash, Set<RoleCode> roles, UUID createdBy) {
    }
}
