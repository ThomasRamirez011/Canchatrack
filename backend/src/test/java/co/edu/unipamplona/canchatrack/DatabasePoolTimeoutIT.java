package co.edu.unipamplona.canchatrack;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Deuda D15: con la base caída, /actuator/health esperaba el tiempo por defecto de Hikari (30 s) antes de fallar y parecía
 * colgado. El pool debe rendirse pronto para que el indicador de base responda DOWN. Esta prueba fija el valor configurado;
 * el comportamiento con la base realmente detenida se midió a mano (runbook de PostgreSQL).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class DatabasePoolTimeoutIT {

    @Autowired
    private DataSource dataSource;

    @Test
    void thePoolGivesUpQuicklyWhenTheDatabaseIsUnreachable() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        assertThat(pool.getConnectionTimeout()).isEqualTo(5_000L);
    }
}
