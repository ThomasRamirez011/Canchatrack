package co.edu.unipamplona.canchatrack.payments.infrastructure;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import co.edu.unipamplona.canchatrack.payments.application.ChargeLine;
import co.edu.unipamplona.canchatrack.payments.application.FeeInfo;
import co.edu.unipamplona.canchatrack.payments.application.PaymentLine;
import co.edu.unipamplona.canchatrack.payments.application.PaymentStore;
import co.edu.unipamplona.canchatrack.payments.application.ReferenceNotFoundException;

@Repository
public class JdbcPaymentStore implements PaymentStore {

    private static final String FOREIGN_KEY_VIOLATION = "23503";
    /** Ajuste o anulación incoherente detectada por el trigger de V10 (p. ej. dos anulaciones simultáneas). */
    private static final String ADJUSTMENT_INVALID = "CT004";

    private static final String FEE_COLUMNS = "id, season_id, concept, name, amount, currency, valid_from, valid_to, active";

    private static final RowMapper<FeeInfo> FEE = (rs, rowNum) -> new FeeInfo(
            rs.getObject("id", UUID.class), rs.getObject("season_id", UUID.class), rs.getString("concept"),
            rs.getString("name"), rs.getBigDecimal("amount"), rs.getString("currency"),
            rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class), rs.getBoolean("active"));

    private static final String CHARGE_SELECT = """
            SELECT cb.charge_id, cb.player_id, cb.fee_id, f.name AS fee_name, f.concept, cb.period, cb.due_date,
                   cb.amount, cb.adjusted, cb.net_amount, cb.applied, cb.outstanding, cb.status
            FROM charge_balances cb JOIN fees f ON f.id = cb.fee_id
            """;

    private static final RowMapper<ChargeLine> CHARGE = (rs, rowNum) -> new ChargeLine(
            rs.getObject("charge_id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("fee_id", UUID.class),
            rs.getString("fee_name"), rs.getString("concept"), rs.getObject("period", LocalDate.class),
            rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("amount"), rs.getBigDecimal("adjusted"),
            rs.getBigDecimal("net_amount"), rs.getBigDecimal("applied"), rs.getBigDecimal("outstanding"), rs.getString("status"));

    private static final String PAYMENT_SELECT = """
            SELECT pm.id, pm.player_id, pm.charge_id, pm.amount, pm.amount + coalesce(a.adjusted, 0) AS net_amount,
                   pm.method, pm.reference, pm.paid_at, pm.receipt_file_id,
                   EXISTS (SELECT 1 FROM adjustments x WHERE x.payment_id = pm.id AND x.kind = 'VOID') AS voided
            FROM payments pm
            LEFT JOIN (SELECT payment_id, sum(amount) AS adjusted FROM adjustments GROUP BY payment_id) a ON a.payment_id = pm.id
            """;

    private static final RowMapper<PaymentLine> PAYMENT = (rs, rowNum) -> new PaymentLine(
            rs.getObject("id", UUID.class), rs.getObject("player_id", UUID.class), rs.getObject("charge_id", UUID.class),
            rs.getBigDecimal("amount"), rs.getBigDecimal("net_amount"), rs.getString("method"), rs.getString("reference"),
            rs.getObject("paid_at", OffsetDateTime.class).toInstant(), rs.getObject("receipt_file_id", UUID.class),
            rs.getBoolean("voided"));

    private final JdbcTemplate jdbc;

    public JdbcPaymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant now() {
        return jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();
    }

    @Override
    public FeeInfo insertFee(UUID seasonId, String concept, String name, java.math.BigDecimal amount, LocalDate validFrom,
                             LocalDate validTo, UUID createdBy) {
        try {
            return jdbc.queryForObject("INSERT INTO fees (season_id, concept, name, amount, valid_from, valid_to, created_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING " + FEE_COLUMNS, FEE,
                    seasonId, concept, name, amount, validFrom, validTo, createdBy);
        } catch (DataIntegrityViolationException e) {
            throw translateForeignKey(e);
        }
    }

    @Override
    public List<FeeInfo> findFees(UUID seasonId) {
        return jdbc.query("SELECT " + FEE_COLUMNS + " FROM fees WHERE (?::uuid IS NULL OR season_id = ?::uuid) "
                + "ORDER BY valid_from DESC, name", FEE, seasonId, seasonId);
    }

    @Override
    public Optional<FeeInfo> findFee(UUID feeId) {
        return jdbc.query("SELECT " + FEE_COLUMNS + " FROM fees WHERE id = ?", FEE, feeId).stream().findFirst();
    }

    @Override
    public Optional<UUID> insertCharge(UUID playerId, UUID feeId, LocalDate period, java.math.BigDecimal amount,
                                       LocalDate dueDate, UUID createdBy) {
        try {
            return jdbc.queryForList("""
                    INSERT INTO charges (player_id, fee_id, period, amount, due_date, created_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING id
                    """, UUID.class, playerId, feeId, period, amount, dueDate, createdBy).stream().findFirst();
        } catch (DataIntegrityViolationException e) {
            throw translateForeignKey(e);
        }
    }

    @Override
    public Optional<UUID> findChargeId(UUID playerId, UUID feeId, LocalDate period) {
        return jdbc.queryForList("SELECT id FROM charges WHERE player_id = ? AND fee_id = ? AND period IS NOT DISTINCT FROM ?::date",
                UUID.class, playerId, feeId, period).stream().findFirst();
    }

    @Override
    public void lockCharge(UUID chargeId) {
        // El saldo se lee en una sentencia posterior: con el bloqueo ya tomado ve los pagos confirmados por otros.
        jdbc.query("SELECT 1 FROM charges WHERE id = ? FOR UPDATE", rs -> { }, chargeId);
    }

    @Override
    public Optional<ChargeLine> findChargeLine(UUID chargeId) {
        return jdbc.query(CHARGE_SELECT + " WHERE cb.charge_id = ?", CHARGE, chargeId).stream().findFirst();
    }

    @Override
    public UUID insertPayment(UUID playerId, UUID chargeId, java.math.BigDecimal amount, String method, String reference,
                              UUID receiptFileId, Instant paidAt, UUID recordedBy) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO payments (player_id, fee_id, charge_id, amount, method, reference, receipt_file_id, paid_at, recorded_by)
                    SELECT ?, c.fee_id, c.id, ?, ?, ?, ?, ?, ? FROM charges c WHERE c.id = ?
                    RETURNING id
                    """, UUID.class, playerId, amount, method, reference, receiptFileId,
                    OffsetDateTime.ofInstant(paidAt, java.time.ZoneOffset.UTC), recordedBy, chargeId);
        } catch (DataIntegrityViolationException e) {
            throw translateForeignKey(e);
        }
    }

    @Override
    public Optional<PaymentLine> findPayment(UUID paymentId) {
        return jdbc.query(PAYMENT_SELECT + " WHERE pm.id = ?", PAYMENT, paymentId).stream().findFirst();
    }

    @Override
    public boolean voidPayment(UUID paymentId, String reason, UUID createdBy) {
        try {
            return jdbc.update("""
                    INSERT INTO adjustments (payment_id, kind, amount, reason, created_by)
                    SELECT p.id, 'VOID', -(p.amount + coalesce((SELECT sum(amount) FROM adjustments WHERE payment_id = p.id), 0)), ?, ?
                    FROM payments p
                    WHERE p.id = ? AND NOT EXISTS (SELECT 1 FROM adjustments WHERE payment_id = p.id AND kind = 'VOID')
                    """, reason, createdBy, paymentId) == 1;
        } catch (DataAccessException e) {
            if (sqlState(e).filter(ADJUSTMENT_INVALID::equals).isPresent()) {
                return false; // otra anulación se coló entre la comprobación y la escritura
            }
            throw e;
        }
    }

    @Override
    public Optional<java.math.BigDecimal[]> totalsOf(UUID playerId) {
        return jdbc.query("SELECT charged, paid, balance FROM player_balances WHERE player_id = ?",
                (rs, rowNum) -> new java.math.BigDecimal[] {rs.getBigDecimal("charged"), rs.getBigDecimal("paid"),
                        rs.getBigDecimal("balance")}, playerId).stream().findFirst();
    }

    @Override
    public List<ChargeLine> chargesOf(UUID playerId) {
        return jdbc.query(CHARGE_SELECT + " WHERE cb.player_id = ? ORDER BY cb.due_date, cb.period NULLS FIRST", CHARGE, playerId);
    }

    @Override
    public List<PaymentLine> paymentsOf(UUID playerId) {
        return jdbc.query(PAYMENT_SELECT + " WHERE pm.player_id = ? ORDER BY pm.paid_at DESC, pm.recorded_at DESC", PAYMENT, playerId);
    }

    @Override
    public boolean enrollmentSettled(UUID playerId) {
        Boolean settled = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM charge_balances cb JOIN fees f ON f.id = cb.fee_id
                               WHERE cb.player_id = ? AND f.concept = 'ENROLLMENT' AND cb.status <> 'VOIDED')
                   AND NOT EXISTS (SELECT 1 FROM charge_balances cb JOIN fees f ON f.id = cb.fee_id
                               WHERE cb.player_id = ? AND f.concept = 'ENROLLMENT' AND cb.status NOT IN ('SETTLED', 'VOIDED'))
                """, Boolean.class, playerId, playerId);
        return Boolean.TRUE.equals(settled);
    }

    private static ReferenceNotFoundException translateForeignKey(DataIntegrityViolationException e) {
        if (sqlState(e).filter(FOREIGN_KEY_VIOLATION::equals).isPresent()) {
            return new ReferenceNotFoundException();
        }
        throw e;
    }

    private static Optional<String> sqlState(Throwable e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof SQLException sql) {
                return Optional.ofNullable(sql.getSQLState());
            }
            cause = cause.getCause();
        }
        return Optional.empty();
    }
}
