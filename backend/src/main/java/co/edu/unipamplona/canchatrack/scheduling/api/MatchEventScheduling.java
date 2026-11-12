package co.edu.unipamplona.canchatrack.scheduling.api;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Fachada pública: agenda de los eventos que son partidos. {@code competitions} decide qué es un partido (rival, torneo,
 * convocatoria); {@code scheduling} sigue siendo el único dueño de {@code sports_events}, de los cruces de agenda y de su
 * auditoría. Todas las operaciones se ejecutan dentro de la transacción del llamador.
 */
public interface MatchEventScheduling {

    /**
     * Crea el evento confirmado del partido.
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException 422 si los datos no son válidos; 409
     *         {@code SCHEDULE_CONFLICT} si el horario se cruza con otro evento
     * @return el id del evento
     */
    UUID schedule(MatchEventSlot slot, UUID actorId, InetAddress ip, String userAgent);

    /**
     * Cancela el evento del partido (queda auditado con el motivo).
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException 422 si falta el motivo; 409 si el evento no está
     *         confirmado
     */
    void cancel(UUID eventId, String reason, UUID actorId, InetAddress ip, String userAgent);

    /**
     * Reprograma el evento del partido (cruces como al programar, sin contar el propio evento; queda auditado con el motivo).
     *
     * @throws co.edu.unipamplona.canchatrack.shared.domain.DomainException 422 si los datos no son válidos; 409
     *         {@code SCHEDULE_CONFLICT} si el nuevo horario se cruza; 409 si el evento no está confirmado o ya empezó
     */
    void reschedule(UUID eventId, MatchEventChange change, String reason, UUID actorId, InetAddress ip, String userAgent);

    /**
     * Avisa al entrenador del partido recién creado (correo con invitación de calendario). Se llama después de registrar el
     * partido, porque el aviso nombra al rival. Las familias no reciben nada aquí: avisan con la convocatoria.
     */
    void announce(UUID eventId);

    /** Marca el evento como realizado (al registrar el resultado). No hace nada si ya lo estaba. */
    void complete(UUID eventId);
}
