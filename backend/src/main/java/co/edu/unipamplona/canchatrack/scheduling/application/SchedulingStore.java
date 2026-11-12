package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: escenarios y eventos. Las escrituras exigen una transacción activa. */
public interface SchedulingStore {

    /** Reloj de la base de datos. */
    Instant now();

    /** @throws DuplicateVenueException si ya hay un escenario con ese nombre */
    VenueInfo insertVenue(String name, String address, String reference, UUID createdBy);

    List<VenueInfo> findVenues(boolean includeInactive);

    Optional<VenueInfo> findVenue(UUID venueId);

    /**
     * Serializa a quienes programan sobre el mismo escenario, entrenador o categoría hasta el fin de la transacción: sin
     * esto dos solicitudes simultáneas verían la agenda vacía y ambas se confirmarían.
     */
    void lockSlots(UUID venueId, UUID coachId, UUID categoryId);

    /** Eventos no cancelados que se cruzan con el intervalo por escenario, entrenador o categoría. */
    List<ConflictView> findConflicts(UUID venueId, UUID coachId, UUID categoryId, Instant startAt, Instant endAt);

    /** @throws ReferenceNotFoundException si la categoría o el escenario no existen */
    UUID insertEvent(String eventType, UUID categoryId, UUID venueId, Instant startAt, Instant endAt, Instant meetingAt,
                     String meetingPoint, String instructions, UUID headCoachId, UUID createdBy);

    Optional<EventView> findEvent(UUID eventId);

    /** Bloquea el evento y devuelve su estado actual. */
    Optional<String> lockStatus(UUID eventId);

    void cancel(UUID eventId, String reason);

    /** CONFIRMED → COMPLETED; no toca eventos en otro estado. */
    void complete(UUID eventId);

    /** Nombre de la categoría, dirección del escenario y nombre del entrenador responsable del evento. */
    EventContext contextOf(EventView event);

    /**
     * Entrenamientos y evaluaciones confirmados que empiezan después de {@code from} y hasta {@code to} (incluido), con el
     * momento en que se programaron. Los partidos avisan por su propia convocatoria.
     */
    List<ReminderCandidate> findUpcomingForReminders(Instant from, Instant to);

    /** @param coachId si no es nulo, solo los eventos de los que es entrenador responsable */
    List<EventView> findEvents(UUID categoryId, Instant from, Instant to, UUID coachId, int limit);
}
