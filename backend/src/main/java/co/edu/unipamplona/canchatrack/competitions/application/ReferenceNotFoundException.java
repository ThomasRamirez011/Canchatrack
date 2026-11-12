package co.edu.unipamplona.canchatrack.competitions.application;

/** Una clave foránea apunta a algo que no existe (temporada, categoría, escenario o jugador). */
public class ReferenceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ReferenceNotFoundException() {
        super("referenced row does not exist", null, false, false);
    }
}
