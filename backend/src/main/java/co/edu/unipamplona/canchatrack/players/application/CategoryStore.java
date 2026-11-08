package co.edu.unipamplona.canchatrack.players.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Puerto de salida: categorías. La escritura exige una transacción activa. */
public interface CategoryStore {

    /**
     * @throws DuplicateCategoryNameException     si el nombre ya existe
     * @throws OverlappingCategoryRangeException  si otra categoría activa cubre alguno de esos años
     */
    CategoryInfo insert(String name, int birthYearMin, int birthYearMax, int capacity, UUID nextCategoryId, UUID createdBy);

    /** Por año de nacimiento descendente (la más joven primero). */
    List<CategoryInfo> findAll(boolean activeOnly);

    Optional<CategoryInfo> findById(UUID categoryId);

    /** Bloquea la fila hasta el fin de la transacción. */
    Optional<CategoryInfo> findByIdForUpdate(UUID categoryId);

    /** Categoría activa que cubre ese año de nacimiento. */
    Optional<CategoryInfo> findActiveCovering(int birthYear);

    /** Otra categoría activa cuyo rango se cruza con el dado (para dar un mensaje claro antes de tocar la base). */
    Optional<CategoryInfo> findActiveOverlapping(int birthYearMin, int birthYearMax, UUID excludeCategoryId);

    /** @throws DuplicateCategoryNameException     como {@link #insert}
     * @throws OverlappingCategoryRangeException  como {@link #insert} */
    void update(UUID categoryId, String name, int birthYearMin, int birthYearMax, int capacity, UUID nextCategoryId,
                boolean active);
}
