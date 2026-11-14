package co.edu.unipamplona.canchatrack.reports.domain;

import java.util.Map;

/**
 * Etiquetas en español de los valores internos (códigos en inglés de la base) que salen en los CSV del ERS 7.3 y en la
 * cartera. Los encabezados ya estaban en español (CA-21); con esto los valores también, para que la planilla se lea sin
 * traducir. Un código que no esté en la tabla sale tal cual, sin perder información. La API JSON no cambia: sigue
 * entregando los códigos.
 */
public final class ExportLabels {

    private static final Map<String, String> PLAYER_STATUS = Map.ofEntries(
            Map.entry("SUBMITTED", "Inscripción radicada"),
            Map.entry("COMMITTEE_PENDING", "Pendiente de comité"),
            Map.entry("CATEGORY_ASSIGNED", "Categoría asignada"),
            Map.entry("PENDING_REGISTRATION", "Pendiente de matrícula"),
            Map.entry("ACTIVE", "Activo"),
            Map.entry("IN_EVALUATION", "En evaluación"),
            Map.entry("PROMOTION_APPROVED", "Ascenso aprobado"),
            Map.entry("SUSPENDED", "Suspendido"),
            Map.entry("WITHDRAWN", "Retirado"),
            Map.entry("SEASON_CLOSED", "Temporada cerrada"),
            Map.entry("REJECTED", "Inscripción no aceptada"));
    private static final Map<String, String> EVENT_TYPE = Map.of(
            "TRAINING", "Entrenamiento",
            "EVALUATION", "Evaluación",
            "FRIENDLY_MATCH", "Partido amistoso",
            "TOURNAMENT_MATCH", "Partido de torneo");
    private static final Map<String, String> EVENT_STATUS = Map.of(
            "CONFIRMED", "Confirmado",
            "CANCELLED", "Cancelado",
            "COMPLETED", "Finalizado");
    private static final Map<String, String> ATTENDANCE = Map.of(
            "PRESENT", "Presente",
            "LATE", "Tarde",
            "EXCUSED", "Excusado",
            "ABSENT", "Ausente");
    private static final Map<String, String> CALLUP = Map.of(
            "PROPOSED", "Propuesto",
            "CONFIRMED", "Convocado",
            "NOT_ELIGIBLE", "No elegible",
            "REMOVED", "Retirado");
    private static final Map<String, String> ROUND_STATUS = Map.of(
            "OPEN", "Abierta",
            "CLOSED", "Cerrada");
    private static final Map<String, String> ROUND_RESULT = Map.of(
            "APPROVED", "Ascenso avalado",
            "FOLLOW_UP", "Seguimiento");
    private static final Map<String, String> CONCEPT = Map.of(
            "ENROLLMENT", "Matrícula",
            "MONTHLY", "Mensualidad",
            "OTHER", "Otro");
    private static final Map<String, String> CHARGE_STATUS = Map.of(
            "PENDING", "Pendiente",
            "PARTIAL", "Pago parcial",
            "SETTLED", "Pagado",
            "VOIDED", "Anulado");

    private ExportLabels() {
    }

    public static String playerStatus(String code) {
        return label(PLAYER_STATUS, code);
    }

    public static String eventType(String code) {
        return label(EVENT_TYPE, code);
    }

    public static String eventStatus(String code) {
        return label(EVENT_STATUS, code);
    }

    public static String attendance(String code) {
        return label(ATTENDANCE, code);
    }

    public static String callup(String code) {
        return label(CALLUP, code);
    }

    public static String roundStatus(String code) {
        return label(ROUND_STATUS, code);
    }

    public static String roundResult(String code) {
        return label(ROUND_RESULT, code);
    }

    public static String concept(String code) {
        return label(CONCEPT, code);
    }

    public static String chargeStatus(String code) {
        return label(CHARGE_STATUS, code);
    }

    private static String label(Map<String, String> table, String code) {
        if (code == null) {
            return null;
        }
        return table.getOrDefault(code, code);
    }
}
