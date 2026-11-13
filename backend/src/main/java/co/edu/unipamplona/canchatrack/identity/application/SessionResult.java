package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;

import co.edu.unipamplona.canchatrack.identity.domain.MfaStep;

/**
 * Resultado de abrir o renovar una sesión. El rechazo NO es una excepción: así la transacción se confirma y quedan
 * registrados el contador de intentos, el bloqueo y la auditoría. Tampoco lleva el motivo: el cliente nunca debe poder
 * distinguir "correo inexistente" de "contraseña incorrecta" o "cuenta bloqueada".
 */
public sealed interface SessionResult {

    record Opened(TokenPair tokens, UserView user) implements SessionResult {
    }

    record Rejected() implements SessionResult {
    }

    /**
     * Las credenciales eran correctas pero el correo aún no se confirmó. Solo se devuelve tras acertar la contraseña,
     * de modo que no permite descubrir qué correos existen.
     */
    record EmailNotVerified() implements SessionResult {
    }

    /**
     * La contraseña era correcta y la cuenta exige segundo factor: todavía no hay sesión. El cliente presenta
     * {@code challengeToken} junto con el código (o, si {@code step} es {@code ENROLL}, primero configura el factor).
     */
    record MfaRequired(MfaStep step, String challengeToken, Instant expiresAt) implements SessionResult {

        @Override
        public String toString() {
            return "MfaRequired[step=" + step + ", challengeToken=***]";
        }
    }
}
