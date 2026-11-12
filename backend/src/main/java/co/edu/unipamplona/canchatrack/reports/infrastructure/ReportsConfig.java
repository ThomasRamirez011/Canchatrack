package co.edu.unipamplona.canchatrack.reports.infrastructure;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.unipamplona.canchatrack.reports.application.ReportSettings;

@Configuration(proxyBeanMethods = false)
public class ReportsConfig {

    /** Misma zona del club que usan el comité y las convocatorias: decide qué día es «hoy» para los vencimientos. */
    @Bean
    ReportSettings reportSettings(@Value("${canchatrack.zone-id:America/Bogota}") String zoneId) {
        return new ReportSettings(ZoneId.of(zoneId));
    }
}
