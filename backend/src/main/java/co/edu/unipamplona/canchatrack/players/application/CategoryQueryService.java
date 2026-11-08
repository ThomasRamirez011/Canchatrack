package co.edu.unipamplona.canchatrack.players.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/** RF-046: sugiere la categoría según el año de nacimiento y muestra su cupo disponible. La decisión es del comité. */
@Service
@Transactional(readOnly = true)
public class CategoryQueryService {

    private final CategoryStore categories;
    private final PlayerStore players;

    public CategoryQueryService(CategoryStore categories, PlayerStore players) {
        this.categories = categories;
        this.players = players;
    }

    public CategorySuggestion suggestFor(UUID playerId) {
        PlayerDetail player = players.findDetail(playerId)
                .orElseThrow(() -> new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "El jugador no existe."));
        int birthYear = player.birthDate().getYear();
        return new CategorySuggestion(player.id(), birthYear, categories.findActiveCovering(birthYear).orElse(null));
    }
}
