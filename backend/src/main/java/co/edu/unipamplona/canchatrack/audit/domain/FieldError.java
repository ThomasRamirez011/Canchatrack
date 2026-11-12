package co.edu.unipamplona.canchatrack.audit.domain;

/** Error de validación atribuible a un parámetro de la solicitud. */
public record FieldError(String field, String message) {
}
