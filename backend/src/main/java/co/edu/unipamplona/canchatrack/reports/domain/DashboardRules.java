package co.edu.unipamplona.canchatrack.reports.domain;

import java.math.BigDecimal;

/** Cálculos puros del tablero del club. */
public final class DashboardRules {

    private DashboardRules() {
    }

    /** Porcentaje entero redondeado (mitad hacia arriba); 0 si no hay base y nunca más de 100. */
    public static int percent(long part, long whole) {
        if (whole <= 0 || part <= 0) {
            return 0;
        }
        long rounded = (part * 100 + whole / 2) / whole;
        return (int) Math.min(100, rounded);
    }

    /** Porcentaje entero de un valor monetario sobre otro, con las mismas reglas que {@link #percent(long, long)}. */
    public static int percent(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() <= 0 || part.signum() <= 0) {
            return 0;
        }
        long rounded = part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, java.math.RoundingMode.HALF_UP).longValueExact();
        return (int) Math.min(100, rounded);
    }

    /** Un jugador está habilitado para competir si está activo y no debe nada ya vencido. */
    public static boolean enabled(String status, boolean hasOverdueBalance) {
        return "ACTIVE".equals(status) && !hasOverdueBalance;
    }
}
