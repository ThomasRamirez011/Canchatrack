package co.edu.unipamplona.canchatrack.payments.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;

/**
 * Cadencia de los avisos de mora (RF-041): un cargo está en mora cuando su vencimiento quedó atrás por más de los días de
 * gracia (la misma regla que usa la elegibilidad de convocatoria). El primer aviso sale el primer día de mora y luego uno
 * cada {@code intervalDays} mientras haya saldo, hasta {@code maxNotices} avisos por cargo.
 *
 * <p>Función pura: el «tramo» es el número de aviso que corresponde hoy (0 = el primero). Si el proceso no corre un día, el
 * tramo se alcanza igual al siguiente (se evalúa por banda, no por día exacto); como el tramo es el ámbito de
 * deduplicación, correr varias veces el mismo día no repite el correo.
 */
public final class ArrearsPolicy {

    public static final int DEFAULT_INTERVAL_DAYS = 7;
    public static final int DEFAULT_MAX_NOTICES = 8;

    private ArrearsPolicy() {
    }

    /** @return el tramo de aviso de hoy, o vacío si el cargo aún no está en mora o ya agotó sus avisos */
    public static OptionalInt stage(LocalDate dueDate, LocalDate today, int graceDays, int intervalDays, int maxNotices) {
        if (dueDate == null || today == null || intervalDays < 1 || maxNotices < 1) {
            return OptionalInt.empty();
        }
        long overdue = ChronoUnit.DAYS.between(dueDate, today);
        long pastGrace = overdue - Math.max(0, graceDays);
        if (pastGrace < 1) {
            return OptionalInt.empty();
        }
        long stage = (pastGrace - 1) / intervalDays;
        return stage >= maxNotices ? OptionalInt.empty() : OptionalInt.of((int) stage);
    }

    /** Vencimiento más reciente que ya está en mora hoy. */
    public static LocalDate newestDue(LocalDate today, int graceDays) {
        return today.minusDays(Math.max(0, graceDays) + 1L);
    }

    /** Vencimiento más antiguo que todavía tiene un aviso por enviar. */
    public static LocalDate oldestDue(LocalDate today, int graceDays, int intervalDays, int maxNotices) {
        return today.minusDays(Math.max(0, graceDays) + (long) intervalDays * maxNotices);
    }
}
