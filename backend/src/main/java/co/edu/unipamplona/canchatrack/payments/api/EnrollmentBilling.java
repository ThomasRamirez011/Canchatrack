package co.edu.unipamplona.canchatrack.payments.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * Fachada pública: cobros que dispara el ciclo de vida del jugador (matrícula al aprobar la inscripción, mensualidades
 * por mes). El módulo {@code players} decide <i>a quién</i> y <i>cuándo</i>; {@code payments} decide <i>cuánto</i> y
 * guarda el cargo. La generación es idempotente: repetirla no duplica cargos.
 */
public interface EnrollmentBilling {

    /**
     * Crea el cargo único de matrícula del jugador con el monto de la tarifa (o devuelve el que ya existía).
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException 422 si la tarifa no existe, no está activa o
     *         no es de concepto ENROLLMENT; 404 si el jugador no existe
     * @return el id del cargo
     */
    UUID chargeEnrollment(UUID playerId, UUID feeId, LocalDate dueDate, UUID createdBy);

    /** La matrícula está cobrada y saldada: existe al menos un cargo de matrícula vigente y ninguno queda pendiente. */
    boolean isEnrollmentSettled(UUID playerId);

    /**
     * Crea el cargo mensual de cada jugador indicado para el periodo (primer día del mes).
     *
     * @return cuántos cargos nuevos se crearon (los que ya existían no cuentan)
     */
    int chargeMonthly(Collection<UUID> playerIds, UUID feeId, LocalDate period, LocalDate dueDate, UUID createdBy);
}
