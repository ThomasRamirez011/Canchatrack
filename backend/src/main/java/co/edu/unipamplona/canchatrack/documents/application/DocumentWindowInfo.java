package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Instant;
import java.util.UUID;

/** Ventana habilitada para cargar un tipo de documento (RN-05). {@code playerId} nulo = ventana general para todos los jugadores. */
public record DocumentWindowInfo(UUID id, UUID playerId, UUID documentTypeId, Instant opensAt, Instant closesAt,
                                 String reason, UUID openedBy, Instant createdAt) {
}
