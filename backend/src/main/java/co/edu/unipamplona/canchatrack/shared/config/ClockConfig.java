package co.edu.unipamplona.canchatrack.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puerto {@code Clock} del DDS (ISP, secc. 2.1): el dominio y la aplicación reciben un {@link Clock} inyectado
 * y nunca llaman a {@code Instant.now()}, de modo que vigencias, vencimientos y recordatorios son testeables.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
