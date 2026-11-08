package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeHasher;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryUsers;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class UserAdministrationServiceTest {

    private final InMemoryUsers users = new InMemoryUsers();
    private final RecordingAudit audit = new RecordingAudit();
    private final UserAdministrationService service = new UserAdministrationService(users, new FakeHasher(), audit);

    private UserView create(String email, String password, String... roles) {
        return service.createStaffUser(new CreateUserCommand(email, "Nombre Apellido", password, List.of(roles)),
                UUID.randomUUID(), "COORDINATOR", ClientOrigin.UNKNOWN);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> errorsOf(DomainException e) {
        return (List<Map<String, String>>) e.getExtensions().get("errors");
    }

    private void assertRejectedWithErrorOn(CreateUserCommand command, String field) {
        assertThatThrownBy(() -> service.createStaffUser(command, UUID.randomUUID(), "COORDINATOR", null))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(errorsOf(e)).extracting(m -> m.get("field")).contains(field);
                });
        assertThat(users.count()).as("una alta inválida no crea cuentas").isZero();
    }

    @Test
    void createsStaffAccountWithNormalizedEmailRolesAndAudit() {
        UserView created = create("  Coach@Club.TEST ", "Entrenador-2026", "COACH", "EVALUATOR");

        assertThat(created.email()).isEqualTo("coach@club.test");
        assertThat(created.roles()).containsExactly("COACH", "EVALUATOR");
        assertThat(audit.count("USER_CREATED")).isEqualTo(1);
        assertThat(audit.records.toString()).doesNotContain("Entrenador-2026");
    }

    @Test
    void roleAndAccountErrorsAreReportedTogether() {
        CreateUserCommand command = new CreateUserCommand("x@club.test", "X", "debil1", List.of("GUARDIAN"));
        assertThatThrownBy(() -> service.createStaffUser(command, UUID.randomUUID(), "COORDINATOR", null))
                .isInstanceOfSatisfying(DomainException.class, e ->
                        assertThat(errorsOf(e)).extracting(m -> m.get("field")).contains("roles", "password"));
        assertThat(users.count()).isZero();
    }

    @Test
    void guardianAccountsAreNotCreatedHere() {
        assertRejectedWithErrorOn(new CreateUserCommand("g@club.test", "G", "Entrenador-2026", List.of("GUARDIAN")), "roles");
    }

    @Test
    void unknownOrMissingRolesAreRejected() {
        assertRejectedWithErrorOn(new CreateUserCommand("x@club.test", "X", "Entrenador-2026", List.of("ROOT")), "roles");
        assertRejectedWithErrorOn(new CreateUserCommand("x@club.test", "X", "Entrenador-2026", List.of()), "roles");
        assertRejectedWithErrorOn(new CreateUserCommand("x@club.test", "X", "Entrenador-2026", null), "roles");
    }

    @Test
    void weakPasswordsAndInvalidEmailsAreRejected() {
        assertRejectedWithErrorOn(new CreateUserCommand("x@club.test", "X", "corta1", List.of("COACH")), "password");
        assertRejectedWithErrorOn(new CreateUserCommand("x@club.test", "X", "soloLetrasLargas", List.of("COACH")), "password");
        assertRejectedWithErrorOn(new CreateUserCommand("no-es-correo", "X", "Entrenador-2026", List.of("COACH")), "email");
        assertRejectedWithErrorOn(new CreateUserCommand("x@club.test", "  ", "Entrenador-2026", List.of("COACH")), "fullName");
    }

    @Test
    void duplicateEmailIsRejectedIgnoringCase() {
        create("coach@club.test", "Entrenador-2026", "COACH");

        assertThatThrownBy(() -> create("COACH@club.test", "Entrenador-2026", "COACH"))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(errorsOf(e)).extracting(m -> m.get("field")).contains("email");
                });
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void bootstrapCreatesTheFirstCoordinatorOnlyWhenThereAreNoAccounts() {
        assertThat(service.bootstrapCoordinatorIfEmpty("coord@club.test", "Coordinador", "Clave-Segura-123")).isTrue();
        assertThat(users.byEmail("coord@club.test").roles()).extracting(Enum::name).containsExactly("COORDINATOR");
        assertThat(audit.count("USER_BOOTSTRAPPED")).isEqualTo(1);

        assertThat(service.bootstrapCoordinatorIfEmpty("otro@club.test", "Otro", "Clave-Segura-123")).isFalse();
        assertThat(users.count()).isEqualTo(1);
    }

    @Test
    void bootstrapStillEnforcesThePasswordPolicy() {
        assertThatThrownBy(() -> service.bootstrapCoordinatorIfEmpty("coord@club.test", "Coordinador", "debil"))
                .isInstanceOf(DomainException.class);
        assertThat(users.count()).isZero();
    }
}
