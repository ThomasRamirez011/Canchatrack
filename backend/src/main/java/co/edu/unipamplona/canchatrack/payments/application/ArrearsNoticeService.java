package co.edu.unipamplona.canchatrack.payments.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.payments.domain.ArrearsPolicy;

/**
 * Avisos de mora (RF-041): a los acudientes con vínculo vigente de cada jugador con un cargo vencido y con saldo, un aviso
 * el primer día de mora y uno cada {@code intervalDays} mientras siga pendiente ({@link ArrearsPolicy}). Cada aviso nombra
 * solo al jugador de ese acudiente, el cargo y el saldo; no lleva datos de salud (RN-17).
 *
 * <p>Idempotente: el ámbito de deduplicación es el tramo del aviso, así que correr el proceso varias veces el mismo día no
 * duplica correos, y un cargo saldado deja de avisar porque ya no sale de la consulta. Los avisos se encolan en la misma
 * transacción; el procesador de notificaciones se encarga del envío y los reintentos.
 */
@Service
public class ArrearsNoticeService {

    public static final String NOTIFICATION_TYPE = "ARREARS_NOTICE";
    static final String RELATED_ENTITY = "charge";

    private final PaymentStore store;
    private final GuardianContacts guardians;
    private final NotificationQueue queue;
    private final ArrearsSettings settings;
    private final Clock clock;

    public ArrearsNoticeService(PaymentStore store, GuardianContacts guardians, NotificationQueue queue,
                                ArrearsSettings settings, Clock clock) {
        this.store = store;
        this.guardians = guardians;
        this.queue = queue;
        this.settings = settings;
        this.clock = clock;
    }

    /** @return cuántos cargos tenían un aviso que corresponde hoy (los ya avisados no se duplican) */
    @Transactional
    public int notifyOverdue() {
        if (settings.maxNotices() == 0) {
            return 0;
        }
        LocalDate today = LocalDate.now(clock.withZone(settings.zone()));
        List<OverdueCharge> candidates = store.findOverdueCharges(
                ArrearsPolicy.oldestDue(today, settings.graceDays(), settings.intervalDays(), settings.maxNotices()),
                ArrearsPolicy.newestDue(today, settings.graceDays()));
        int due = 0;
        for (OverdueCharge charge : candidates) {
            OptionalInt stage = ArrearsPolicy.stage(charge.dueDate(), today, settings.graceDays(),
                    settings.intervalDays(), settings.maxNotices());
            if (stage.isEmpty()) {
                continue;
            }
            Set<UUID> recipients = guardians.guardianUserIdsOf(List.of(charge.playerId()));
            if (recipients.isEmpty()) {
                continue;
            }
            queue.enqueue(NOTIFICATION_TYPE, recipients, "Saldo pendiente vencido", payload(charge, today),
                    RELATED_ENTITY, charge.chargeId(), "stage:" + stage.getAsInt());
            due++;
        }
        return due;
    }

    private Map<String, String> payload(OverdueCharge charge, LocalDate today) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("playerName", charge.playerName());
        payload.put("concept", charge.concept());
        if (charge.period() != null) {
            payload.put("period", charge.period().toString());
        }
        payload.put("dueDate", charge.dueDate().toString());
        payload.put("outstanding", charge.outstanding().toPlainString());
        payload.put("currency", charge.currency());
        payload.put("daysOverdue", Long.toString(java.time.temporal.ChronoUnit.DAYS.between(charge.dueDate(), today)));
        if (!settings.appUrl().isEmpty()) {
            payload.put("appUrl", settings.appUrl());
        }
        return payload;
    }
}
