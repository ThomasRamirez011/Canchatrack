package co.edu.unipamplona.canchatrack.documents.application;

/** Ya existe un tipo de documento con ese código. */
public class DuplicateDocumentTypeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateDocumentTypeException(String code) {
        super("Ya existe un tipo de documento con el código " + code);
    }
}
