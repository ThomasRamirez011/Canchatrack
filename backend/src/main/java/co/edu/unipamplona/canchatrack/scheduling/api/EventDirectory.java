package co.edu.unipamplona.canchatrack.scheduling.api;

import java.util.Optional;
import java.util.UUID;

/** Fachada pública: datos mínimos de un evento para quien depende de él (asistencia, convocatorias). */
public interface EventDirectory {

    Optional<EventInfo> find(UUID eventId);
}
