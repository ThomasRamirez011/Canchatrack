package co.edu.unipamplona.canchatrack.scheduling.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.unipamplona.canchatrack.scheduling.application.AgendaSettings;
import co.edu.unipamplona.canchatrack.scheduling.domain.ReminderPolicy;

@Configuration(proxyBeanMethods = false)
class SchedulingConfig {

    /**
     * Un valor inválido de {@code canchatrack.scheduling.reminders.hours} o {@code ...match-hours} impide arrancar (con todos los problemas juntos)
     * en lugar de dejar recordatorios que nunca salen.
     */
    @Bean
    AgendaSettings agendaSettings(@Value("${canchatrack.app-url:}") String appUrl,
                                  @Value("${canchatrack.scheduling.reminders.hours:24,2}") String reminderHours,
                                  @Value("${canchatrack.scheduling.reminders.match-hours:48,2}") String matchReminderHours) {
        return new AgendaSettings(appUrl, ReminderPolicy.parseHours(reminderHours),
                ReminderPolicy.parseHours(matchReminderHours, "canchatrack.scheduling.reminders.match-hours"));
    }
}
