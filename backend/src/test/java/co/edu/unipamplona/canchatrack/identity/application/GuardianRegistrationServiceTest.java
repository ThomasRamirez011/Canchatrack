package co.edu.unipamplona.canchatrack.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import co.edu.unipamplona.canchatrack.identity.application.Fakes.FakeHasher;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryGuardians;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.InMemoryUsers;
import co.edu.unipamplona.canchatrack.identity.application.Fakes.RecordingAudit;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

class GuardianRegistrationServiceTest {

    private final InMemoryUsers users = new InMemoryUsers();
    private final InMemoryGuardians guardians = new InMemoryGuardians();
    private final RecordingAudit audit = new RecordingAudit();
    private final GuardianRegistrationService service =
            new GuardianRegistrationService(users, guardians, new FakeHasher(), audit);

    private static RegisterGuardianCommand command(String email, String password, String type, String number, String phone) {
        return new RegisterGuardianCommand(email, "Ana Gómez", password, type, number, phone);
    }

    @SuppressWarnings("unchecked")
    private static List<String> errorFields(DomainException e) {
        return ((List<Map<String, String>>) e.getExtensions().get("errors")).stream().map(m -> m.get("field")).toList();
    }

    private DomainException rejected(RegisterGuardianCommand command) {
        try {
            service.register(command, null);
        } catch (DomainException e) {
            assertThat(e.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
            return e;
        }
        throw new AssertionError("se esperaba DomainException");
    }

    @Test
    void registersTheAccountWithTheGuardianRoleAndItsRecord() {
        UserView view = service.register(command("  Ana@Club.TEST ", "Clave-Segura-123", "cc", " 1020-304 ", "300 123 4567"), null);

        assertThat(view.email()).isEqualTo("ana@club.test");
        assertThat(view.roles()).containsExactly("GUARDIAN");
        assertThat(guardians.guardianIdByUser).containsKey(view.id());
        assertThat(guardians.existsByDocument(co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType.CC, "1020-304")).isTrue();
        assertThat(guardians.phoneByUser.get(view.id())).isEqualTo("300 123 4567");
        assertThat(users.byEmail("ana@club.test").passwordHash()).isEqualTo("hash:Clave-Segura-123");
        assertThat(audit.count("GUARDIAN_REGISTERED")).isEqualTo(1);
    }

    @Test
    void theAuditSummaryHasNoDocumentOrContactData() {
        service.register(command("ana@club.test", "Clave-Segura-123", "CC", "1020304050", "3001234567"), null);

        String after = audit.records.get(0).afterJson();
        assertThat(after).doesNotContain("1020304050").doesNotContain("3001234567").doesNotContain("ana@club.test");
    }

    @Test
    void everyInvalidFieldIsReportedTogether() {
        DomainException e = rejected(new RegisterGuardianCommand("no-es-correo", "", "debil1", "TI", "ab", "letras"));

        assertThat(errorFields(e)).contains("email", "fullName", "password", "documentType", "documentNumber", "phone");
        assertThat(users.count()).isZero();
    }

    @Test
    void minorsDocumentsAreNotAcceptedForGuardians() {
        assertThat(errorFields(rejected(command("a@club.test", "Clave-Segura-123", "RC", "1020304050", null))))
                .containsExactly("documentType");
    }

    @Test
    void phoneIsOptional() {
        service.register(command("a@club.test", "Clave-Segura-123", "CC", "1020304050", "   "), null);

        assertThat(guardians.phoneByUser.values()).containsOnlyNulls();
    }

    @Test
    void duplicateEmailAndDocumentAreRejectedWithoutCreatingAnything() {
        service.register(command("ana@club.test", "Clave-Segura-123", "CC", "1020304050", null), null);

        assertThat(errorFields(rejected(command("ANA@club.test", "Clave-Segura-123", "CC", "9999999999", null))))
                .containsExactly("email");
        assertThat(errorFields(rejected(command("otra@club.test", "Clave-Segura-123", "CC", "1020304050", null))))
                .containsExactly("documentNumber");
        assertThat(users.count()).isEqualTo(1);
        assertThat(audit.count("GUARDIAN_REGISTERED")).isEqualTo(1);
    }

    @Test
    void theCommandNeverPrintsThePassword() {
        assertThat(command("a@club.test", "Clave-Segura-123", "CC", "1020304050", null).toString())
                .doesNotContain("Clave-Segura-123");
    }
}
