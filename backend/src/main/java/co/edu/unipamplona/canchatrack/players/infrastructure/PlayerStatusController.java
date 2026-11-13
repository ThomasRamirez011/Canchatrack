package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.PlayerStatusService;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.PlayerStatusRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.PlayerStatusResponse;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;

/** Deshabilitar y rehabilitar jugadores (solo coordinación). */
@RestController
@RequestMapping(ApiPaths.V1 + "/players/{playerId}")
public class PlayerStatusController {

    private final PlayerStatusService statuses;

    public PlayerStatusController(PlayerStatusService statuses) {
        this.statuses = statuses;
    }

    /** T-13: ACTIVE → SUSPENDED con motivo obligatorio. */
    @PostMapping("/suspension")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PlayerStatusResponse suspend(@PathVariable UUID playerId, @RequestBody PlayerStatusRequest request,
                                        HttpServletRequest http) {
        return PlayerStatusResponse.from(statuses.suspend(RequestOrigins.adminActor(http), playerId, request.reason()));
    }

    /** T-14: SUSPENDED → ACTIVE; el motivo es opcional. */
    @PostMapping("/reinstatement")
    @PreAuthorize("hasRole('COORDINATOR')")
    public PlayerStatusResponse reinstate(@PathVariable UUID playerId, @RequestBody(required = false) PlayerStatusRequest request,
                                          HttpServletRequest http) {
        return PlayerStatusResponse.from(statuses.reinstate(RequestOrigins.adminActor(http), playerId,
                request == null ? null : request.reason()));
    }
}
