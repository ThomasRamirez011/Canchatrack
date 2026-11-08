package co.edu.unipamplona.canchatrack.players.application;

/** Ya existe una temporada con ese nombre. */
public class DuplicateSeasonNameException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateSeasonNameException() {
        super("duplicate season name", null, false, false);
    }
}
