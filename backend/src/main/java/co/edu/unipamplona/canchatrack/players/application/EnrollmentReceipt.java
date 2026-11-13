package co.edu.unipamplona.canchatrack.players.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import co.edu.unipamplona.canchatrack.players.domain.PlayerStatus;

/**
 * Constancia de radicación. {@code attachedDocuments} cuenta los documentos adjuntados ahora; {@code missingDocuments} son
 * los nombres de los tipos obligatorios que el jugador aún no tiene vigentes (se cargan después y la activación los exige).
 */
public record EnrollmentReceipt(UUID enrollmentId, String filingCode, UUID playerId, String playerCode,
                                PlayerStatus playerStatus, UUID callId, Instant submittedAt, boolean viaException,
                                int attachedDocuments, List<String> missingDocuments) {

    public EnrollmentReceipt {
        missingDocuments = missingDocuments == null ? List.of() : List.copyOf(missingDocuments);
    }
}
