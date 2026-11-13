package co.edu.unipamplona.canchatrack.shared.domain;

/**
 * Catálogo de códigos de error estables de la API (DDS secc. 13). El nombre del enum es el contrato público
 * ({@code code} en la respuesta {@code application/problem+json}); no se renombra sin control de cambios.
 *
 * <p>Los códigos marcados como "adición" no figuran en el DDS 13 y deben incorporarse al documento.
 */
public enum ErrorCode {

    // --- DDS secc. 13 ---
    AUTH_FORBIDDEN(403, "Acceso denegado", "El usuario no tiene permiso efectivo."),
    PLAYER_STATE_CONFLICT(409, "Conflicto de estado", "El estado cambió o no permite la acción."),
    ENROLLMENT_CLOSED(409, "Inscripción cerrada", "No existe convocatoria vigente."),
    CATEGORY_FULL(409, "Categoría sin cupo", "La categoría no tiene cupo."),
    PANEL_INVALID(422, "Panel inválido", "No hay tres evaluadores válidos o el actor no está asignado."),
    NEGATIVE_REASON_REQUIRED(422, "Justificación requerida", "Falta justificación."),
    ROUND_CLOSED(409, "Ronda cerrada", "La ronda ya no admite decisiones."),
    CALLUP_NOT_ELIGIBLE(409, "Jugador no elegible", "El jugador no cumple los requisitos de convocatoria."),
    SCHEDULE_CONFLICT(409, "Conflicto de agenda", "Existe superposición de agenda."),
    ATTENDANCE_SESSION_CLOSED(409, "Sesión cerrada", "La sesión está cerrada; la corrección exige flujo auditado."),
    FILE_REJECTED(422, "Archivo rechazado", "Formato, tamaño o análisis de archivo inválido."),
    PAYMENT_IMMUTABLE(409, "Pago inmutable", "El pago no se edita; use anulación o ajuste."),
    NOTIFICATION_DELIVERY_FAILED(503, "Fallo de notificación", "Fallo temporal del servicio de correo."),
    REPORT_GENERATION_FAILED(500, "Fallo al generar el informe", "No fue posible producir el informe; puede reintentarse."),

    // --- Adiciones (no están en el DDS 13) ---
    AUTH_UNAUTHENTICATED(401, "No autenticado", "Se requieren credenciales válidas."),
    RESOURCE_NOT_FOUND(404, "Recurso no encontrado", "El recurso solicitado no existe."),
    VALIDATION_FAILED(422, "Validación fallida", "Los datos enviados no cumplen las reglas de validación."),
    RESOURCE_STATE_CONFLICT(409, "Conflicto de estado del recurso", "El estado del recurso no permite la acción."),
    CONCURRENT_MODIFICATION(409, "Modificación concurrente", "El recurso cambió desde que fue leído (versión obsoleta)."),
    IDEMPOTENCY_KEY_REQUIRED(400, "Idempotency-Key requerida", "La operación exige la cabecera Idempotency-Key."),
    IDEMPOTENCY_KEY_REUSED(422, "Idempotency-Key reutilizada", "La clave ya se usó con una solicitud distinta."),
    DOCUMENT_UPLOAD_NOT_ALLOWED(409, "Carga de documento no habilitada",
            "No hay una ventana habilitada ni un vencimiento próximo que permitan cargar este documento."),
    EMAIL_NOT_VERIFIED(403, "Correo sin confirmar",
            "Debe confirmar su correo con el enlace que se le envió antes de iniciar sesión."),
    RATE_LIMITED(429, "Demasiadas solicitudes", "Se superó el límite de solicitudes; intente de nuevo más tarde."),
    INTERNAL_ERROR(500, "Error interno", "Ocurrió un error interno.");

    private final int httpStatus;
    private final String title;
    private final String defaultDetail;

    ErrorCode(int httpStatus, String title, String defaultDetail) {
        this.httpStatus = httpStatus;
        this.title = title;
        this.defaultDetail = defaultDetail;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }

    public String defaultDetail() {
        return defaultDetail;
    }
}
