package co.edu.unipamplona.canchatrack.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.reports.domain.ExportLabels;

class ExportLabelsTest {

    @Test
    void translatesTheCodesThatTheDatabaseStores() {
        assertThat(ExportLabels.playerStatus("ACTIVE")).isEqualTo("Activo");
        assertThat(ExportLabels.playerStatus("PENDING_REGISTRATION")).isEqualTo("Pendiente de matrícula");
        assertThat(ExportLabels.eventType("TOURNAMENT_MATCH")).isEqualTo("Partido de torneo");
        assertThat(ExportLabels.eventStatus("COMPLETED")).isEqualTo("Finalizado");
        assertThat(ExportLabels.attendance("EXCUSED")).isEqualTo("Excusado");
        assertThat(ExportLabels.callup("CONFIRMED")).isEqualTo("Convocado");
        assertThat(ExportLabels.roundStatus("CLOSED")).isEqualTo("Cerrada");
        assertThat(ExportLabels.roundResult("FOLLOW_UP")).isEqualTo("Seguimiento");
        assertThat(ExportLabels.concept("ENROLLMENT")).isEqualTo("Matrícula");
        assertThat(ExportLabels.chargeStatus("SETTLED")).isEqualTo("Pagado");
    }

    @Test
    void anUnknownCodeIsKeptAsIsAndNullStaysNull() {
        assertThat(ExportLabels.playerStatus("FUTURE_STATUS")).isEqualTo("FUTURE_STATUS");
        assertThat(ExportLabels.callup(null)).isNull();
    }
}
