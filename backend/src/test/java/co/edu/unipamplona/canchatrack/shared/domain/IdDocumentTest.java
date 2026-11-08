package co.edu.unipamplona.canchatrack.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdDocumentTest {

    @Test
    void typesAreParsedIgnoringCaseAndSpaces() {
        assertThat(IdDocumentType.parse(" cc ")).contains(IdDocumentType.CC);
        assertThat(IdDocumentType.parse("ti")).contains(IdDocumentType.TI);
        assertThat(IdDocumentType.parse("XX")).isEmpty();
        assertThat(IdDocumentType.parse(null)).isEmpty();
    }

    @Test
    void onlyAdultDocumentsServeForGuardians() {
        assertThat(IdDocumentType.CC.isForAdults()).isTrue();
        assertThat(IdDocumentType.CE.isForAdults()).isTrue();
        assertThat(IdDocumentType.PA.isForAdults()).isTrue();
        assertThat(IdDocumentType.TI.isForAdults()).isFalse();
        assertThat(IdDocumentType.RC.isForAdults()).isFalse();
    }

    @Test
    void numbersAreNormalizedAndValidated() {
        assertThat(IdDocumentNumber.normalize("  ti-1001 ")).contains("TI-1001");
        assertThat(IdDocumentNumber.normalize("123")).isEmpty();            // muy corto
        assertThat(IdDocumentNumber.normalize("1".repeat(31))).isEmpty();   // muy largo
        assertThat(IdDocumentNumber.normalize("12 34")).isEmpty();          // espacio interno
        assertThat(IdDocumentNumber.normalize("12'; DROP")).isEmpty();
        assertThat(IdDocumentNumber.normalize(null)).isEmpty();
    }
}
