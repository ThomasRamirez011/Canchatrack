package co.edu.unipamplona.canchatrack.attendance.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: sesiones, lotes y marcas de asistencia. Las escrituras exigen una transacción activa. */
public interface AttendanceStore {

    /** Reloj de la base de datos. */
    Instant now();

    /**
     * Crea la sesión si no existe y la bloquea hasta el fin de la transacción. Las marcas la toman en modo compartido y el
     * cierre en exclusivo, de modo que un cierre no se cruza con marcas en curso.
     *
     * @return {@code true} si la sesión ya está cerrada
     */
    boolean lockSession(UUID eventId, boolean exclusive);

    boolean isClosed(UUID eventId);

    Optional<Instant> closedAt(UUID eventId);

    Optional<SyncBatch> findBatch(UUID userId, String key);

    /** @return el id del lote, o vacío si otro lote con la misma clave ya existe */
    Optional<UUID> insertBatch(UUID eventId, UUID userId, String key);

    void finishBatch(UUID batchId, int received, int accepted, int duplicates, int rejected);

    /** @return {@code true} si se creó; {@code false} si la marca (o el jugador en la sesión) ya existía */
    boolean insertRecord(UUID eventId, UUID playerId, String status, boolean excuseAccepted, UUID clientUuid,
                         Instant clientRecordedAt, UUID recordedBy, UUID batchId);

    List<RecordInfo> recordsOf(UUID eventId);

    Optional<RecordInfo> findRecord(UUID eventId, UUID playerId);

    /** Marca ausentes a los jugadores indicados que aún no tienen marca; devuelve cuántos. */
    int markAbsent(UUID eventId, Collection<UUID> playerIds, UUID recordedBy);

    /** @return el instante de cierre, o vacío si ya estaba cerrada */
    Optional<Instant> closeSession(UUID eventId, UUID closedBy);

    /** Corrección posterior al cierre: deja la marca nueva y su rastro en una sola transacción (RN-15). */
    void correct(UUID recordId, String previousStatus, String newStatus, boolean excuseAccepted, String reason, UUID correctedBy);

    Optional<PlayerAttendanceSummary> summaryOf(UUID playerId);
}
