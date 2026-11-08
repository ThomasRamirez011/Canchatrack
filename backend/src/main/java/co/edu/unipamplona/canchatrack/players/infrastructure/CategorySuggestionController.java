package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.CategoryQueryService;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CategorySuggestionResponse;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;

@RestController
@RequestMapping(ApiPaths.V1 + "/players/{playerId}/category-suggestion")
public class CategorySuggestionController {

    private final CategoryQueryService queries;

    public CategorySuggestionController(CategoryQueryService queries) {
        this.queries = queries;
    }

    /** RF-046: categoría sugerida por año de nacimiento, con su cupo. La decisión final es del Comité Técnico. */
    @GetMapping
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public CategorySuggestionResponse suggest(@PathVariable UUID playerId) {
        return CategorySuggestionResponse.from(queries.suggestFor(playerId));
    }
}
