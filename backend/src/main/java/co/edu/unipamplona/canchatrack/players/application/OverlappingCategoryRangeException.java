package co.edu.unipamplona.canchatrack.players.application;

/** El rango de nacimiento se solapa con otra categoría activa (restricción de exclusión de la base, última defensa). */
public class OverlappingCategoryRangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OverlappingCategoryRangeException() {
        super("overlapping category range", null, false, false);
    }
}
