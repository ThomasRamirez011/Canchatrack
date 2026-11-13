package co.edu.unipamplona.canchatrack.payments.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: tarifas, cargos y pagos. Las escrituras exigen una transacción activa. */
public interface PaymentStore {

    /** Reloj de la base de datos. */
    Instant now();

    /** @throws ReferenceNotFoundException si la temporada no existe */
    FeeInfo insertFee(UUID seasonId, String concept, String name, BigDecimal amount, LocalDate validFrom, LocalDate validTo,
                      UUID createdBy);

    List<FeeInfo> findFees(UUID seasonId);

    Optional<FeeInfo> findFee(UUID feeId);

    /**
     * Crea el cargo; si ya existe uno del mismo jugador, tarifa y periodo no hace nada.
     *
     * @return el id del cargo nuevo, o vacío si ya existía
     * @throws ReferenceNotFoundException si el jugador no existe
     */
    Optional<UUID> insertCharge(UUID playerId, UUID feeId, LocalDate period, BigDecimal amount, LocalDate dueDate,
                                UUID createdBy);

    Optional<UUID> findChargeId(UUID playerId, UUID feeId, LocalDate period);

    /** Bloquea el cargo hasta el fin de la transacción: serializa pagos concurrentes sobre el mismo cargo. */
    void lockCharge(UUID chargeId);

    Optional<ChargeLine> findChargeLine(UUID chargeId);

    /** @throws ReferenceNotFoundException si el jugador no existe */
    UUID insertPayment(UUID playerId, UUID chargeId, BigDecimal amount, String method, String reference,
                       UUID receiptFileId, Instant paidAt, UUID recordedBy);

    Optional<PaymentLine> findPayment(UUID paymentId);

    /** Anula el pago con un asiento compensatorio; {@code false} si ya estaba anulado. */
    boolean voidPayment(UUID paymentId, String reason, UUID createdBy);

    /** Totales del jugador; vacío si el jugador no existe. */
    Optional<BigDecimal[]> totalsOf(UUID playerId);

    List<ChargeLine> chargesOf(UUID playerId);

    List<PaymentLine> paymentsOf(UUID playerId);

    boolean enrollmentSettled(UUID playerId);

    /**
     * Cargos con saldo pendiente cuyo vencimiento está entre las dos fechas (inclusive), de jugadores con un proceso en
     * curso, del más antiguo al más reciente. Es el insumo de los avisos de mora.
     */
    List<OverdueCharge> findOverdueCharges(LocalDate oldestDue, LocalDate newestDue);
}
