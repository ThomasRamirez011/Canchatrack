package co.edu.unipamplona.canchatrack.players.application;

/** Ya existe una categoría con ese nombre. */
public class DuplicateCategoryNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateCategoryNameException() {
        super("duplicate category name", null, false, false);
    }
}
