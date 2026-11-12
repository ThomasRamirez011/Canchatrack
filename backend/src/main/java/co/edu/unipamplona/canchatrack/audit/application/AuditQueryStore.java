package co.edu.unipamplona.canchatrack.audit.application;

import java.util.List;

/** Puerto de lectura de la bitácora. Solo consulta: la escritura sigue siendo exclusiva de {@code AuditWriter}. */
public interface AuditQueryStore {

    /**
     * Eventos que cumplen el filtro, del más reciente al más antiguo, estrictamente posteriores en el orden a {@code after}
     * (si no es nulo). Devuelve a lo sumo {@code limit} filas.
     */
    List<AuditEntry> search(AuditFilter filter, AuditCursor after, int limit);
}
