package co.edu.unipamplona.canchatrack.players.application;

/** El jugador ya está inscrito en esa convocatoria (índice único {@code (player_id, call_id)}). */
public class DuplicateEnrollmentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateEnrollmentException() {
        super("player already enrolled in the call");
    }
}
