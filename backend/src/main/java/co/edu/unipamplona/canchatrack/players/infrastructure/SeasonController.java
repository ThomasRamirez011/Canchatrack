package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.CreateSeasonCommand;
import co.edu.unipamplona.canchatrack.players.application.SeasonAdministrationService;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CreateSeasonRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.SeasonResponse;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Temporadas: solo el coordinador (DAD CU-01). */
@RestController
@RequestMapping(ApiPaths.V1 + "/seasons")
@PreAuthorize("hasRole('COORDINATOR')")
public class SeasonController {

    private final SeasonAdministrationService seasons;

    public SeasonController(SeasonAdministrationService seasons) {
        this.seasons = seasons;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SeasonResponse create(@Valid @RequestBody CreateSeasonRequest request, HttpServletRequest http) {
        return SeasonResponse.from(seasons.create(RequestOrigins.adminActor(http),
                new CreateSeasonCommand(request.name(), request.startAt(), request.endAt())));
    }

    @GetMapping
    public List<SeasonResponse> list() {
        return seasons.list().stream().map(SeasonResponse::from).toList();
    }

    @PostMapping("/{seasonId}/activate")
    public SeasonResponse activate(@PathVariable UUID seasonId, HttpServletRequest http) {
        return SeasonResponse.from(seasons.activate(RequestOrigins.adminActor(http), seasonId));
    }
}
