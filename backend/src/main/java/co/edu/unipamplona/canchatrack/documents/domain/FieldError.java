package co.edu.unipamplona.canchatrack.documents.domain;

/** Un error de validación asociado a un campo de la solicitud. */
public record FieldError(String field, String message) {
}
