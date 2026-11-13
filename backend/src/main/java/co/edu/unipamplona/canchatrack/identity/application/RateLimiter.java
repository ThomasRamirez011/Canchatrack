package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Service;

import co.edu.unipamplona.canchatrack.identity.domain.Emails;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Límite de tasa de los puntos de entrada públicos. Cada llamada a {@code check*} cuenta un intento y, si ya se superó
 * el tope de la ventana, lanza {@link ErrorCode#RATE_LIMITED} con la espera sugerida. Un intento rechazado no se
 * cuenta: quien insiste no alarga su propio bloqueo.
 *
 * <p>Este servicio no es transaccional: cada conteo se confirma solo (ver {@link RateLimitStore#tryRecord}).
 */
@Service
public class RateLimiter {

    private final RateLimitStore store;
    private final RateLimitSettings settings;
    private final Clock clock;

    public RateLimiter(RateLimitStore store, RateLimitSettings settings, Clock clock) {
        this.store = store;
        this.settings = settings;
        this.clock = clock;
    }

    /** Limita por IP de origen. Sin IP conocida no hay a quién atribuir el intento y no se limita. */
    public void checkIp(RateLimitBucket bucket, ClientOrigin origin) {
        if (!settings.enabled() || origin == null || origin.ip() == null) {
            return;
        }
        check(bucket, RateLimitSubjects.ipKey(origin.ip()));
    }

    /** Limita por correo. Se cuenta igual exista o no la cuenta, para no revelar cuáles existen. */
    public void checkEmail(RateLimitBucket bucket, String email) {
        if (!settings.enabled()) {
            return;
        }
        String normalized = Emails.normalize(email);
        if (normalized == null || normalized.isBlank()) {
            return;
        }
        check(bucket, RateLimitSubjects.emailKey(normalized));
    }

    private void check(RateLimitBucket bucket, String key) {
        RateLimitRule rule = settings.ruleFor(bucket);
        Instant now = clock.instant();
        RateLimitStore.Attempt attempt = store.tryRecord(bucket, RateLimitSubjects.hash(key), rule, now);
        if (!attempt.allowed()) {
            throw tooMany(attempt.retryAfterSeconds());
        }
    }

    public static DomainException tooMany(long retryAfterSeconds) {
        long wait = Math.max(1, retryAfterSeconds);
        return new DomainException(ErrorCode.RATE_LIMITED,
                "Demasiados intentos. Intente de nuevo en " + wait + (wait == 1 ? " segundo." : " segundos."),
                Map.of("retryAfterSeconds", wait));
    }
}
