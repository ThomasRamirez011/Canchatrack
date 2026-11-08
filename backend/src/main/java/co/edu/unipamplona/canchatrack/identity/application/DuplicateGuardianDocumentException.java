package co.edu.unipamplona.canchatrack.identity.application;

/** El documento de identidad ya está registrado para otro acudiente (índice único). */
public class DuplicateGuardianDocumentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateGuardianDocumentException() {
        super("guardian document already registered");
    }
}
