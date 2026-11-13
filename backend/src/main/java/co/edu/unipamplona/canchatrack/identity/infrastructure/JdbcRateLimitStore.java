package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.identity.application.RateLimitStore;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;

/**
 * Ventana deslizante en PostgreSQL: funciona con varias instancias y sobrevive a reinicios. Un candado consultivo por
 * cubeta y sujeto serializa los intentos simultáneos del mismo sujeto (sin él, dos solicitudes concurrentes podrían leer
 * "queda un cupo" a la vez y registrar las dos).
 */
@Repository
class JdbcRateLimitStore implements RateLimitStore {

    private final JdbcTemplate jdbc;

    JdbcRateLimitStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Attempt tryRecord(RateLimitBucket bucket, String subjectHash, RateLimitRule rule, Instant now) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
        }, bucket.name() + ":" + subjectHash);

        List<Instant> inWindow = jdbc.query("""
                SELECT occurred_at FROM rate_limit_events
                WHERE bucket = ? AND subject_hash = ? AND occurred_at > ?
                """, (rs, rowNum) -> rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                bucket.name(), subjectHash, toOffset(rule.windowStart(now)));
        if (rule.isExceeded(inWindow.size())) {
            return new Attempt(false, rule.retryAfterSeconds(inWindow, now));
        }
        jdbc.update("INSERT INTO rate_limit_events (bucket, subject_hash, occurred_at) VALUES (?, ?, ?)",
                bucket.name(), subjectHash, toOffset(now));
        return new Attempt(true, 0);
    }

    @Override
    @Transactional
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM rate_limit_events WHERE occurred_at < ?", toOffset(cutoff));
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
