package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Instant;

/** Credenciales entregadas al cliente. {@code refreshToken} es el valor opaco: solo existe en esta respuesta. */
public record TokenPair(String accessToken, Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) {

    @Override
    public String toString() {
        return "TokenPair[accessExpiresAt=" + accessExpiresAt + ", refreshExpiresAt=" + refreshExpiresAt + "]";
    }
}
