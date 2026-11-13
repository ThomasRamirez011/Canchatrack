package co.edu.unipamplona.canchatrack.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** Vectores de prueba oficiales de RFC 6238 (apéndice B, SHA-1) recortados a 6 dígitos. */
class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheOfficialRfc6238Vectors() {
        assertThat(Totp.code(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(Totp.code(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(Totp.code(RFC_SECRET, 1111111111L / 30)).isEqualTo("050471");
        assertThat(Totp.code(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(Totp.code(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
        assertThat(Totp.code(RFC_SECRET, 20000000000L / 30)).isEqualTo("353130");
    }

    @Test
    void theStepIsThirtySecondsLong() {
        assertThat(Totp.stepAt(Instant.ofEpochSecond(0))).isEqualTo(0L);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(29))).isEqualTo(0L);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(30))).isEqualTo(1L);
        assertThat(Totp.stepAt(Instant.ofEpochSecond(1111111109L))).isEqualTo(37037036L);
    }

    @Test
    void verifyAcceptsTheCurrentStepAndReturnsIt() {
        Instant now = Instant.ofEpochSecond(1111111109L);

        assertThat(Totp.verify(RFC_SECRET, "081804", now, 1, null).getAsLong()).isEqualTo(37037036L);
    }

    @Test
    void verifyToleratesOneStepEitherSideButNotTwo() {
        Instant now = Instant.ofEpochSecond(1111111109L);
        long step = Totp.stepAt(now);

        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 1, null).isPresent()).isTrue();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, 1, null).isPresent()).isTrue();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, 1, null).isPresent()).isFalse();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, 1, null).isPresent()).isFalse();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, 0, null).isPresent())
                .as("sin tolerancia solo vale el intervalo actual").isFalse();
    }

    @Test
    void anAlreadyUsedStepCannotBeUsedAgain() {
        Instant now = Instant.ofEpochSecond(1111111109L);
        long step = Totp.stepAt(now);

        assertThat(Totp.verify(RFC_SECRET, "081804", now, 1, step).isPresent()).isFalse();
        assertThat(Totp.verify(RFC_SECRET, "081804", now, 1, step + 1).isPresent()).isFalse();
        assertThat(Totp.verify(RFC_SECRET, "081804", now, 1, step - 1).isPresent()).isTrue();
    }

    @Test
    void malformedCodesNeverMatch() {
        Instant now = Instant.ofEpochSecond(1111111109L);

        for (String bad : new String[] {null, "", "8180", "0818044", "08180a", " 81804", "٠٨١٨٠٤", "08 804"}) {
            assertThat(Totp.verify(RFC_SECRET, bad, now, 1, null).isPresent()).as("código: " + bad).isFalse();
        }
    }

    @Test
    void aNewSecretHasTwentyRandomBytes() {
        byte[] first = Totp.newSecret();
        byte[] second = Totp.newSecret();

        assertThat(first.length).isEqualTo(20);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theProvisioningUriCarriesWhatAnAuthenticatorAppNeeds() {
        String uri = Totp.otpauthUri("Cancha Track", "a+b@club.test", RFC_SECRET);

        assertThat(uri).startsWith("otpauth://totp/Cancha%20Track:a%2Bb%40club.test?")
                .contains("secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ").contains("issuer=Cancha%20Track")
                .contains("algorithm=SHA1").contains("digits=6").contains("period=30");
    }
}
