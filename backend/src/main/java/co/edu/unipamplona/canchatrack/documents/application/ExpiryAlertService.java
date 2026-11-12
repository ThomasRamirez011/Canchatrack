package co.edu.unipamplona.canchatrack.documents.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.documents.domain.DocumentRules;
import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;

/**
 * Alertas de vencimiento (RF-010, RN-06): a 30 y a 7 días del vencimiento se avisa al acudiente y al entrenador titular
 * del jugador. Se encolan en el outbox de notificaciones, que se encarga del envío y los reintentos. El aviso no lleva
 * datos de salud: solo el nombre del documento, el jugador y la fecha (RN-17).
 *
 * <p>Idempotente: el ámbito de deduplicación es «tramo + fecha de vencimiento», así que correr el proceso varias veces el
 * mismo día no duplica avisos, y un documento renovado (otra fecha) vuelve a avisar en su momento.
 */
@Service
public class ExpiryAlertService {

    public static final String NOTIFICATION_TYPE = "DOCUMENT_EXPIRY";
    static final String RELATED_ENTITY = "document_version";

    private final DocumentStore store;
    private final GuardianContacts guardians;
    private final NotificationQueue queue;
    private final DocumentSettings settings;
    private final Clock clock;

    public ExpiryAlertService(DocumentStore store, GuardianContacts guardians, NotificationQueue queue,
                              DocumentSettings settings, Clock clock) {
        this.store = store;
        this.guardians = guardians;
        this.queue = queue;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return cuántos documentos tenían una alerta que corresponde hoy (los ya avisados no se duplican) */
    @Transactional
    public int notifyExpiring() {
        LocalDate today = LocalDate.now(clock.withZone(settings.zone()));
        List<ExpiringDocument> candidates = store.findExpiring(today, DocumentRules.FIRST_ALERT_DAYS);
        int due = 0;
        for (ExpiringDocument document : candidates) {
            OptionalInt stage = DocumentRules.alertStage(document.expiresOn(), today);
            if (stage.isEmpty()) {
                continue;
            }
            Set<UUID> recipients = new LinkedHashSet<>(guardians.guardianUserIdsOf(List.of(document.playerId())));
            if (document.headCoachId() != null) {
                recipients.add(document.headCoachId());
            }
            if (recipients.isEmpty()) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, document.expiresOn());
            queue.enqueue(NOTIFICATION_TYPE, recipients, "Documento por vencer",
                    Map.of("playerName", document.playerName(), "documentName", document.documentName(),
                            "expiresOn", document.expiresOn().toString(), "daysLeft", Long.toString(daysLeft)),
                    RELATED_ENTITY, document.versionId(), stage.getAsInt() + ":" + document.expiresOn());
            due++;
        }
        return due;
    }
}
