package co.edu.unipamplona.canchatrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifica contra PostgreSQL real que Flyway aplica todo el modelo del DDS secc. 7 y que se cumplen las reglas
 * críticas de datos (pagos inmutables, asistencia idempotente, unanimidad, auditoría...).
 * Las invariantes viven en {@code db/schema-invariants.sql}, el mismo script que usa {@code scripts/check-schema.sh}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional // cada prueba se revierte: el script de invariantes inserta datos de ejemplo
class DatabaseSchemaIT {

    /** Tablas de DDS secc. 7 (y las que el DAD/ERS exigen y el DDS no lista; ver README). */
    private static final List<String> EXPECTED_TABLES = List.of(
            "users", "roles", "user_roles", "guardians", "seasons", "calls", "call_exceptions", "categories",
            "players", "player_guardians", "enrollments", "committee_minutes", "category_assignments",
            "evaluator_assignments", "venues", "sports_events", "event_participants", "tournaments", "matches",
            "callup_entries", "document_types", "document_windows", "document_versions", "consents",
            "attendance_sessions", "sync_batches", "attendance_records", "attendance_corrections",
            "evaluation_rubrics", "evaluation_rounds", "evaluator_decisions", "evaluator_decision_history",
            "fees", "charges", "charge_adjustments", "payments", "adjustments", "season_reports", "support_documents",
            "notifications", "delivery_attempts", "state_history", "audit_events", "outbox_events",
            "idempotency_keys", "club_parameters", "stored_files", "refresh_tokens");

    @Autowired
    private JdbcTemplate jdbc;

    @Value("classpath:db/schema-invariants.sql")
    private Resource invariants;

    @Test
    void flywayAppliedEveryMigrationSuccessfully() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true AND version IS NOT NULL", Integer.class);

        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(13);
    }

    @Test
    void everyTableOfTheDataModelExists() {
        for (String table : EXPECTED_TABLES) {
            Boolean exists = jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, "public." + table);
            assertThat(exists).as("tabla %s", table).isTrue();
        }
    }

    @Test
    void criticalBusinessInvariantsAreEnforcedByTheDatabase() throws Exception {
        String script = invariants.getContentAsString(StandardCharsets.UTF_8);

        assertThatCode(() -> jdbc.execute(script)).doesNotThrowAnyException();
    }
}
