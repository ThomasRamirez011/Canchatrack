package co.edu.unipamplona.canchatrack.scheduling.application;

public class DuplicateVenueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateVenueException() {
        super("venue name already exists", null, false, false);
    }
}
