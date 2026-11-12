package co.edu.unipamplona.canchatrack.competitions.domain;

/** Error de validación atribuible a un campo de la solicitud. */
public record FieldError(String field, String message) {
}
