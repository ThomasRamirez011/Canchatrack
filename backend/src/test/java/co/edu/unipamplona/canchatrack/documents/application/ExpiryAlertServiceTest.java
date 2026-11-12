package co.edu.unipamplona.canchatrack.documents.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;

/** A quién y con qué ámbito de deduplicación se encolan las alertas de vencimiento (RF-010). */
class ExpiryAlertServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-12T17:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 12);

    private final UUID player = UUID.randomUUID();
    private final UUID guardian = UUID.randomUUID();
    private final UUID coach = UUID.randomUUID();
    private final List<Queued> queued = new ArrayList<>();
    private final List<ExpiringDocument> candidates = new ArrayList<>();
    private Set<UUID> guardians = Set.of(guardian);

    private record Queued(String type, Set<UUID> recipients, Map<String, String> payload, UUID entityId, String scope) {
    }

    private ExpiryAlertService service() {
        DocumentStore store = (DocumentStore) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DocumentStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findExpiring")) {
                        assertThat(args[0]).isEqualTo(TODAY);
                        assertThat(args[1]).isEqualTo(30);
                        return candidates;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        GuardianContacts contacts = playerIds -> guardians;
        NotificationQueue queue = new NotificationQueue() {
            @Override
            public void enqueue(String type, Collection<UUID> recipientUserIds, String subject, Map<String, String> payload,
                                String relatedEntityType, UUID relatedEntityId, String dedupScope) {
                queued.add(new Queued(type, Set.copyOf(recipientUserIds), payload, relatedEntityId, dedupScope));
            }
        };
        return new ExpiryAlertService(store, contacts, queue, new DocumentSettings(ZoneId.of("America/Bogota"), 1024),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ExpiringDocument expiring(int daysFromToday, UUID headCoach) {
        return new ExpiringDocument(UUID.randomUUID(), player, "JG-000001", "Ana Gómez", "Certificado médico",
                TODAY.plusDays(daysFromToday), headCoach);
    }

    @Test
    void theGuardianAndTheHeadCoachAreWarnedWithTheStageAndNoHealthData() {
        ExpiringDocument document = expiring(25, coach);
        candidates.add(document);

        int due = service().notifyExpiring();

        assertThat(due).isEqualTo(1);
        Queued notice = queued.get(0);
        assertThat(notice.type()).isEqualTo("DOCUMENT_EXPIRY");
        assertThat(notice.recipients()).isEqualTo(Set.of(guardian, coach));
        assertThat(notice.entityId()).isEqualTo(document.versionId());
        assertThat(notice.scope()).isEqualTo("30:" + TODAY.plusDays(25));
        assertThat(notice.payload()).isEqualTo(Map.of("playerName", "Ana Gómez", "documentName", "Certificado médico",
                "expiresOn", TODAY.plusDays(25).toString(), "daysLeft", "25"));
    }

    @Test
    void theClosingStageUsesItsOwnDeduplicationScope() {
        candidates.add(expiring(7, null));
        candidates.add(expiring(0, null));

        assertThat(service().notifyExpiring()).isEqualTo(2);

        assertThat(queued.get(0).scope()).isEqualTo("7:" + TODAY.plusDays(7));
        assertThat(queued.get(1).scope()).isEqualTo("7:" + TODAY);
        assertThat(queued.get(0).recipients()).isEqualTo(Set.of(guardian));
    }

    @Test
    void nothingIsQueuedForExpiredDocumentsOrWhenNobodyCanBeWarned() {
        candidates.add(expiring(-1, coach));
        assertThat(service().notifyExpiring()).isEqualTo(0);

        candidates.clear();
        candidates.add(expiring(10, null));
        guardians = Set.of();
        assertThat(service().notifyExpiring()).isEqualTo(0);
        assertThat(queued).isEmpty();
    }
}
