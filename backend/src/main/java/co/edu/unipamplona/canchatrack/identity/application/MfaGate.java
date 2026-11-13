package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;

/**
 * Lo que el inicio de sesión necesita saber del segundo factor. Es una interfaz pequeña para que
 * {@link AuthenticationService} no dependa de toda la lógica del MFA.
 */
public interface MfaGate {

    /** Sin MFA: nadie lo debe. */
    MfaGate DISABLED = new MfaGate() {
        @Override
        public boolean isRequiredFor(UserAccount account) {
            return false;
        }

        @Override
        public boolean hasActiveFactor(UUID userId) {
            return false;
        }

        @Override
        public SessionResult.MfaRequired challenge(UserAccount account, Instant now, ClientOrigin origin) {
            throw new UnsupportedOperationException("MFA deshabilitado");
        }
    };

    boolean isRequiredFor(UserAccount account);

    boolean hasActiveFactor(UUID userId);

    /** Abre un desafío (reemplazando los anteriores) y lo devuelve para que el cliente lo presente con el código. */
    SessionResult.MfaRequired challenge(UserAccount account, Instant now, ClientOrigin origin);
}
