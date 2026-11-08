package co.edu.unipamplona.canchatrack.identity.application;

import java.util.UUID;

/** Puerto de salida: vínculo acudiente-jugador vigente (la vigencia se evalúa con la hora de la base de datos). */
public interface GuardianLinks {

    boolean isActiveGuardianOf(UUID userId, UUID playerId);
}
