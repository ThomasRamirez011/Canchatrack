package co.edu.unipamplona.canchatrack.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;
import co.edu.unipamplona.canchatrack.payments.application.ArrearsNoticeService;

/**
 * Avisos de mora (RF-041) contra PostgreSQL real: la consulta de cargos vencidos con saldo, el destinatario (acudiente
 * con vínculo vigente), la deduplicación por tramo y el contenido del correo ya renderizado. Las pruebas comparten la
 * base, así que cada una busca sus avisos por el cargo que creó y no por el total que devuelve el proceso.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ArrearsNoticeIT {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private ArrearsNoticeService notices;

    private record Family(UUID player, String guardianEmail, UUID guardianUser) {
    }

    private UUID user(String email, String role) {
        UUID id = jdbc.queryForObject("INSERT INTO users (email, full_name, password_hash) VALUES (?, ?, ?) RETURNING id",
                UUID.class, email, "Cuenta de prueba", encoder.encode("Clave-Segura-123"));
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE code = ?", id, role);
        return id;
    }

    private Family family(String playerStatus, boolean withGuardian) {
        UUID player = jdbc.queryForObject("""
                INSERT INTO players (first_name, last_name, document_type, document_number, birth_date, status)
                VALUES ('Ana', 'Ríos', 'TI', ?, DATE '2014-06-01', ?) RETURNING id
                """, UUID.class, "TI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), playerStatus);
        String email = "mora-" + UUID.randomUUID() + "@familia.test";
        UUID guardianUser = null;
        if (withGuardian) {
            guardianUser = user(email, "GUARDIAN");
            UUID guardian = jdbc.queryForObject(
                    "INSERT INTO guardians (user_id, document_type, document_number) VALUES (?, 'CC', ?) RETURNING id",
                    UUID.class, guardianUser, "cc-" + UUID.randomUUID().toString().substring(0, 20));
            jdbc.update("INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (?, ?, true)", player, guardian);
        }
        return new Family(player, email, guardianUser);
    }

    private UUID fee() {
        UUID season = jdbc.queryForObject("INSERT INTO seasons (name, start_at, end_at) VALUES (?, now(), now() + interval '1 year') RETURNING id",
                UUID.class, "Temporada " + UUID.randomUUID());
        return jdbc.queryForObject("""
                INSERT INTO fees (season_id, concept, name, amount, valid_from) VALUES (?, 'MONTHLY', 'Mensualidad de mora', 80000, current_date)
                RETURNING id
                """, UUID.class, season);
    }

    /** {@code monthsBack} distingue el periodo: un jugador no puede tener dos cargos de la misma tarifa y periodo. */
    private UUID charge(UUID player, UUID fee, int daysOverdue, int monthsBack) {
        return jdbc.queryForObject("""
                INSERT INTO charges (player_id, fee_id, period, amount, due_date)
                VALUES (?, ?, date_trunc('month', ?::date)::date, 80000, ?) RETURNING id
                """, UUID.class, player, fee, LocalDate.now(ZONE).minusMonths(monthsBack), LocalDate.now(ZONE).minusDays(daysOverdue));
    }

    private UUID charge(UUID player, UUID fee, int daysOverdue) {
        return charge(player, fee, daysOverdue, 1);
    }

    private List<String> noticesOf(UUID charge) {
        return jdbc.queryForList("SELECT dedup_key FROM notifications WHERE related_entity_id = ? AND notification_type = 'ARREARS_NOTICE' ORDER BY dedup_key",
                String.class, charge);
    }

    @Test
    void theGuardianOfAPlayerWithAnOverdueBalanceIsToldOnceAndTheSameDayRunsDoNotRepeatIt() {
        Family family = family("ACTIVE", true);
        UUID charge = charge(family.player(), fee(), 10);

        notices.notifyOverdue();
        notices.notifyOverdue();

        assertThat(noticesOf(charge)).hasSize(1);
        var row = jdbc.queryForMap("""
                SELECT recipient_email, status, subject, payload->>'playerName' AS player, payload->>'outstanding' AS outstanding,
                       payload->>'daysOverdue' AS days, payload->>'concept' AS concept
                FROM notifications WHERE related_entity_id = ? AND notification_type = 'ARREARS_NOTICE'
                """, charge);
        assertThat(row.get("recipient_email")).isEqualTo(family.guardianEmail());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("player")).isEqualTo("Ana Ríos");
        assertThat(row.get("concept")).isEqualTo("Mensualidad de mora");
        assertThat(row.get("days")).isEqualTo("10");
        assertThat(new java.math.BigDecimal(row.get("outstanding").toString())).isEqualByComparingTo("80000");
    }

    @Test
    void eachChargeHasItsOwnStageAndAChargeNotYetDueIsLeftAlone() {
        Family family = family("ACTIVE", true);
        UUID fee = fee();
        UUID first = charge(family.player(), fee, 1, 1);
        UUID later = charge(family.player(), fee, 20, 2);
        UUID notDue = charge(family.player(), fee, -5, 3);

        notices.notifyOverdue();

        assertThat(noticesOf(first)).hasSize(1);
        assertThat(noticesOf(later)).hasSize(1);
        assertThat(noticesOf(notDue)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT dedup_key) FROM notifications WHERE related_entity_id IN (?, ?)",
                Integer.class, first, later)).isEqualTo(2);
    }

    @Test
    void aSettledChargeAProcessThatEndedAndAPlayerWithoutGuardiansProduceNothing() {
        UUID fee = fee();
        Family paid = family("ACTIVE", true);
        UUID settled = charge(paid.player(), fee, 12);
        jdbc.update("""
                INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by)
                SELECT player_id, fee_id, id, amount, 'CASH', now(), ? FROM charges WHERE id = ?
                """, paid.guardianUser(), settled);
        Family rejected = family("REJECTED", true);
        UUID ended = charge(rejected.player(), fee, 12);
        Family orphan = family("ACTIVE", false);
        UUID noGuardian = charge(orphan.player(), fee, 12);

        notices.notifyOverdue();

        assertThat(noticesOf(settled)).isEmpty();
        assertThat(noticesOf(ended)).isEmpty();
        assertThat(noticesOf(noGuardian)).isEmpty();
    }

    @Test
    void aPartiallyPaidChargeStillGetsItsNoticeWithTheRemainingBalance() {
        Family family = family("ACTIVE", true);
        UUID charge = charge(family.player(), fee(), 3);
        jdbc.update("""
                INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by)
                SELECT player_id, fee_id, id, 30000, 'CASH', now(), ? FROM charges WHERE id = ?
                """, family.guardianUser(), charge);

        notices.notifyOverdue();

        assertThat(noticesOf(charge)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT (payload->>'outstanding')::numeric FROM notifications WHERE related_entity_id = ?",
                java.math.BigDecimal.class, charge)).isEqualByComparingTo("50000");
    }
}
