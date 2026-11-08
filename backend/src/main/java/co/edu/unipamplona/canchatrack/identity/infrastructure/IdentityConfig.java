package co.edu.unipamplona.canchatrack.identity.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import co.edu.unipamplona.canchatrack.identity.application.AuthSettings;
import co.edu.unipamplona.canchatrack.identity.domain.LockoutPolicy;

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
                new LockoutPolicy(properties.maxFailedAttempts(), properties.lockDuration()));
    }
}
