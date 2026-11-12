package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.unipamplona.canchatrack.players.application.CallSettings;
import co.edu.unipamplona.canchatrack.players.application.CommitteeSettings;
import co.edu.unipamplona.canchatrack.players.application.EnrollmentSettings;

@Configuration(proxyBeanMethods = false)
public class PlayersConfig {

    /** Versión del texto de consentimiento vigente; se cambia por entorno cuando el club publique un texto nuevo. */
    @Bean
    EnrollmentSettings enrollmentSettings(@Value("${canchatrack.enrollment.consent-version:v1}") String consentVersion) {
        return new EnrollmentSettings(consentVersion);
    }

    /** Vigencia máxima de una habilitación excepcional (RF-044): acota el riesgo de una excepción olvidada. */
    @Bean
    CallSettings callSettings(@Value("${canchatrack.calls.max-exception-validity:30d}") Duration maxExceptionValidity) {
        return new CallSettings(maxExceptionValidity);
    }

    /**
     * Parámetros de la decisión del comité, provisionales hasta que el club responda DP-01: roles que pueden integrar el
     * panel de tres evaluadores y si se admite asignar a una categoría llena dejando constancia del motivo.
     */
    @Bean
    CommitteeSettings committeeSettings(
            @Value("${canchatrack.committee.panel-roles:EVALUATOR,COACH,COMMITTEE}") List<String> panelRoles,
            @Value("${canchatrack.committee.allow-capacity-exception:true}") boolean allowCapacityException,
            @Value("${canchatrack.zone-id:America/Bogota}") String zoneId) {
        return CommitteeSettings.of(panelRoles, allowCapacityException, ZoneId.of(zoneId));
    }
}
