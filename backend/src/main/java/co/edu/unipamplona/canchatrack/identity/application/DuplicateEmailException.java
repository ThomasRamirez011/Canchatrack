package co.edu.unipamplona.canchatrack.identity.application;

/** El correo ya está registrado. Lo lanza el adaptador de persistencia; el caso de uso la traduce a un error de validación. */
public class DuplicateEmailException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateEmailException() {
        super("email already registered");
    }
}
