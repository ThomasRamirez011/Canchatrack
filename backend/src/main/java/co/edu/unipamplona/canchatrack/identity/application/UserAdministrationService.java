package co.edu.unipamplona.canchatrack.identity.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

/**
 * Alta de cuentas de personal (coordinador, entrenador, evaluador, comité técnico). Las cuentas de acudiente NO se
 * crean aquí: nacen con la radicación de la inscripción, porque exigen documento de identidad y vínculo con el jugador.
 */
@Service
public class UserAdministrationService {

    static final String ACTION_USER_CREATED = "USER_CREATED";
    static final String ACTION_USER_BOOTSTRAPPED = "USER_BOOTSTRAPPED";
    private static final int MAX_FULL_NAME = 200;

    private final UserAccountRepository users;
    private final PasswordHasher hasher;
    private final AuditWriter audit;

    public UserAdministrationService(UserAccountRepository users, PasswordHasher hasher, AuditWriter audit) {
        this.users = users;
        this.hasher = hasher;
        this.audit = audit;
    }

    /**
     * Crea una cuenta de personal. Quien llama debe haber verificado que el actor es coordinador (la regla de acceso
     * vive en el adaptador de entrada); aquí solo se registra a quién se atribuye.
     */
    @Transactional
    public UserView createStaffUser(CreateUserCommand command, UUID actorId, String actorRole, ClientOrigin origin) {
        UserAccount created = create(command, actorId, validatedRoles(command));
        auditCreation(ACTION_USER_CREATED, created, actorId, actorRole, origin);
        return UserView.from(created);
    }

    /**
     * Primera cuenta del sistema (coordinador). Solo procede si no existe ninguna cuenta; sin ella nadie podría crear
     * a los demás. Devuelve {@code true} si la creó.
     */
    @Transactional
    public boolean bootstrapCoordinatorIfEmpty(String email, String fullName, String password) {
        if (users.count() > 0) {
            return false;
        }
        CreateUserCommand command = new CreateUserCommand(email, fullName, password, List.of(RoleCode.COORDINATOR.name()));
        UserAccount created = create(command, null, validatedRoles(command));
        auditCreation(ACTION_USER_BOOTSTRAPPED, created, null, null, ClientOrigin.UNKNOWN);
        return true;
    }

    private UserAccount create(CreateUserCommand command, UUID createdBy, Set<RoleCode> roles) {
        String email = Emails.normalize(command.email());
        List<Map<String, String>> errors = new ArrayList<>();

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
        if (errors.isEmpty() && users.existsByEmail(email)) {
            errors.add(error("email", "El correo ya está registrado."));
        }
        failIfAny(errors);

        try {
            return users.create(new UserAccountRepository.NewUser(
                    email, fullName, hasher.hash(command.password()), roles, createdBy));
        } catch (DuplicateEmailException e) {
            // Carrera entre la verificación y el INSERT: el índice único tiene la última palabra.
            throw validation(List.of(error("email", "El correo ya está registrado.")));
        }
    }

    private Set<RoleCode> validatedRoles(CreateUserCommand command) {
        List<Map<String, String>> errors = new ArrayList<>();
        Set<RoleCode> roles = EnumSet.noneOf(RoleCode.class);
        List<String> requested = command.roles() == null ? List.of() : command.roles();
        if (requested.isEmpty()) {
            errors.add(error("roles", "Debe indicar al menos un rol."));
        }
        for (String code : requested) {
            RoleCode.parse(code).ifPresentOrElse(role -> {
                if (role == RoleCode.GUARDIAN) {
                    errors.add(error("roles", "Las cuentas de acudiente se crean con la inscripción, no aquí."));
                } else {
                    roles.add(role);
                }
            }, () -> errors.add(error("roles", "Rol desconocido: " + sanitize(code))));
        }
        failIfAny(errors);
        return roles;
    }

    private void auditCreation(String action, UserAccount created, UUID actorId, String actorRole, ClientOrigin origin) {
        ClientOrigin from = origin == null ? ClientOrigin.UNKNOWN : origin;
        String rolesJson = created.roles().stream()
                .map(role -> "\"" + role.name() + "\"")
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .map(joined -> "{\"roles\":[" + joined + "]}")
                .orElse("{\"roles\":[]}");
        audit.write(AuditRecord.builder(action, "user")
                .actor(actorId, actorRole)
                .entity(created.id())
                .after(rolesJson)
                .origin(from.ip(), from.userAgent())
                .build());
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

    /** Evita reflejar texto arbitrario largo o con saltos de línea en la respuesta. */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.length() > 40 ? clean.substring(0, 40) + "…" : clean;
    }
}
