package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.UUID;

import co.edu.unipamplona.canchatrack.identity.application.MfaCrypto;

/** Sustituto cuando el MFA está apagado y no hay llave: si algo lo usa, falla con un mensaje claro. */
class UnconfiguredMfaCrypto implements MfaCrypto {

    private static IllegalStateException unavailable() {
        return new IllegalStateException("El MFA está deshabilitado y no hay llave de cifrado configurada.");
    }

    @Override
    public byte[] seal(UUID owner, byte[] secret) {
        throw unavailable();
    }

    @Override
    public byte[] open(UUID owner, byte[] sealed) {
        throw unavailable();
    }

    @Override
    public String fingerprint(UUID owner, String normalizedCode) {
        throw unavailable();
    }
}
