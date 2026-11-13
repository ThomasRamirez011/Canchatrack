package co.edu.unipamplona.canchatrack.scheduling.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
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

    /**
     * Eventos no cancelados que se cruzan con el intervalo por escenario, entrenador o categoría.
     *
     * @param excludeEventId evento que no cuenta como cruce (el que se está reprogramando); nulo al programar uno nuevo
     */
    List<ConflictView> findConflicts(UUID venueId, UUID coachId, UUID categoryId, Instant startAt, Instant endAt,
                                     UUID excludeEventId);

    /** @throws ReferenceNotFoundException si la categoría o el escenario no existen */
    UUID insertEvent(String eventType, UUID categoryId, UUID venueId, Instant startAt, Instant endAt, Instant meetingAt,
                     String meetingPoint, String instructions, UUID headCoachId, UUID createdBy);

    Optional<EventView> findEvent(UUID eventId);

    /** Bloquea el evento y devuelve su estado actual. */
    Optional<String> lockStatus(UUID eventId);

    void cancel(UUID eventId, String reason);

    /**
     * Cambia fecha, lugar, entrenador responsable y datos de concentración de un evento confirmado y sube su revisión.
     * Quien llama ya bloqueó el evento y validó los datos.
     */
    void reschedule(UUID eventId, UUID venueId, UUID headCoachId, Instant startAt, Instant endAt, Instant meetingAt,
                    String meetingPoint, String instructions);

    /**
     * Número de veces que cambió el evento (reprogramaciones y cancelación). Es el {@code SEQUENCE} de su invitación de
     * calendario: la programación inicial es 0 y cada cambio la sube.
     */
    int revisionOf(UUID eventId);

    /** CONFIRMED → COMPLETED; no toca eventos en otro estado. */
    void complete(UUID eventId);

    /** Nombre de la categoría, dirección del escenario, nombre del entrenador responsable y, en un partido registrado, el rival. */
    EventContext contextOf(EventView event);

    /**
     * Entrenamientos, evaluaciones y partidos confirmados que empiezan después de {@code from} y hasta {@code to} (incluido),
     * con el momento de su última modificación (programación o reprogramación).
     */
    List<ReminderCandidate> findUpcomingForReminders(Instant from, Instant to);

    /** Eventos que cumplen todos los filtros, ordenados por inicio. */
    List<EventView> findEvents(EventQuery query, int limit);

    /** @throws ReferenceNotFoundException si la categoría, el escenario o el entrenador no existen */
    UUID insertSeries(String eventType, UUID categoryId, UUID venueId, UUID headCoachId, Set<DayOfWeek> weekdays,
                      LocalDate startDate, LocalDate endDate, LocalTime startTime, LocalTime endTime, String timeZone,
                      String meetingPoint, String instructions, int occurrenceCount, UUID createdBy);

    /** Igual que {@link #insertEvent} pero ligado a su serie; {@code position} empieza en 1. */
    UUID insertSeriesEvent(UUID seriesId, int position, String eventType, UUID categoryId, UUID venueId, Instant startAt,
                           Instant endAt, String meetingPoint, String instructions, UUID headCoachId, UUID createdBy);

    Optional<SeriesInfo> findSeries(UUID seriesId);

    /** Todas las sesiones de la serie, también las canceladas, por orden cronológico. */
    List<EventView> findSeriesEvents(UUID seriesId);
}
