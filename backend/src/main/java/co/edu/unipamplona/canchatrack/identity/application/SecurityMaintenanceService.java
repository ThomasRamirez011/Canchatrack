package co.edu.unipamplona.canchatrack.identity.application;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;

/**
 * Limpieza periódica de lo que la seguridad de cuentas deja atrás: refresh tokens de sesiones vencidas o revocadas,
 * tokens de un solo uso ya gastados y eventos del límite de tasa que salieron de toda ventana. Idempotente: repetirla
 * no borra nada que no sea ya inservible.
 */
@Service
public class SecurityMaintenanceService {

    private final RefreshTokenStore refreshTokens;
    private final AccountTokenStore accountTokens;
    private final RateLimitStore rateLimits;
    private final AccountSecuritySettings settings;
    private final Clock clock;

    public SecurityMaintenanceService(RefreshTokenStore refreshTokens, AccountTokenStore accountTokens,
                                      RateLimitStore rateLimits, AccountSecuritySettings settings, Clock clock) {
        this.refreshTokens = refreshTokens;
        this.accountTokens = accountTokens;
        this.rateLimits = rateLimits;
        this.settings = settings;
        this.clock = clock;
    }

    @Transactional
    public Result purge() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(settings.retention());
        int refresh = refreshTokens.deleteInactiveBefore(cutoff);
        int account = accountTokens.deleteFinishedBefore(cutoff);
        // Un evento más viejo que la mayor ventana posible no puede contar nunca más.
        int events = rateLimits.deleteOlderThan(now.minus(RateLimitRule.MAX_WINDOW));
        return new Result(refresh, account, events);
    }

    /** Filas borradas por tabla. */
    public record Result(int refreshTokens, int accountTokens, int rateLimitEvents) {

        public int total() {
            return refreshTokens + accountTokens + rateLimitEvents;
        }
    }
}
