package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.identity.domain.MfaStep;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;

/**
 * Decide si una cuenta debe presentar segundo factor y abre el desafío que une la contraseña con el código. No es
 * transaccional por sí mismo: se ejecuta dentro de la transacción del inicio de sesión.
 */
@Service
public class MfaService implements MfaGate {

    private final MfaStore store;
    private final MfaSettings settings;

    public MfaService(MfaStore store, MfaSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    @Override
    public boolean isRequiredFor(UserAccount account) {
        return settings.enabled() && account.roles().stream().anyMatch(settings.requiredRoles()::contains);
    }

    @Override
    public boolean hasActiveFactor(UUID userId) {
        return store.findFactor(userId).map(MfaStore.Factor::active).orElse(false);
    }

    @Override
    public SessionResult.MfaRequired challenge(UserAccount account, Instant now, ClientOrigin origin) {
        // Un desafío nuevo reemplaza a los anteriores: solo el último inicio de sesión puede completarse.
        store.consumeOpenChallenges(account.id(), now);
        MfaStep step = hasActiveFactor(account.id()) ? MfaStep.VERIFY : MfaStep.ENROLL;
        String raw = OpaqueToken.generate();
        Instant expiresAt = now.plus(settings.challengeTtl());
        store.insertChallenge(new MfaStore.NewChallenge(UUID.randomUUID(), account.id(), OpaqueToken.sha256Hex(raw),
                step, now, expiresAt, origin));
        return new SessionResult.MfaRequired(step, raw, expiresAt);
    }

    public MfaSettings settings() {
        return settings;
    }
}
