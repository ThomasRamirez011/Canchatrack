package co.edu.unipamplona.canchatrack.payments.infrastructure;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import co.edu.unipamplona.canchatrack.payments.application.ArrearsSettings;

@Configuration(proxyBeanMethods = false)
public class PaymentsConfig {

    /** Los días de gracia son los de la elegibilidad de convocatoria (una sola definición de «mora» en el sistema). */
    @Bean
    ArrearsSettings arrearsSettings(@Value("${canchatrack.zone-id:America/Bogota}") String zoneId,
                                    @Value("${canchatrack.payments.arrears-grace-days:0}") int graceDays,
                                    @Value("${canchatrack.payments.arrears-notices.interval-days:7}") int intervalDays,
                                    @Value("${canchatrack.payments.arrears-notices.max-notices:8}") int maxNotices,
                                    @Value("${canchatrack.app-url:}") String appUrl) {
        return new ArrearsSettings(ZoneId.of(zoneId), graceDays, intervalDays, maxNotices, appUrl);
    }
}
