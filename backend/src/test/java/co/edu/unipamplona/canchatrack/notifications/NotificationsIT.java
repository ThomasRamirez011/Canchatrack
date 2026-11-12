package co.edu.unipamplona.canchatrack.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import co.edu.unipamplona.canchatrack.TestcontainersConfiguration;
import co.edu.unipamplona.canchatrack.notifications.application.DueNotification;
import co.edu.unipamplona.canchatrack.notifications.application.EmailDeliveryException;
import co.edu.unipamplona.canchatrack.notifications.application.EmailSender;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationOutbox;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationProcessor;
import co.edu.unipamplona.canchatrack.notifications.domain.OutboundEmail;

/**
 * Procesador de la cola de avisos contra PostgreSQL real: envío con evidencia, reintentos con espera creciente, fallo
 * definitivo, reclamo concurrente sin duplicados y arriendo de los avisos. El proveedor de correo es un guion
 * ({@link ScriptedEmailSender}) que decide el resultado según el prefijo del destinatario; el procesador periódico está
 * apagado en el perfil de pruebas y se invoca a mano.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NotificationsIT.SenderConfig.class})
@ActiveProfiles("test")
class NotificationsIT {

    private static final String PAYLOAD = """
            {"playerName": "Ana Pérez", "opponent": "Club Rival", "startAt": "2026-12-05T20:00:00Z"}""";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private NotificationProcessor processor;
    @Autowired
    private NotificationOutbox outbox;
    @Autowired
    private ScriptedEmailSender sender;

    /** Deja la cola sin avisos vencidos (otras pruebas de la suite pudieron dejar pendientes) para que cada prueba parta limpia. */
    @BeforeEach
    void drainLeftovers() {
        drain();
    }

    @Test
    void aPendingNotificationIsSentAndLeavesEvidence() {
        String email = "ok-" + UUID.randomUUID() + "@test.local";
        UUID id = insert("MATCH_CALLUP", email);

        drain();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("sent")).isEqualTo(true);
        assertThat(row.get("last_error")).isNull();
        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("succeeded")).isEqualTo(true);
        assertThat((String) attempts.get(0).get("provider_response")).startsWith("script-");

        List<OutboundEmail> delivered = sender.deliveredTo(email);
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).subject()).isEqualTo("Convocatoria a partido");
        assertThat(delivered.get(0).body()).contains("Ana Pérez", "Club Rival", "05/12/2026 15:00");
    }

    @Test
    void aSentNotificationIsNeverSentAgain() {
        String email = "ok-" + UUID.randomUUID() + "@test.local";
        insert("MATCH_CALLUP", email);

        drain();
        drain();

        assertThat(sender.deliveredTo(email)).hasSize(1);
    }

    @Test
    void aTransientFailureIsRetriedLaterAndThenSucceeds() {
        String email = "transient-once-" + UUID.randomUUID() + "@test.local";
        UUID id = insert("MATCH_CALLUP", email);

        drain();

        Map<String, Object> afterFirst = row(id);
        assertThat(afterFirst.get("status")).isEqualTo("RETRYING");
        assertThat(afterFirst.get("attempt_count")).isEqualTo(1);
        assertThat((String) afterFirst.get("last_error")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT next_attempt_at > now() + interval '30 seconds' FROM notifications WHERE id = ?",
                Boolean.class, id)).as("espera de al menos un minuto antes del reintento").isTrue();

        drain();
        assertThat(sender.callsTo(email)).as("aún no vence la espera").isEqualTo(1);

        makeDue(id);
        drain();

        Map<String, Object> afterSecond = row(id);
        assertThat(afterSecond.get("status")).isEqualTo("SENT");
        assertThat(afterSecond.get("attempt_count")).isEqualTo(2);
        assertThat(afterSecond.get("last_error")).as("el éxito limpia el último error").isNull();
        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).extracting(a -> a.get("succeeded")).containsExactly(false, true);
        assertThat(attempts).extracting(a -> a.get("attempt_number")).containsExactly(1, 2);
    }

    @Test
    void aPermanentFailureIsNotRetried() {
        String email = "permanent-" + UUID.randomUUID() + "@test.local";
        UUID id = insert("MATCH_CALLUP", email);

        drain();
        makeDue(id);
        drain();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("sent")).isEqualTo(false);
        assertThat(sender.callsTo(email)).isEqualTo(1);
        assertThat(attempts(id)).hasSize(1);
    }

    @Test
    void afterTheMaximumAttemptsANotificationFails() {
        String email = "transient-always-" + UUID.randomUUID() + "@test.local";
        UUID id = insert("MATCH_CALLUP", email);

        for (int attempt = 1; attempt <= 5; attempt++) {
            makeDue(id);
            drain();
            assertThat(row(id).get("attempt_count")).isEqualTo(attempt);
        }

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(attempts(id)).hasSize(5).allSatisfy(a -> assertThat(a.get("succeeded")).isEqualTo(false));

        makeDue(id);
        drain();
        assertThat(sender.callsTo(email)).as("un aviso fallido no se vuelve a intentar").isEqualTo(5);
    }

    @Test
    void aTypeWithoutTemplateFailsWithoutSending() {
        String email = "ok-" + UUID.randomUUID() + "@test.local";
        UUID id = insert("NO_SUCH_TYPE", email);

        drain();

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat((String) row.get("last_error")).startsWith("NO_TEMPLATE");
        assertThat(sender.callsTo(email)).isZero();
    }

    @Test
    void twoProcessorsNeverClaimTheSameNotification() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            ids.add(insert("MATCH_CALLUP", "ok-" + UUID.randomUUID() + "@test.local"));
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<List<DueNotification>> claim = () -> {
                start.await();
                return outbox.claimDue(20, Duration.ofMinutes(5));
            };
            Future<List<DueNotification>> a = pool.submit(claim);
            Future<List<DueNotification>> b = pool.submit(claim);
            start.countDown();
            Set<UUID> claimedByA = idsOf(a.get(30, TimeUnit.SECONDS));
            Set<UUID> claimedByB = idsOf(b.get(30, TimeUnit.SECONDS));

            assertThat(claimedByA).hasSize(20);
            assertThat(claimedByB).hasSize(20);
            Set<UUID> overlap = new HashSet<>(claimedByA);
            overlap.retainAll(claimedByB);
            assertThat(overlap).as("ningún aviso en manos de dos procesadores").isEmpty();
            Set<UUID> all = new HashSet<>(claimedByA);
            all.addAll(claimedByB);
            assertThat(all).containsExactlyInAnyOrderElementsOf(ids);
        } finally {
            pool.shutdownNow();
        }
        assertThat(outbox.claimDue(50, Duration.ofMinutes(5))).as("los reclamados están arrendados").isEmpty();
    }

    @Test
    void concurrentProcessorPassesSendEachNotificationExactlyOnce() throws Exception {
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String email = "ok-" + UUID.randomUUID() + "@test.local";
            emails.add(email);
            insert("MATCH_CALLUP", email);
        }
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    return processor.processDue();
                }));
            }
            start.countDown();
            int handled = 0;
            for (Future<Integer> run : runs) {
                handled += run.get(60, TimeUnit.SECONDS);
            }
            assertThat(handled).isEqualTo(30);
        } finally {
            pool.shutdownNow();
        }
        for (String email : emails) {
            assertThat(sender.callsTo(email)).as(email).isEqualTo(1);
        }
    }

    @Test
    void aClaimedButUnfinishedNotificationIsReclaimedWhenItsLeaseExpires() {
        UUID id = insert("MATCH_CALLUP", "ok-" + UUID.randomUUID() + "@test.local");

        List<DueNotification> first = outbox.claimDue(50, Duration.ofMinutes(5));
        assertThat(first).extracting(DueNotification::id).containsExactly(id);
        assertThat(first.get(0).attemptNumber()).isEqualTo(1);
        assertThat(outbox.claimDue(50, Duration.ofMinutes(5))).as("mientras dure el arriendo nadie más lo toma").isEmpty();

        // El proceso murió antes de registrar el resultado: el arriendo vence y el aviso vuelve a la cola sin gastar un intento.
        makeDue(id);
        List<DueNotification> second = outbox.claimDue(50, Duration.ofMinutes(5));
        assertThat(second).extracting(DueNotification::id).containsExactly(id);
        assertThat(second.get(0).attemptNumber()).isEqualTo(1);
        assertThat(row(id).get("status")).isEqualTo("PENDING");
    }

    @Test
    void aStaleResultCannotOverwriteAnAlreadyRecordedAttempt() {
        UUID id = insert("MATCH_CALLUP", "ok-" + UUID.randomUUID() + "@test.local");
        outbox.claimDue(50, Duration.ofMinutes(5));

        outbox.markSent(id, 1, "primero");
        outbox.markSent(id, 1, "repetido");
        outbox.markFailed(id, 1, "tardío", Duration.ofMinutes(1));

        Map<String, Object> row = row(id);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempt_count")).isEqualTo(1);
        assertThat(row.get("last_error")).isNull();
        List<Map<String, Object>> attempts = attempts(id);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).get("provider_response")).isEqualTo("primero");
    }

    @Test
    void theDeliveryEvidenceCannotBeAltered() {
        UUID id = insert("MATCH_CALLUP", "ok-" + UUID.randomUUID() + "@test.local");
        drain();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("UPDATE delivery_attempts SET succeeded = false WHERE notification_id = ?", id))
                .hasMessageContaining("IMMUTABLE_RECORD");
    }

    // --- soportes ------------------------------------------------------------------------------------------------

    private UUID insert(String type, String email) {
        return jdbc.queryForObject("""
                INSERT INTO notifications (notification_type, recipient_email, subject, payload)
                VALUES (?, ?, ?, ?::jsonb) RETURNING id
                """, UUID.class, type, email, "Convocatoria a partido", PAYLOAD);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("""
                SELECT status, attempt_count, last_error, sent_at IS NOT NULL AS sent
                FROM notifications WHERE id = ?""", id);
    }

    private List<Map<String, Object>> attempts(UUID id) {
        return jdbc.queryForList("""
                SELECT attempt_number, succeeded, provider_response, error
                FROM delivery_attempts WHERE notification_id = ? ORDER BY attempt_number""", id);
    }

    private void makeDue(UUID id) {
        jdbc.update("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
    }

    private void drain() {
        for (int i = 0; i < 50 && processor.processDue() > 0; i++) {
            // sigue hasta que no queden avisos vencidos
        }
    }

    private static Set<UUID> idsOf(List<DueNotification> claimed) {
        Set<UUID> ids = new HashSet<>();
        claimed.forEach(n -> ids.add(n.id()));
        return ids;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SenderConfig {

        @Bean
        @Primary
        ScriptedEmailSender scriptedEmailSender() {
            return new ScriptedEmailSender();
        }
    }

    /**
     * Proveedor de correo de guion. El prefijo del destinatario decide el resultado: {@code transient-once-} falla una vez
     * de forma transitoria, {@code transient-always-} siempre, {@code permanent-} de forma definitiva; cualquier otro entrega.
     */
    static final class ScriptedEmailSender implements EmailSender {

        private final List<OutboundEmail> delivered = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

        @Override
        public String send(OutboundEmail email) throws EmailDeliveryException {
            int call = calls.computeIfAbsent(email.to(), k -> new AtomicInteger()).incrementAndGet();
            if (email.to().startsWith("permanent-")) {
                throw new EmailDeliveryException("dirección rechazada", true, null);
            }
            if (email.to().startsWith("transient-always-") || (email.to().startsWith("transient-once-") && call == 1)) {
                throw new EmailDeliveryException("servidor no disponible", false, null);
            }
            delivered.add(email);
            return "script-" + UUID.randomUUID();
        }

        int callsTo(String address) {
            AtomicInteger count = calls.get(address);
            return count == null ? 0 : count.get();
        }

        List<OutboundEmail> deliveredTo(String address) {
            List<OutboundEmail> result = new ArrayList<>();
            for (OutboundEmail email : delivered) {
                if (email.to().equals(address)) {
                    result.add(email);
                }
            }
            return Collections.unmodifiableList(result);
        }
    }
}
