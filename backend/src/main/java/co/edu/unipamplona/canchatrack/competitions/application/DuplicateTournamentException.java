package co.edu.unipamplona.canchatrack.competitions.application;

/** La temporada ya tiene un torneo con ese nombre. */
public class DuplicateTournamentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateTournamentException() {
        super("duplicate tournament name in season", null, false, false);
    }
}
