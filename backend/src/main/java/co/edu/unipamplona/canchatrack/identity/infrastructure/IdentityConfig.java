package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.unipamplona.canchatrack.identity.application.AccountSecuritySettings;
import co.edu.unipamplona.canchatrack.identity.application.AuthSettings;
import co.edu.unipamplona.canchatrack.identity.application.MfaCrypto;
import co.edu.unipamplona.canchatrack.identity.application.MfaSettings;
import co.edu.unipamplona.canchatrack.identity.application.RateLimitSettings;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentityProperties.class)
class IdentityConfig {

    @Bean
    PasswordEncoder passwordEncoder(IdentityProperties properties) {
        return new BCryptPasswordEncoder(properties.bcryptStrength());
    }

    @Bean
    AuthSettings authSettings(IdentityProperties properties) {
        return new AuthSettings(
                properties.refreshTtl(),
                properties.refreshAbsoluteTtl(),
                new LockoutPolicy(properties.maxFailedAttempts(), properties.lockDuration()),
                properties.requireVerifiedEmail());
    }

    @Bean
    AccountSecuritySettings accountSecuritySettings(IdentityProperties properties,
                                                    @Value("${canchatrack.app-url:}") String appUrl) {
        IdentityProperties.Recovery recovery = properties.recovery();
        return new AccountSecuritySettings(recovery.resetTtl(), recovery.verificationTtl(), appUrl, recovery.resetPath(),
                recovery.verifyPath(), recovery.retention());
    }

    @Bean
    RateLimitSettings rateLimitSettings(IdentityProperties properties) {
        IdentityProperties.RateLimit limits = properties.rateLimit();
        return new RateLimitSettings(limits.enabled(), Map.of(
                RateLimitBucket.LOGIN_IP, new RateLimitRule(limits.loginPerIp(), limits.loginWindow()),
                RateLimitBucket.REGISTER_IP, new RateLimitRule(limits.registerPerIp(), limits.registerWindow()),
                RateLimitBucket.RECOVERY_IP, new RateLimitRule(limits.recoveryPerIp(), limits.recoveryWindow()),
                RateLimitBucket.RECOVERY_EMAIL, new RateLimitRule(limits.recoveryPerEmail(), limits.recoveryWindow()),
                RateLimitBucket.CONFIRM_IP, new RateLimitRule(limits.confirmPerIp(), limits.confirmWindow())));
    }

    @Bean
    MfaSettings mfaSettings(IdentityProperties properties) {
        IdentityProperties.Mfa mfa = properties.mfa();
        Set<RoleCode> roles = mfa.requiredRoles().stream()
                .map(name -> RoleCode.valueOf(name.trim().toUpperCase(Locale.ROOT)))
                .collect(Collectors.toUnmodifiableSet());
        return new MfaSettings(mfa.enabled(), roles, mfa.issuer(), mfa.challengeTtl(), mfa.window(), mfa.backupCodes());
    }

    /**
     * Con el MFA activo la llave es obligatoria y la aplicación no arranca sin ella. Con el MFA apagado no se necesita,
     * pero si alguien la define igualmente se valida.
     */
    @Bean
    MfaCrypto mfaCrypto(IdentityProperties properties) {
        IdentityProperties.Mfa mfa = properties.mfa();
        String key = mfa.encryptionKey();
        if (key == null || key.isBlank()) {
            if (mfa.enabled()) {
                throw new IllegalStateException("canchatrack.identity.mfa.encryption-key (MFA_ENCRYPTION_KEY) es "
                        + "obligatoria con el MFA activo: 32 bytes en Base64 (por ejemplo, openssl rand -base64 32).");
            }
            return new UnconfiguredMfaCrypto();
        }
        return new AesGcmMfaCrypto(key);
    }
}
