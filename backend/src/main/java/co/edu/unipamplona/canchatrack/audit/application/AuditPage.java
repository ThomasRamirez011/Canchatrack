package co.edu.unipamplona.canchatrack.audit.application;

import java.util.List;

/** Una página de eventos, del más reciente al más antiguo; {@code nextCursor} es nulo en la última página. */
public record AuditPage(List<AuditEntry> items, String nextCursor) {
}
