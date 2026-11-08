package co.edu.unipamplona.canchatrack.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;
import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.shared.web.CorrelationIds;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class AuditWriterIT {

    @Autowired
    private AuditWriter auditWriter;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional
    void writeJoinsTheBusinessTransactionAndCapturesTheCorrelationId() {
        UUID entityId = UUID.randomUUID();
        MDC.put(CorrelationIds.MDC_KEY, "it-correlation-id");
        try {
            auditWriter.write(AuditRecord.builder("PAYMENT_RECORDED", "payment")
                    .entity(entityId)
                    .after("{\"amount\": 100000}")
                    .reason("prueba de integración")
                    .build());
        } finally {
            MDC.remove(CorrelationIds.MDC_KEY);
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT action, outcome, correlation_id, after_state ->> 'amount' AS amount "
                        + "FROM audit_events WHERE entity_id = ?", entityId);
        assertThat(row)
                .containsEntry("action", "PAYMENT_RECORDED")
                .containsEntry("outcome", "SUCCESS")
                .containsEntry("correlation_id", "it-correlation-id")
                .containsEntry("amount", "100000");
    }

    @Test
    @Transactional
    void auditedEventsCannotBeModifiedOrDeleted() {
        UUID entityId = UUID.randomUUID();
        auditWriter.write(AuditRecord.builder("PLAYER_SUSPENDED", "player").entity(entityId).reason("x").build());

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_events SET action = 'OTRA' WHERE entity_id = ?", entityId))
                .hasMessageContaining("IMMUTABLE_RECORD");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void writeFailsWithoutAnActiveTransaction() {
        assertThatThrownBy(() -> auditWriter.write(AuditRecord.builder("X", "y").build()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void writeIndependentPersistsEvenWithoutABusinessTransaction() {
        UUID entityId = UUID.randomUUID();

        auditWriter.writeIndependent(AuditRecord.builder("ENROLLMENT_REJECTED", "enrollment")
                .entity(entityId)
                .outcome(AuditRecord.Outcome.DENIED)
                .reason("Fuera de convocatoria")
                .build());

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_events WHERE entity_id = ? AND outcome = 'DENIED'", Integer.class, entityId);
        assertThat(count).isEqualTo(1);
    }
}
