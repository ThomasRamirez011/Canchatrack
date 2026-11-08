package co.edu.unipamplona.canchatrack.players.application;

/** Ya existe una decisión del mismo jugador en un acta con ese número ({@code ux_committee_minutes}). */
public class DuplicateMinuteException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateMinuteException() {
        super("duplicate minute for player", null, false, false);
    }
}
