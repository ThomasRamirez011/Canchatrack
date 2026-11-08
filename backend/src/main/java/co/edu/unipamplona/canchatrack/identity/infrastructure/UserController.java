package co.edu.unipamplona.canchatrack.identity.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.identity.application.CreateUserCommand;
import co.edu.unipamplona.canchatrack.identity.application.UserAdministrationService;
import co.edu.unipamplona.canchatrack.identity.domain.RoleCode;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.CreateUserRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.UserResponse;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.V1 + "/users")
public class UserController {

    private final UserAdministrationService administration;

    public UserController(UserAdministrationService administration) {
        this.administration = administration;
    }

    /** Solo el coordinador crea cuentas de personal (DAD 4.1). */
    @PostMapping
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request, HttpServletRequest http) {
        AuthenticatedUser actor = CurrentUser.require();
        CreateUserCommand command = new CreateUserCommand(
                request.email(), request.fullName(), request.password(), request.roles());
        return UserResponse.from(administration.createStaffUser(
                command, actor.id(), RoleCode.COORDINATOR.name(), ClientOrigins.from(http)));
    }
}
