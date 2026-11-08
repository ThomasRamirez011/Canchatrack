package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.CallStatus;

/**
 * Puerto de salida: administración de convocatorias y habilitaciones excepcionales. Toda vigencia se evalúa con el
 * reloj de la base de datos (RN-01). La escritura exige una transacción activa.
 */
public interface CallStore {

    /** Hora de la base de datos. */
    Instant now();

    CallDetail insert(UUID seasonId, String name, Instant startAt, Instant endAt, String instructions, UUID createdBy);

    Optional<CallDetail> findById(UUID callId);

    /** Bloquea la fila hasta el fin de la transacción para serializar transiciones y ediciones. */
    Optional<CallDetail> findByIdForUpdate(UUID callId);

    /** Más recientes primero; filtros opcionales; máximo 200 filas. */
    List<CallDetail> find(UUID seasonId, CallStatus status);

    void update(UUID callId, String name, Instant startAt, Instant endAt, String instructions);

    /** DRAFT → PUBLISHED solo si el cierre aún no ocurrió; {@code false} en otro caso. */
    boolean publish(UUID callId);

    /** DRAFT o PUBLISHED → CANCELLED; {@code false} si ya estaba cancelada. */
    boolean cancel(UUID callId);

    CallExceptionInfo insertException(UUID callId, UUID guardianId, String reason, UUID grantedBy, Instant expiresAt);

    /** Más recientes primero. */
    List<CallExceptionInfo> findExceptions(UUID callId);
}
