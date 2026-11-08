package co.edu.unipamplona.canchatrack.players.application;

/** Ya existe un jugador con ese documento (carrera entre la consulta y el INSERT; el índice único decide). */
public class DuplicatePlayerDocumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicatePlayerDocumentException() {
        super("player document already registered");
    }
}
