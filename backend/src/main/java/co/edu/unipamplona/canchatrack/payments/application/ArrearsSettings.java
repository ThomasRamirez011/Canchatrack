package co.edu.unipamplona.canchatrack.payments.application;

import java.time.ZoneId;

/**
 * Parámetros de los avisos de mora: los días de gracia son los mismos de la elegibilidad de convocatoria; con
 * {@code maxNotices = 0} no se envía ningún aviso. Un valor inválido impide el arranque.
 */
public record ArrearsSettings(ZoneId zone, int graceDays, int intervalDays, int maxNotices, String appUrl) {

    public ArrearsSettings {
        if (zone == null) {
            throw new IllegalArgumentException("canchatrack.zone-id es obligatorio.");
        }
        if (graceDays < 0) {
            throw new IllegalArgumentException("canchatrack.payments.arrears-grace-days no puede ser negativo.");
        }
        if (intervalDays < 1 || intervalDays > 60) {
            throw new IllegalArgumentException("canchatrack.payments.arrears-notices.interval-days debe estar entre 1 y 60.");
        }
        if (maxNotices < 0 || maxNotices > 52) {
            throw new IllegalArgumentException("canchatrack.payments.arrears-notices.max-notices debe estar entre 0 y 52.");
        }
        appUrl = appUrl == null ? "" : appUrl.trim();
    }
}
