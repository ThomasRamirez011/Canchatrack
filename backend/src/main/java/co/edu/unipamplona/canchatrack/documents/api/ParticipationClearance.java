package co.edu.unipamplona.canchatrack.documents.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Fachada pública: requisitos documentales y de autorización para participar en un partido (RN-06, RN-07, RF-033,
 * RF-034). {@code competitions} decide <i>a quién</i> convocar; {@code documents} sabe si el certificado y las
 * autorizaciones están vigentes. Nada de lo que devuelve contiene datos de salud: solo quién no cumple.
 */
public interface ParticipationClearance {

    /** Jugadores (de los indicados) sin certificado médico vigente en la fecha: sin certificado, vencido o sin fecha de vencimiento. */
    Set<UUID> withoutValidMedicalCertificate(Collection<UUID> playerIds, LocalDate onDate);

    /** Jugadores (de los indicados) sin autorización general de participación vigente (no revocada ni vencida) en el instante. */
    Set<UUID> withoutValidParticipationAuthorization(Collection<UUID> playerIds, Instant at);

    /**
     * Registra la autorización del acudiente para un partido concreto. Es idempotente: repetirla no duplica.
     * Debe ejecutarse dentro de una transacción.
     *
     * @return {@code true} si se registró ahora, {@code false} si ya existía una vigente
     */
    boolean authorizeMatch(UUID guardianId, UUID playerId, UUID matchId, String versionText);

    /** Jugadores con autorización vigente (no revocada) para ese partido. */
    Set<UUID> matchAuthorizedPlayers(UUID matchId);

    /** Partidos para los que el jugador tiene autorización vigente (no revocada). */
    Set<UUID> authorizedMatchesOf(UUID playerId);
}
