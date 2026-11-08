package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.PlayerQueryService;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.PlayerDetailResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.PlayerPage;
import co.edu.unipamplona.canchatrack.shared.security.AuthenticatedUser;
import co.edu.unipamplona.canchatrack.shared.security.CurrentUser;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

@RestController
@RequestMapping(ApiPaths.V1 + "/players")
public class PlayerController {

    private final PlayerQueryService players;

    public PlayerController(PlayerQueryService players) {
        this.players = players;
    }

    /** Coordinador y comité ven todos; el acudiente, solo los jugadores vinculados; otros roles, 403. */
    @GetMapping
    public PlayerPage list(@RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        AuthenticatedUser user = CurrentUser.require();
        return PlayerPage.from(players.list(user.id(), user.roles(), status, page, size));
    }

    @GetMapping("/{playerId}")
    public PlayerDetailResponse get(@PathVariable UUID playerId) {
        AuthenticatedUser user = CurrentUser.require();
        return PlayerDetailResponse.from(players.get(user.id(), user.roles(), playerId));
    }
}
