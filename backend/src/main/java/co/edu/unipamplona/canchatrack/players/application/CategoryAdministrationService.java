package co.edu.unipamplona.canchatrack.players.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.players.domain.CategoryRules;
import co.edu.unipamplona.canchatrack.players.domain.FieldError;
import co.edu.unipamplona.canchatrack.shared.domain.DomainException;
import co.edu.unipamplona.canchatrack.shared.domain.ErrorCode;

/**
 * Categorías con rango de año de nacimiento y cupo (RN-04, DDS 4.2). Los valores los define el club (DP-01 pendiente),
 * así que todo se administra por API: no hay categorías ni cupos quemados. Dos categorías activas no pueden cubrir el
 * mismo año (la sugerencia sería ambigua); lo garantiza la base con una restricción de exclusión (V14) y este servicio
 * lo anticipa para dar un mensaje claro.
 */
@Service
public class CategoryAdministrationService {

    static final String ACTION_CREATED = "CATEGORY_CREATED";
    static final String ACTION_UPDATED = "CATEGORY_UPDATED";
    private static final String ENTITY = "category";
    private static final int MAX_CHAIN = 100;

    private final CategoryStore categories;
    private final AuditWriter audit;

    public CategoryAdministrationService(CategoryStore categories, AuditWriter audit) {
        this.categories = categories;
        this.audit = audit;
    }

    @Transactional
    public CategoryInfo create(AdminActor actor, CreateCategoryCommand command) {
        List<FieldError> errors = new ArrayList<>(CategoryRules.violations(
                command.name(), command.birthYearMin(), command.birthYearMax(), command.capacity()));
        if (errors.isEmpty()) {
            overlapError(command.birthYearMin(), command.birthYearMax(), null, errors);
        }
        if (command.nextCategoryId() != null && categories.findById(command.nextCategoryId()).isEmpty()) {
            errors.add(new FieldError("nextCategoryId", "La categoría de ascenso no existe."));
        }
        ValidationFailure.throwIfAny(errors);

        CategoryInfo created;
        try {
            created = categories.insert(command.name().trim(), command.birthYearMin(), command.birthYearMax(),
                    command.capacity(), command.nextCategoryId(), actor.id());
        } catch (DuplicateCategoryNameException e) {
            throw ValidationFailure.of("name", "Ya existe una categoría con ese nombre.");
        } catch (OverlappingCategoryRangeException e) {
            throw ValidationFailure.of("birthYearMin", "El rango se solapa con otra categoría activa.");
        }
        audit.write(audit(ACTION_CREATED, actor, created.id()).after(summary(created)).build());
        return created;
    }

    @Transactional(readOnly = true)
    public List<CategoryInfo> list(boolean includeInactive) {
        return categories.findAll(!includeInactive);
    }

    @Transactional(readOnly = true)
    public CategoryInfo get(UUID categoryId) {
        return categories.findById(categoryId).orElseThrow(CategoryAdministrationService::notFound);
    }

    @Transactional
    public CategoryInfo update(AdminActor actor, UUID categoryId, UpdateCategoryCommand command) {
        CategoryInfo current = categories.findByIdForUpdate(categoryId).orElseThrow(CategoryAdministrationService::notFound);

        String name = command.name() == null ? current.name() : command.name().trim();
        Integer min = command.birthYearMin() == null ? current.birthYearMin() : command.birthYearMin();
        Integer max = command.birthYearMax() == null ? current.birthYearMax() : command.birthYearMax();
        Integer capacity = command.capacity() == null ? current.capacity() : command.capacity();
        boolean active = command.active() == null ? current.active() : command.active();
        UUID next = command.clearNextCategory() ? null
                : command.nextCategoryId() == null ? current.nextCategoryId() : command.nextCategoryId();

        List<FieldError> errors = new ArrayList<>();
        boolean nothing = command.name() == null && command.birthYearMin() == null && command.birthYearMax() == null
                && command.capacity() == null && command.nextCategoryId() == null && !command.clearNextCategory()
                && command.active() == null;
        if (nothing) {
            errors.add(new FieldError("body", "Indique al menos un campo por modificar."));
        }
        if (command.clearNextCategory() && command.nextCategoryId() != null) {
            errors.add(new FieldError("nextCategoryId", "No puede indicar una categoría de ascenso y quitarla a la vez."));
        }
        errors.addAll(CategoryRules.violations(name, min, max, capacity));
        if (errors.isEmpty() && capacity < current.occupied()) {
            errors.add(new FieldError("capacity", "El cupo no puede ser menor que los " + current.occupied()
                    + " jugadores ya asignados."));
        }
        if (errors.isEmpty() && active) {
            overlapError(min, max, categoryId, errors);
        }
        if (next != null && !next.equals(current.nextCategoryId())) {
            nextCategoryErrors(categoryId, next, errors);
        }
        ValidationFailure.throwIfAny(errors);

        boolean unchanged = name.equals(current.name()) && min == current.birthYearMin() && max == current.birthYearMax()
                && capacity == current.capacity() && Objects.equals(next, current.nextCategoryId())
                && active == current.active();
        if (unchanged) {
            return current;
        }
        try {
            categories.update(categoryId, name, min, max, capacity, next, active);
        } catch (DuplicateCategoryNameException e) {
            throw ValidationFailure.of("name", "Ya existe una categoría con ese nombre.");
        } catch (OverlappingCategoryRangeException e) {
            throw ValidationFailure.of("birthYearMin", "El rango se solapa con otra categoría activa.");
        }
        CategoryInfo updated = categories.findById(categoryId).orElseThrow();
        audit.write(audit(ACTION_UPDATED, actor, categoryId).before(summary(current)).after(summary(updated)).build());
        return updated;
    }

    private void overlapError(int min, int max, UUID exclude, List<FieldError> errors) {
        categories.findActiveOverlapping(min, max, exclude).ifPresent(other -> errors.add(new FieldError(
                "birthYearMin", "El rango se solapa con la categoría activa «" + other.name() + "» ("
                + other.birthYearMin() + "-" + other.birthYearMax() + ").")));
    }

    /** El destino de ascenso debe existir, ser otra categoría y no cerrar un ciclo (A→B→A impediría ascender). */
    private void nextCategoryErrors(UUID categoryId, UUID next, List<FieldError> errors) {
        if (next.equals(categoryId)) {
            errors.add(new FieldError("nextCategoryId", "Una categoría no puede ascender a sí misma."));
            return;
        }
        UUID cursor = next;
        for (int steps = 0; cursor != null && steps < MAX_CHAIN; steps++) {
            CategoryInfo step = categories.findById(cursor).orElse(null);
            if (step == null) {
                errors.add(new FieldError("nextCategoryId", "La categoría de ascenso no existe."));
                return;
            }
            if (step.nextCategoryId() != null && step.nextCategoryId().equals(categoryId)) {
                errors.add(new FieldError("nextCategoryId", "La cadena de ascensos formaría un ciclo."));
                return;
            }
            cursor = step.nextCategoryId();
        }
    }

    private static AuditRecord.Builder audit(String action, AdminActor actor, UUID categoryId) {
        return AuditRecord.builder(action, ENTITY)
                .actor(actor.id(), AdminActor.ROLE)
                .entity(categoryId)
                .origin(actor.ip(), actor.userAgent());
    }

    private static String summary(CategoryInfo category) {
        return AuditJson.of("name", category.name(), "birthYearMin", category.birthYearMin(),
                "birthYearMax", category.birthYearMax(), "capacity", category.capacity(),
                "nextCategoryId", category.nextCategoryId(), "active", category.active());
    }

    private static DomainException notFound() {
        return new DomainException(ErrorCode.RESOURCE_NOT_FOUND, "La categoría no existe.");
    }
}
