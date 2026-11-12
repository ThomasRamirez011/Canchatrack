package co.edu.unipamplona.canchatrack.notifications.infrastructure;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import co.edu.unipamplona.canchatrack.notifications.application.NotificationSettings;
import co.edu.unipamplona.canchatrack.notifications.domain.RetryPolicy;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NotificationProperties.class)
@EnableScheduling
class NotificationsConfig {

    @Bean
    NotificationSettings notificationSettings(NotificationProperties properties,
                                              @Value("${canchatrack.zone-id}") String zoneId) {
        NotificationProperties.Processor processor = properties.processor();
        NotificationProperties.Retry retry = properties.retry();
        return new NotificationSettings(processor.batchSize(), processor.lease(), ZoneId.of(zoneId),
                new RetryPolicy(retry.maxAttempts(), retry.baseDelay(), retry.maxDelay()));
    }
}
