package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.CallInfo;

/** Puerto de salida: convocatorias y habilitaciones excepcionales. Toda vigencia se evalúa con el reloj de la base. */
public interface CallCatalog {

    Optional<CallInfo> findById(UUID callId);

    /** Convocatorias publicadas y dentro de su ventana, la que cierra primero va antes. */
    List<CallInfo> findOpen();

    /** Habilitación excepcional vigente del acudiente para la convocatoria, si existe. */
    Optional<UUID> activeExceptionId(UUID callId, UUID guardianId);
}
