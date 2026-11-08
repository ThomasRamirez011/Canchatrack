package co.edu.unipamplona.canchatrack.shared.web;

/** Rutas base de la API REST (DDS secc. 8.1). Todo controlador se monta bajo {@link #V1}. */
public final class ApiPaths {

    public static final String V1 = "/api/v1";

    /** Cabecera de idempotencia obligatoria en cargas, asistencia, pagos, programación e informes. */
    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private ApiPaths() {
    }
}
