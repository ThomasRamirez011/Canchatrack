package co.edu.unipamplona.canchatrack.players.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import co.edu.unipamplona.canchatrack.players.application.CategoryAdministrationService;
import co.edu.unipamplona.canchatrack.players.application.CreateCategoryCommand;
import co.edu.unipamplona.canchatrack.players.application.UpdateCategoryCommand;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CategoryResponse;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.CreateCategoryRequest;
import co.edu.unipamplona.canchatrack.players.infrastructure.PlayersDtos.UpdateCategoryRequest;
import co.edu.unipamplona.canchatrack.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** Categorías: las define el club (DP-01), se administran por API. Escribe el coordinador; leen coordinador y comité. */
@RestController
@RequestMapping(ApiPaths.V1 + "/categories")
public class CategoryController {

    private final CategoryAdministrationService categories;

    public CategoryController(CategoryAdministrationService categories) {
        this.categories = categories;
    }

    @PostMapping
    @PreAuthorize("hasRole('COORDINATOR')")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request, HttpServletRequest http) {
        return CategoryResponse.from(categories.create(RequestOrigins.adminActor(http),
                new CreateCategoryCommand(request.name(), request.birthYearMin(), request.birthYearMax(),
                        request.capacity(), request.nextCategoryId())));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public List<CategoryResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return categories.list(includeInactive).stream().map(CategoryResponse::from).toList();
    }

    @GetMapping("/{categoryId}")
    @PreAuthorize("hasAnyRole('COORDINATOR', 'COMMITTEE')")
    public CategoryResponse get(@PathVariable UUID categoryId) {
        return CategoryResponse.from(categories.get(categoryId));
    }

    @PatchMapping("/{categoryId}")
    @PreAuthorize("hasRole('COORDINATOR')")
    public CategoryResponse update(@PathVariable UUID categoryId, @Valid @RequestBody UpdateCategoryRequest request,
                                   HttpServletRequest http) {
        return CategoryResponse.from(categories.update(RequestOrigins.adminActor(http), categoryId,
                new UpdateCategoryCommand(request.name(), request.birthYearMin(), request.birthYearMax(),
                        request.capacity(), request.nextCategoryId(), Boolean.TRUE.equals(request.clearNextCategory()),
                        request.active())));
    }
}
