package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.notifications.application.DueNotification;
import co.edu.unipamplona.canchatrack.notifications.application.NotificationOutbox;

/**
 * Cola persistente sobre {@code notifications} y {@code delivery_attempts}. El reclamo usa {@code FOR UPDATE SKIP LOCKED}
 * y un arriendo en {@code next_attempt_at}: dos instancias nunca toman el mismo aviso y el envío ocurre fuera de la
 * transacción. Cada resultado se registra solo si el aviso sigue pendiente y no tiene ya ese intento, de modo que un
 * proceso rezagado no pisa el resultado de otro.
 */
@Repository
public class JdbcNotificationOutbox implements NotificationOutbox {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, String>> PAYLOAD = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public JdbcNotificationOutbox(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<DueNotification> claimDue(int limit, Duration lease) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id
                    FROM notifications
                    WHERE status IN ('PENDING', 'RETRYING') AND next_attempt_at <= now()
                    ORDER BY next_attempt_at, created_at, id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE notifications n
                SET next_attempt_at = now() + make_interval(secs => ?::double precision)
                FROM due
                WHERE n.id = due.id
                RETURNING n.id, n.notification_type, n.recipient_email, n.subject, n.payload::text AS payload,
                          n.attempt_count
                """, DUE, limit, lease.toMillis() / 1000.0);
    }

    @Override
    @Transactional
    public void markSent(UUID id, int attemptNumber, String providerResponse) {
        int updated = jdbc.update("""
                UPDATE notifications
                SET status = 'SENT', sent_at = now(), attempt_count = ?, last_error = NULL,
                    payload = %s
                WHERE id = ? AND status IN ('PENDING', 'RETRYING') AND attempt_count < ?
                """.formatted(SCRUBBED_PAYLOAD), attemptNumber, id, attemptNumber);
        if (updated == 1) {
            recordAttempt(id, attemptNumber, true, providerResponse, null);
        }
    }

    @Override
    @Transactional
    public void markFailed(UUID id, int attemptNumber, String error, Duration retryAfter) {
        int updated;
        if (retryAfter == null) {
            updated = jdbc.update("""
                    UPDATE notifications
                    SET status = 'FAILED', attempt_count = ?, last_error = ?,
                        payload = %s
                    WHERE id = ? AND status IN ('PENDING', 'RETRYING') AND attempt_count < ?
                    """.formatted(SCRUBBED_PAYLOAD), attemptNumber, error, id, attemptNumber);
        } else {
            updated = jdbc.update("""
                    UPDATE notifications
                    SET status = 'RETRYING', attempt_count = ?, last_error = ?,
                        next_attempt_at = now() + make_interval(secs => ?::double precision)
                    WHERE id = ? AND status IN ('PENDING', 'RETRYING') AND attempt_count < ?
                    """, attemptNumber, error, retryAfter.toMillis() / 1000.0, id, attemptNumber);
        }
        if (updated == 1) {
            recordAttempt(id, attemptNumber, false, null, error);
        }
    }

    private void recordAttempt(UUID id, int attemptNumber, boolean succeeded, String providerResponse, String error) {
        jdbc.update("""
                INSERT INTO delivery_attempts (notification_id, attempt_number, succeeded, provider_response, error)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (notification_id, attempt_number) DO NOTHING
                """, id, attemptNumber, succeeded, providerResponse, error);
    }

    /**
     * Los avisos con un secreto de un solo uso (enlace o código de recuperación y de verificación) pierden esas claves
     * cuando el aviso termina, enviado o fallido: el valor ya no sirve y no debe quedar guardado en claro.
     */
    private static final String SCRUBBED_PAYLOAD = """
            CASE WHEN notification_type IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')
                     THEN payload - 'token' - 'url'
                 ELSE payload END""";

    private static final RowMapper<DueNotification> DUE = (ResultSet rs, int row) -> new DueNotification(
            rs.getObject("id", UUID.class), rs.getString("notification_type"), rs.getString("recipient_email"),
            rs.getString("subject"), payload(rs), rs.getInt("attempt_count") + 1);

    private static Map<String, String> payload(ResultSet rs) throws SQLException {
        try {
            return MAPPER.readValue(rs.getString("payload"), PAYLOAD);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
