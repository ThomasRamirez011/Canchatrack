package co.edu.unipamplona.canchatrack.identity.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.identity.domain.Emails;
import co.edu.unipamplona.canchatrack.identity.domain.PasswordPolicy;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.domain.UserAccount;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentNumber;
import co.edu.unipamplona.canchatrack.shared.domain.IdDocumentType;

/**
 * Autorregistro de acudientes (DAD CU-02 exige un usuario autenticado para radicar: esta es la forma de obtener la
 * cuenta). Crea la cuenta con el rol {@code GUARDIAN} y su ficha de acudiente en una sola transacción.
 *
 * <p>La cuenta nace con el correo sin confirmar y se envía el enlace de confirmación en la misma transacción. El límite
 * de tasa por IP lo aplica el controlador. Límite conocido: por ser un alta pública revela si un correo o documento ya
 * está registrado (se documenta en el README).
 */
@Service
public class GuardianRegistrationService {

    static final String ACTION_GUARDIAN_REGISTERED = "GUARDIAN_REGISTERED";
    private static final int MAX_FULL_NAME = 200;
    private static final Pattern PHONE = Pattern.compile("[0-9+() -]{7,30}");

    private final UserAccountRepository users;
    private final GuardianRegistry guardians;
    private final PasswordHasher hasher;
    private final AuditWriter audit;
    private final EmailVerificationService verification;

    public GuardianRegistrationService(UserAccountRepository users, GuardianRegistry guardians, PasswordHasher hasher,
                                       AuditWriter audit, EmailVerificationService verification) {
        this.users = users;
        this.guardians = guardians;
        this.hasher = hasher;
        this.audit = audit;
        this.verification = verification;
    }

    @Transactional
    public UserView register(RegisterGuardianCommand command, ClientOrigin origin) {
        List<Map<String, String>> errors = new ArrayList<>();

        String email = Emails.normalize(command.email());
        if (!Emails.isValid(email)) {
            errors.add(error("email", "El correo no es válido."));
        }
        String fullName = command.fullName() == null ? "" : command.fullName().trim();
        if (fullName.isEmpty() || fullName.length() > MAX_FULL_NAME) {
            errors.add(error("fullName", "El nombre es obligatorio y no puede superar " + MAX_FULL_NAME + " caracteres."));
        }
        for (String violation : PasswordPolicy.violations(command.password(), email)) {
            errors.add(error("password", violation));
        }
        Optional<IdDocumentType> type = IdDocumentType.parse(command.documentType()).filter(IdDocumentType::isForAdults);
        if (type.isEmpty()) {
            errors.add(error("documentType", "El tipo de documento de un acudiente debe ser CC, CE o PA."));
        }
        Optional<String> number = IdDocumentNumber.normalize(command.documentNumber());
        if (number.isEmpty()) {
            errors.add(error("documentNumber", "El número de documento debe tener entre 4 y 30 letras, dígitos o guiones."));
        }
        String phone = command.phone() == null || command.phone().isBlank() ? null : command.phone().trim();
        if (phone != null && !PHONE.matcher(phone).matches()) {
            errors.add(error("phone", "El teléfono no es válido."));
        }
        if (errors.isEmpty()) {
            if (users.existsByEmail(email)) {
                errors.add(error("email", "El correo ya está registrado."));
            }
            if (guardians.existsByDocument(type.orElseThrow(), number.orElseThrow())) {
                errors.add(error("documentNumber", "El documento ya está registrado."));
            }
        }
        failIfAny(errors);

        UserAccount created;
        try {
            created = users.create(new UserAccountRepository.NewUser(
                    email, fullName, hasher.hash(command.password()), EnumSet.of(RoleCode.GUARDIAN), null, false));
            guardians.create(created.id(), type.orElseThrow(), number.orElseThrow(), phone);
        } catch (DuplicateEmailException e) {
            throw validation(List.of(error("email", "El correo ya está registrado.")));
        } catch (DuplicateGuardianDocumentException e) {
            throw validation(List.of(error("documentNumber", "El documento ya está registrado.")));
        }

        verification.sendFor(created.id(), origin);

        ClientOrigin from = origin == null ? ClientOrigin.UNKNOWN : origin;
        audit.write(AuditRecord.builder(ACTION_GUARDIAN_REGISTERED, "user")
                .actor(created.id(), RoleCode.GUARDIAN.name())
                .entity(created.id())
                .after("{\"roles\":[\"GUARDIAN\"]}")
                .origin(from.ip(), from.userAgent())
                .build());
        return UserView.from(created);
    }

    private static void failIfAny(List<Map<String, String>> errors) {
        if (!errors.isEmpty()) {
            throw validation(errors);
        }
    }

    private static DomainException validation(List<Map<String, String>> errors) {
        return new DomainException(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", List.copyOf(errors)));
    }

    private static Map<String, String> error(String field, String message) {
        return Map.of("field", field, "message", message);
    }
}
