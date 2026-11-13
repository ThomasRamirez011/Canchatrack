package co.edu.unipamplona.canchatrack.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class AesGcmMfaCryptoTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String OTHER_KEY = Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private final AesGcmMfaCrypto crypto = new AesGcmMfaCrypto(KEY);
    private final UUID owner = UUID.randomUUID();
    private final byte[] secret = "0123456789abcdefghij".getBytes();

    @Test
    void sealsAndOpensTheSecret() {
        byte[] sealed = crypto.seal(owner, secret);

        assertThat(sealed.length).isEqualTo(12 + secret.length + 16);
        assertThat(crypto.open(owner, sealed)).isEqualTo(secret);
    }

    @Test
    void theStoredBytesDoNotContainTheSecretAndChangeEveryTime() {
        byte[] first = crypto.seal(owner, secret);
        byte[] second = crypto.seal(owner, secret);

        assertThat(first).isNotEqualTo(second);
        assertThat(new String(first, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain(new String(secret));
    }

    @Test
    void aSecretCopiedToAnotherUsersRowCannotBeOpened() {
        byte[] sealed = crypto.seal(owner, secret);

        assertThatThrownBy(() -> crypto.open(UUID.randomUUID(), sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedOrTruncatedDataIsRejected() {
        byte[] sealed = crypto.seal(owner, secret);
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 1;

        assertThatThrownBy(() -> crypto.open(owner, tampered)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.open(owner, new byte[5])).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> crypto.open(owner, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherMasterKeyCannotOpenIt() {
        byte[] sealed = crypto.seal(owner, secret);

        assertThatThrownBy(() -> new AesGcmMfaCrypto(OTHER_KEY).open(owner, sealed))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fingerprintsAreStableKeyedAndPerOwner() {
        String fingerprint = crypto.fingerprint(owner, "ABCDEFGHJK");

        assertThat(fingerprint).matches("[0-9a-f]{64}");
        assertThat(crypto.fingerprint(owner, "ABCDEFGHJK")).isEqualTo(fingerprint);
        assertThat(crypto.fingerprint(owner, "ABCDEFGHJM")).isNotEqualTo(fingerprint);
        assertThat(crypto.fingerprint(UUID.randomUUID(), "ABCDEFGHJK")).as("depende del dueño").isNotEqualTo(fingerprint);
        assertThat(new AesGcmMfaCrypto(OTHER_KEY).fingerprint(owner, "ABCDEFGHJK")).as("depende de la llave")
                .isNotEqualTo(fingerprint);
    }

    @Test
    void theMasterKeyMustBeThirtyTwoBytesOfBase64() {
        assertThatThrownBy(() -> new AesGcmMfaCrypto(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmMfaCrypto("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmMfaCrypto("no es base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesGcmMfaCrypto(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
