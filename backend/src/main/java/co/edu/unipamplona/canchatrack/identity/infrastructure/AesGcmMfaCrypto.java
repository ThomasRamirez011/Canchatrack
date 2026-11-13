package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import co.edu.unipamplona.canchatrack.identity.application.MfaCrypto;

/**
 * AES-256-GCM para el secreto TOTP (el dueño viaja como dato asociado autenticado) y HMAC-SHA256 para la huella de los
 * códigos de respaldo. La llave maestra llega por configuración ({@code MFA_ENCRYPTION_KEY}, 32 bytes en Base64) y
 * nunca se guarda en la base: quien robe solo la base no puede abrir ni los secretos ni los códigos.
 */
class AesGcmMfaCrypto implements MfaCrypto {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec cipherKey;
    private final SecretKeySpec macKey;
    private final SecureRandom random = new SecureRandom();

    /** @param base64Key llave maestra de 32 bytes en Base64 */
    AesGcmMfaCrypto(String base64Key) {
        byte[] master;
        try {
            master = Base64.getDecoder().decode(base64Key == null ? "" : base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("canchatrack.identity.mfa.encryption-key no es Base64 válido.", e);
        }
        if (master.length != 32) {
            throw new IllegalStateException(
                    "canchatrack.identity.mfa.encryption-key debe ser de 32 bytes (Base64); tiene " + master.length + ".");
        }
        // Llaves distintas para cifrar y para las huellas, derivadas de la maestra.
        this.cipherKey = new SecretKeySpec(derive(master, "cipher"), "AES");
        this.macKey = new SecretKeySpec(derive(master, "fingerprint"), "HmacSHA256");
        Arrays.fill(master, (byte) 0);
    }

    @Override
    public byte[] seal(UUID owner, byte[] secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, cipherKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(ownerBytes(owner));
            byte[] encrypted = cipher.doFinal(secret);
            return ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el secreto MFA.", e);
        }
    }

    @Override
    public byte[] open(UUID owner, byte[] sealed) {
        try {
            if (sealed == null || sealed.length <= IV_BYTES) {
                throw new IllegalStateException("Secreto MFA con formato inválido.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, cipherKey, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            cipher.updateAAD(ownerBytes(owner));
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (GeneralSecurityException e) {
            // Llave equivocada, fila alterada o secreto de otro usuario.
            throw new IllegalStateException("No se pudo abrir el secreto MFA.", e);
        }
    }

    @Override
    public String fingerprint(UUID owner, String normalizedCode) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(macKey);
            mac.update(ownerBytes(owner));
            mac.update((byte) '|');
            return HexFormat.of().formatHex(mac.doFinal(normalizedCode.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    private static byte[] derive(byte[] master, String label) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(master, "HmacSHA256"));
            return mac.doFinal(("canchatrack-mfa|" + label).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    private static byte[] ownerBytes(UUID owner) {
        return ByteBuffer.allocate(16).putLong(owner.getMostSignificantBits()).putLong(owner.getLeastSignificantBits())
                .array();
    }
}
