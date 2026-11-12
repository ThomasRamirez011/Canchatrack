package co.edu.unipamplona.canchatrack.competitions.infrastructure;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.unipamplona.canchatrack.competitions.application.CompetitionSettings;

@Configuration(proxyBeanMethods = false)
public class CompetitionsConfig {

    /** Misma zona del club que usa el comité: decide el día del partido (vigencia del certificado) y el día de hoy (mora). */
    @Bean
    CompetitionSettings competitionSettings(@Value("${canchatrack.zone-id:America/Bogota}") String zoneId) {
        return new CompetitionSettings(ZoneId.of(zoneId));
    }
}
