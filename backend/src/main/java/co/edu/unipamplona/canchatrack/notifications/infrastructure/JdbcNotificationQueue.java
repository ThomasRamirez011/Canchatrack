package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import co.edu.unipamplona.canchatrack.notifications.api.NotificationQueue;

@Repository
public class JdbcNotificationQueue implements NotificationQueue {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

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
            String dedupKey = dedupKey(type, relatedEntityId, userId, dedupScope);
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

    /**
     * La columna {@code dedup_key} admite 150 caracteres y tipo + dos UUID + ámbito la superan: se guarda el tipo (legible)
     * y el SHA-256 de la combinación, que es determinista y de largo fijo.
     */
    static String dedupKey(String type, UUID relatedEntityId, UUID userId, String dedupScope) {
        String raw = relatedEntityId + ":" + userId + ":" + dedupScope;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return type + ":" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String write(Map<String, String> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("payload is not serializable", e);
        }
    }
}
