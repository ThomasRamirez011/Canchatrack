package co.edu.unipamplona.canchatrack.audit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class AuditRecordTest {

    @Test
    void buildsWithSuccessAsDefaultOutcome() {
        UUID entityId = UUID.randomUUID();

        AuditRecord record = AuditRecord.builder("PAYMENT_VOIDED", "payment")
                .actor(UUID.randomUUID(), "COORDINATOR")
                .entity(entityId)
                .reason("Pago registrado por error")
                .build();

        assertThat(record.outcome()).isEqualTo(AuditRecord.Outcome.SUCCESS);
        assertThat(record.entityId()).isEqualTo(entityId);
    }

    @Test
    void rejectsBlankAction() {
        assertThatThrownBy(() -> AuditRecord.builder(" ", "payment").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLongEntityType() {
        assertThatThrownBy(() -> AuditRecord.builder("X", "e".repeat(61)).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesUserAgent() {
        AuditRecord record = AuditRecord.builder("LOGIN_FAILED", "user")
                .origin(null, "u".repeat(900))
                .build();

        assertThat(record.userAgent()).hasSize(500);
    }
}
