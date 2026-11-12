package co.edu.unipamplona.canchatrack.documents.infrastructure;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import co.edu.unipamplona.canchatrack.documents.application.DocumentSettings;
import co.edu.unipamplona.canchatrack.documents.application.FileSettings;

@Configuration(proxyBeanMethods = false)
public class DocumentsConfig {

    @Bean
    FileSettings fileSettings(@Value("${canchatrack.files.max-size:10MB}") DataSize maxSize) {
        return new FileSettings(maxSize.toBytes());
    }

    /** La zona del club decide qué día es «hoy» para vigencias y alertas (la zona del club, común a todos los módulos). */
    @Bean
    DocumentSettings documentSettings(@Value("${canchatrack.zone-id:America/Bogota}") String zoneId,
                                      @Value("${canchatrack.files.max-size:10MB}") DataSize maxSize) {
        return new DocumentSettings(ZoneId.of(zoneId), maxSize.toBytes());
    }
}
