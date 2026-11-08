package co.edu.unipamplona.canchatrack.identity.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import co.edu.unipamplona.canchatrack.identity.application.UserAdministrationService;

/**
 * Crea la primera cuenta (coordinador) si y solo si no existe ninguna y hay credenciales configuradas
 * ({@code canchatrack.identity.bootstrap.*}, en producción por variables de entorno). Sin ella nadie podría crear a los
 * demás usuarios. Tras el primer arranque conviene retirar las variables y cambiar la contraseña.
 */
@Component
class CoordinatorBootstrap implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CoordinatorBootstrap.class);
    private static final String DEFAULT_NAME = "Coordinador";

    private final IdentityProperties properties;
    private final UserAdministrationService administration;

    CoordinatorBootstrap(IdentityProperties properties, UserAdministrationService administration) {
        this.properties = properties;
        this.administration = administration;
    }

    @Override
    public void run(ApplicationArguments args) {
        IdentityProperties.Bootstrap bootstrap = properties.bootstrap();
        if (bootstrap == null || !bootstrap.configured()) {
            return;
        }
        String name = bootstrap.coordinatorName() == null || bootstrap.coordinatorName().isBlank()
                ? DEFAULT_NAME : bootstrap.coordinatorName();
        boolean created = administration.bootstrapCoordinatorIfEmpty(
                bootstrap.coordinatorEmail(), name, bootstrap.coordinatorPassword());
        if (created) {
            LOG.info("Cuenta de coordinador inicial creada para {}", bootstrap.coordinatorEmail());
        }
    }
}
