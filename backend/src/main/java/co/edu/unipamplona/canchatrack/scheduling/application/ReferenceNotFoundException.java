package co.edu.unipamplona.canchatrack.scheduling.application;

/** Una clave foránea apunta a algo que no existe (categoría o escenario). */
public class ReferenceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReferenceNotFoundException() {
        super("referenced row does not exist", null, false, false);
    }
}
