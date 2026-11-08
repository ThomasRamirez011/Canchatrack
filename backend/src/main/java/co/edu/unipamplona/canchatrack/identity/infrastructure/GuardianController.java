package co.edu.unipamplona.canchatrack.identity.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.identity.application.GuardianRegistrationService;
import co.edu.unipamplona.canchatrack.identity.application.RegisterGuardianCommand;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.RegisterGuardianRequest;
import co.edu.unipamplona.canchatrack.identity.infrastructure.AuthDtos.UserResponse;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping(ApiPaths.V1 + "/guardians")
public class GuardianController {

    private final GuardianRegistrationService registration;

    public GuardianController(GuardianRegistrationService registration) {
        this.registration = registration;
    }

    /** Autorregistro público de acudientes: da la cuenta con la que luego se radica la inscripción. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterGuardianRequest request, HttpServletRequest http) {
        RegisterGuardianCommand command = new RegisterGuardianCommand(request.email(), request.fullName(),
                request.password(), request.documentType(), request.documentNumber(), request.phone());
        return UserResponse.from(registration.register(command, ClientOrigins.from(http)));
    }
}
