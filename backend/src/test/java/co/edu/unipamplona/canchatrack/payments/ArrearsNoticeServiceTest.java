package co.edu.unipamplona.canchatrack.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.api.GuardianContacts;
import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;
import co.edu.unipamplona.canchatrack.payments.application.ArrearsNoticeService;
import co.edu.unipamplona.canchatrack.payments.application.ArrearsSettings;
import co.edu.unipamplona.canchatrack.payments.application.OverdueCharge;
import co.edu.unipamplona.canchatrack.payments.application.PaymentStore;

class ArrearsNoticeServiceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Bogota");
    /** 2026-12-01 en Bogotá (UTC-5): 14:00 UTC es el mediodía del día 1. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T17:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate TODAY = LocalDate.of(2026, 12, 1);

    record Sent(String type, Set<UUID> recipients, String subject, Map<String, String> payload, String entity,
                UUID entityId, String scope) {
    }

    private final List<OverdueCharge> overdue = new ArrayList<>();
    private final List<LocalDate[]> windows = new ArrayList<>();
    private final Map<UUID, Set<UUID>> guardiansOf = new HashMap<>();
    private final List<Sent> sent = new ArrayList<>();

    private final PaymentStore store = (PaymentStore) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{PaymentStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("findOverdueCharges")) {
                    windows.add(new LocalDate[]{(LocalDate) args[0], (LocalDate) args[1]});
                    return overdue.stream()
                            .filter(c -> !c.dueDate().isBefore((LocalDate) args[0]) && !c.dueDate().isAfter((LocalDate) args[1]))
                            .toList();
                }
                throw new UnsupportedOperationException(method.getName());
            });

    private final GuardianContacts guardians = playerIds -> {
        Set<UUID> users = new LinkedHashSet<>();
        for (UUID id : playerIds) {
            users.addAll(guardiansOf.getOrDefault(id, Set.of()));
        }
        return users;
    };

    private final NotificationQueue queue = new NotificationQueue() {
        @Override
        public void enqueue(String type, Collection<UUID> recipientUserIds, String subject, Map<String, String> payload,
                            String relatedEntityType, UUID relatedEntityId, String dedupScope) {
            sent.add(new Sent(type, new LinkedHashSet<>(recipientUserIds), subject, Map.copyOf(payload), relatedEntityType,
                    relatedEntityId, dedupScope));
        }
    };

    private ArrearsNoticeService service(int grace, int interval, int max, String appUrl) {
        return new ArrearsNoticeService(store, guardians, queue, new ArrearsSettings(ZONE, grace, interval, max, appUrl), CLOCK);
    }

    private OverdueCharge charge(UUID player, int daysOverdue, String amount, LocalDate period) {
        return new OverdueCharge(UUID.randomUUID(), player, "Ana Ríos", "Mensualidad", period,
                TODAY.minusDays(daysOverdue), new BigDecimal(amount), "COP");
    }

    @Test
    void theGuardiansOfAPlayerWithAnOverdueChargeAreToldTheBalanceAndTheDays() {
        UUID player = UUID.randomUUID();
        UUID mother = UUID.randomUUID();
        guardiansOf.put(player, Set.of(mother));
        OverdueCharge charge = charge(player, 10, "80000.00", LocalDate.of(2026, 11, 1));
        overdue.add(charge);

        assertThat(service(0, 7, 8, "https://club.example.co").notifyOverdue()).isEqualTo(1);

        assertThat(sent).hasSize(1);
        Sent notice = sent.get(0);
        assertThat(notice.type()).isEqualTo("ARREARS_NOTICE");
        assertThat(notice.recipients()).containsExactly(mother);
        assertThat(notice.entity()).isEqualTo("charge");
        assertThat(notice.entityId()).isEqualTo(charge.chargeId());
        assertThat(notice.scope()).isEqualTo("stage:1");
        assertThat(notice.payload()).containsEntry("playerName", "Ana Ríos").containsEntry("concept", "Mensualidad")
                .containsEntry("period", "2026-11-01").containsEntry("dueDate", TODAY.minusDays(10).toString())
                .containsEntry("outstanding", "80000.00").containsEntry("currency", "COP")
                .containsEntry("daysOverdue", "10").containsEntry("appUrl", "https://club.example.co");
    }

    @Test
    void theFirstDayOfArrearsIsStageZeroAndAChargeWithoutPeriodOrLinkOmitsThem() {
        UUID player = UUID.randomUUID();
        guardiansOf.put(player, Set.of(UUID.randomUUID()));
        overdue.add(charge(player, 1, "150000.00", null));

        service(0, 7, 8, "").notifyOverdue();

        assertThat(sent.get(0).scope()).isEqualTo("stage:0");
        assertThat(sent.get(0).payload()).doesNotContainKeys("period", "appUrl");
    }

    @Test
    void theWindowAskedOfTheStoreMatchesTheGraceAndTheLimitOfNotices() {
        service(2, 7, 8, "").notifyOverdue();
        assertThat(windows).hasSize(1);
        assertThat(windows.get(0)[0]).isEqualTo(TODAY.minusDays(2 + 7 * 8));
        assertThat(windows.get(0)[1]).isEqualTo(TODAY.minusDays(3));
    }

    @Test
    void chargesWithinTheGraceDaysOrBeyondTheLimitProduceNothing() {
        UUID player = UUID.randomUUID();
        guardiansOf.put(player, Set.of(UUID.randomUUID()));
        overdue.add(charge(player, 2, "10000.00", null));   // dentro de la gracia
        overdue.add(charge(player, 200, "10000.00", null)); // ya agotó sus avisos

        assertThat(service(2, 7, 8, "").notifyOverdue()).isZero();
        assertThat(sent).isEmpty();
    }

    @Test
    void aPlayerWithoutGuardiansIsSkippedAndTheOthersStillGetTheirNotice() {
        UUID orphan = UUID.randomUUID();
        UUID player = UUID.randomUUID();
        guardiansOf.put(player, Set.of(UUID.randomUUID()));
        overdue.add(charge(orphan, 5, "10000.00", null));
        overdue.add(charge(player, 5, "20000.00", null));

        assertThat(service(0, 7, 8, "").notifyOverdue()).isEqualTo(1);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).payload()).containsEntry("outstanding", "20000.00");
    }

    @Test
    void twoChargesOfTheSamePlayerAreTwoNotices() {
        UUID player = UUID.randomUUID();
        guardiansOf.put(player, Set.of(UUID.randomUUID()));
        overdue.add(charge(player, 3, "10000.00", LocalDate.of(2026, 10, 1)));
        overdue.add(charge(player, 40, "10000.00", LocalDate.of(2026, 9, 1)));

        assertThat(service(0, 7, 8, "").notifyOverdue()).isEqualTo(2);
        assertThat(sent).extracting(Sent::scope).containsExactlyInAnyOrder("stage:0", "stage:5");
        assertThat(sent).extracting(Sent::entityId).doesNotHaveDuplicates();
    }

    @Test
    void withZeroNoticesTheStoreIsNotEvenAsked() {
        overdue.add(charge(UUID.randomUUID(), 5, "10000.00", null));
        assertThat(service(0, 7, 0, "").notifyOverdue()).isZero();
        assertThat(windows).isEmpty();
        assertThat(sent).isEmpty();
    }

    @Test
    void runningTwiceTheSameDayAsksForTheSameScopeSoTheQueueCanDeduplicate() {
        UUID player = UUID.randomUUID();
        guardiansOf.put(player, Set.of(UUID.randomUUID()));
        overdue.add(charge(player, 9, "10000.00", null));
        ArrearsNoticeService service = service(0, 7, 8, "");

        service.notifyOverdue();
        service.notifyOverdue();

        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).scope()).isEqualTo(sent.get(0).scope());
        assertThat(sent.get(1).entityId()).isEqualTo(sent.get(0).entityId());
    }
}
