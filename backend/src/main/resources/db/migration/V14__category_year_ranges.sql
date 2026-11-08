-- V14: dos categorías activas no pueden cubrir el mismo año de nacimiento (RN-04, DDS 4.2 CategoryAssignmentPolicy).
-- La categoría se sugiere por año de nacimiento: con rangos solapados la sugerencia sería ambigua. Una categoría
-- inactiva conserva su rango (historial) sin bloquear a las activas. int4range es nativo de GiST: no requiere extensiones.
ALTER TABLE categories
    ADD CONSTRAINT ex_categories_active_birth_years
        EXCLUDE USING gist (int4range(birth_year_min::int, birth_year_max::int, '[]') WITH &&) WHERE (active);
