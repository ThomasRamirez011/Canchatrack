package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.identity.application.PasswordHasher;

@Component
class BCryptPasswordHasher implements PasswordHasher {

    private final PasswordEncoder encoder;
    /** Hash de una contraseña aleatoria con el mismo costo: sirve para igualar tiempos cuando no hay contra qué comparar. */
    private final String dummyHash;

    BCryptPasswordHasher(PasswordEncoder encoder) {
        this.encoder = encoder;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String hash) {
        return hash != null && encoder.matches(rawPassword, hash);
    }

    @Override
    public void simulateVerification(String rawPassword) {
        encoder.matches(rawPassword, dummyHash);
    }
}
