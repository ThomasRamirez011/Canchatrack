package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.RegistrationService;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.MonthlyBillingRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.MonthlyBillingResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.RegistrationResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.StartRegistrationRequest;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Matrícula, activación del jugador y cobro mensual (DDS UC-04). */
@RestController
@RequestMapping(ApiPaths.V1)
public class RegistrationController {

    private final RegistrationService registration;

    public RegistrationController(RegistrationService registration) {
        this.registration = registration;
    }

    /** CATEGORY_ASSIGNED → PENDING_REGISTRATION: genera el cargo de matrícula. */
    @PostMapping("/players/{playerId}/registration")
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationResponse start(@PathVariable UUID playerId, @RequestBody StartRegistrationRequest request,
                                      HttpServletRequest http) {
        return RegistrationResponse.from(registration.startRegistration(RequestOrigins.adminActor(http), playerId,
                request.feeId(), request.dueDate()));
    }

    /** PENDING_REGISTRATION → ACTIVE: exige la matrícula saldada. */
    @PostMapping("/players/{playerId}/activation")
    @PreAuthorize("hasRole('COORDINATOR')")
    public RegistrationResponse activate(@PathVariable UUID playerId, HttpServletRequest http) {
        return RegistrationResponse.from(registration.activate(RequestOrigins.adminActor(http), playerId));
    }

    /** Un cargo mensual por cada jugador activo; idempotente por jugador, tarifa y periodo. */
    @PostMapping("/billing/monthly")
    @PreAuthorize("hasRole('COORDINATOR')")
    public MonthlyBillingResponse monthly(@RequestBody MonthlyBillingRequest request, HttpServletRequest http) {
        return MonthlyBillingResponse.from(registration.generateMonthly(RequestOrigins.adminActor(http), request.feeId(),
                request.period(), request.dueDate()));
    }
}
