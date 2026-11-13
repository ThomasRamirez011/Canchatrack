package co.edu.unipamplona.canchatrack.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Vectores de RFC 4648 sección 10 (sin relleno). */
class Base32Test {

    private static String enc(String text) {
        return Base32.encode(text.getBytes(StandardCharsets.US_ASCII));
    }

    @Test
    void encodesTheRfcVectors() {
        assertThat(enc("")).isEqualTo("");
        assertThat(enc("f")).isEqualTo("MY");
        assertThat(enc("fo")).isEqualTo("MZXQ");
        assertThat(enc("foo")).isEqualTo("MZXW6");
        assertThat(enc("foob")).isEqualTo("MZXW6YQ");
        assertThat(enc("fooba")).isEqualTo("MZXW6YTB");
        assertThat(enc("foobar")).isEqualTo("MZXW6YTBOI");
    }

    @Test
    void decodesBackIgnoringCaseSpacesAndPadding() {
        assertThat(new String(Base32.decode("MZXW6YTBOI"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
        assertThat(new String(Base32.decode("mzxw 6ytb oi======"), StandardCharsets.US_ASCII)).isEqualTo("foobar");
    }

    @Test
    void roundTripsRandomSecrets() {
        for (int i = 0; i < 50; i++) {
            byte[] secret = Totp.newSecret();
            assertThat(Base32.decode(Base32.encode(secret))).isEqualTo(secret);
        }
    }

    @Test
    void rejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> Base32.decode("MZXW1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base32.decode("MZ-W6")).isInstanceOf(IllegalArgumentException.class);
    }
}
