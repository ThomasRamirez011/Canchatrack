package co.edu.unipamplona.canchatrack.identity.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.OptionalLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Contraseña de un solo uso basada en tiempo (RFC 6238 sobre HOTP de RFC 4226): HMAC-SHA1, 6 dígitos, intervalos de
 * 30 segundos. Son los parámetros que aceptan Google Authenticator, Microsoft Authenticator y compañía.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    public static final int SECRET_BYTES = 20;

    private static final int[] POWERS = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000};
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    public static byte[] newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return secret;
    }

    /** Intervalo de 30 s al que pertenece el instante. */
    public static long stepAt(Instant instant) {
        return Math.floorDiv(instant.getEpochSecond(), PERIOD_SECONDS);
    }

    /** Código de 6 dígitos del intervalo dado (con ceros a la izquierda). */
    public static String code(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % POWERS[DIGITS]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 no disponible", e);
        }
    }

    /**
     * Comprueba el código enviado contra el intervalo actual y {@code window} intervalos a cada lado (tolera el reloj
     * del teléfono). Solo se acepta un intervalo posterior a {@code lastUsedStep}: un código ya usado no sirve otra vez.
     *
     * @return el intervalo que coincidió, o vacío si el código no es válido o ya se usó
     */
    public static OptionalLong verify(byte[] secret, String submitted, Instant now, int window, Long lastUsedStep) {
        if (submitted == null || submitted.length() != DIGITS || !submitted.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return OptionalLong.empty();
        }
        byte[] expectedBytes = submitted.getBytes(StandardCharsets.US_ASCII);
        long current = stepAt(now);
        OptionalLong match = OptionalLong.empty();
        // Se recorren todos los intervalos aunque ya haya coincidencia, para que el tiempo no delate cuál fue.
        for (long step = current - window; step <= current + window; step++) {
            boolean equal = MessageDigest.isEqual(code(secret, step).getBytes(StandardCharsets.US_ASCII), expectedBytes);
            boolean fresh = lastUsedStep == null || step > lastUsedStep;
            if (equal && fresh && (match.isEmpty() || step > match.getAsLong())) {
                match = OptionalLong.of(step);
            }
        }
        return match;
    }

    /** URI {@code otpauth://} que las aplicaciones leen del código QR o aceptan pegada. */
    public static String otpauthUri(String issuer, String account, byte[] secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/" + label + "?secret=" + Base32.encode(secret) + "&issuer=" + encode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
