package co.edu.unipamplona.canchatrack.identity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generación y huella de tokens opacos. El valor se entrega una sola vez al cliente; en la base solo vive su SHA-256.
 * Con 256 bits de entropía aleatoria basta un hash rápido (no hace falta BCrypt para tokens de alta entropía).
 */
final class OpaqueToken {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 32;

    private OpaqueToken() {
    }

    static String generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }
}
