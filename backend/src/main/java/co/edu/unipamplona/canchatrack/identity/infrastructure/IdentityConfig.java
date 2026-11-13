package co.edu.unipamplona.canchatrack.identity.infrastructure;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.unipamplona.canchatrack.identity.application.AccountSecuritySettings;
import co.edu.unipamplona.canchatrack.identity.application.AuthSettings;
import co.edu.unipamplona.canchatrack.identity.application.RateLimitSettings;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitBucket;
import co.edu.unipamplona.canchatrack.identity.domain.RateLimitRule;

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
}
