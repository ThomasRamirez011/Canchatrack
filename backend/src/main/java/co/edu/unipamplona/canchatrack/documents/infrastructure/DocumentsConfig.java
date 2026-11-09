package co.edu.unipamplona.canchatrack.documents.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import co.edu.unipamplona.canchatrack.documents.application.FileSettings;

@Configuration(proxyBeanMethods = false)
public class DocumentsConfig {

    @Bean
    FileSettings fileSettings(@Value("${canchatrack.files.max-size:10MB}") DataSize maxSize) {
        return new FileSettings(maxSize.toBytes());
    }
}
