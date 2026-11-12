package co.edu.unipamplona.canchatrack.reports.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/** Cálculos de los reportes (CU-10 a CU-12). Puros y sin estado. */
public final class ReportRules {

    /** Tope de filas del reporte de cartera; si hay más se marca {@code truncated} y los totales siguen siendo completos. */
    public static final int MAX_RECEIVABLE_LINES = 5000;

    private ReportRules() {
    }

    /** Días transcurridos desde el vencimiento hasta {@code asOf}; cero si aún no vence. */
    public static int daysOverdue(LocalDate dueDate, LocalDate asOf) {
        long days = ChronoUnit.DAYS.between(dueDate, asOf);
        return days <= 0 ? 0 : (int) Math.min(days, Integer.MAX_VALUE);
    }

    /**
     * Porcentaje de asistencia, con la misma fórmula del módulo de asistencia: (presentes + tardes) / (registros − excusas
     * aceptadas), un decimal. {@code null} si no hay nada que contar.
     */
    public static BigDecimal attendancePercentage(int attended, int records, int acceptedExcuses) {
        int counted = records - acceptedExcuses;
        if (counted <= 0) {
            return null;
        }
        return BigDecimal.valueOf(attended * 100L).divide(BigDecimal.valueOf(counted), 1, RoundingMode.HALF_UP);
    }

    // ---------- informe de temporada por jugador (CU-10) y cierre (CU-11) ----------

    /** Límite del motivo de regeneración y de reapertura. */
    public static final int MAX_REASON = 500;
    /** Límite del detalle de una reapertura. */
    public static final int MAX_DETAIL = 2000;
    /** Versión de la plantilla del PDF; cambia cuando el club entregue la definitiva (P-09). */
    public static final String TEMPLATE_VERSION = "plantilla-1";

    /** Sin 0/O ni 1/I para que el código se pueda dictar o copiar sin confusiones. */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_GROUPS = 3;
    private static final int CODE_GROUP_SIZE = 4;

    /** Estados con trayectoria deportiva suficiente para emitir un informe (los previos a la activación no la tienen). */
    private static final Set<String> REPORTABLE_PLAYER_STATES = Set.of(
            "ACTIVE", "IN_EVALUATION", "PROMOTION_APPROVED", "SUSPENDED", "WITHDRAWN", "SEASON_CLOSED");

    /** Consecutivo visible del informe: {@code IT-000012}. */
    public static String reportNumber(long consecutive) {
        return "IT-" + String.format(Locale.ROOT, "%06d", consecutive);
    }

    /**
     * Código de verificación {@code XXXX-XXXX-XXXX} con 60 bits de entropía.
     *
     * @param nextBelow fuente de azar: devuelve un entero en {@code [0, bound)} (en producción, {@code SecureRandom})
     */
    public static String verificationCode(IntUnaryOperator nextBelow) {
        StringBuilder code = new StringBuilder();
        for (int group = 0; group < CODE_GROUPS; group++) {
            if (group > 0) {
                code.append('-');
            }
            for (int i = 0; i < CODE_GROUP_SIZE; i++) {
                code.append(CODE_ALPHABET.charAt(nextBelow.applyAsInt(CODE_ALPHABET.length())));
            }
        }
        return code.toString();
    }

    /**
     * Forma canónica de un código escrito a mano (minúsculas, espacios o guiones de menos o de más), o {@code null} si
     * no puede ser un código de verificación.
     */
    public static String normalizeVerificationCode(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (compact.length() != CODE_GROUPS * CODE_GROUP_SIZE) {
            return null;
        }
        StringBuilder canonical = new StringBuilder();
        for (int i = 0; i < compact.length(); i++) {
            char c = compact.charAt(i);
            if (CODE_ALPHABET.indexOf(c) < 0) {
                return null;
            }
            if (i > 0 && i % CODE_GROUP_SIZE == 0) {
                canonical.append('-');
            }
            canonical.append(c);
        }
        return canonical.toString();
    }

    public static boolean isReportablePlayerState(String status) {
        return REPORTABLE_PLAYER_STATES.contains(status);
    }

    /** Fin del periodo cubierto: el de la temporada, o ahora si todavía no termina (informe parcial). */
    public static Instant periodEnd(Instant seasonEnd, Instant now) {
        return now.isBefore(seasonEnd) ? now : seasonEnd;
    }

    /** Confirmación reforzada de la reapertura: quien reabre debe escribir el código del jugador (sin importar mayúsculas). */
    public static boolean confirmationMatches(String playerCode, String typed) {
        return playerCode != null && typed != null && playerCode.trim().equalsIgnoreCase(typed.trim());
    }

    /** Texto obligatorio, sin contar espacios, con un máximo de caracteres. */
    public static String requiredTextProblem(String value, int max) {
        if (value == null || value.isBlank()) {
            return "Es obligatorio.";
        }
        return value.strip().length() > max ? "No puede superar " + max + " caracteres." : null;
    }
}
