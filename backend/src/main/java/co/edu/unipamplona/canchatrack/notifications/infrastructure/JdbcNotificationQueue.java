package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;

@Repository
public class JdbcNotificationQueue implements NotificationQueue {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public JdbcNotificationQueue(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String type, Collection<UUID> recipientUserIds, String subject, Map<String, String> payload,
                        String relatedEntityType, UUID relatedEntityId, String dedupScope) {
        String payloadJson = write(payload);
        for (UUID userId : recipientUserIds) {
            String dedupKey = type + ":" + relatedEntityId + ":" + userId + ":" + dedupScope;
            jdbc.update("""
                    INSERT INTO notifications (notification_type, recipient_user_id, recipient_email, subject, payload,
                                               related_entity_type, related_entity_id, dedup_key)
                    SELECT ?::varchar, u.id, u.email, ?::varchar, ?::jsonb, ?::varchar, ?::uuid, ?::varchar
                    FROM users u
                    WHERE u.id = ? AND u.status = 'ACTIVE'
                    ON CONFLICT (dedup_key) DO NOTHING
                    """, type, subject, payloadJson, relatedEntityType, relatedEntityId, dedupKey, userId);
        }
    }

    private static String write(Map<String, String> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("payload is not serializable", e);
        }
    }
}
