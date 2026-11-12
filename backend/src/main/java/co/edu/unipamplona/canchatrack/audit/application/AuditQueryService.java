package co.edu.unipamplona.canchatrack.audit.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import co.edu.unipamplona.canchatrack.audit.api.AuditRecord;
import co.edu.unipamplona.canchatrack.audit.api.AuditWriter;
import co.edu.unipamplona.canchatrack.audit.domain.AuditQueryRules;
import co.edu.unipamplona.canchatrack.audit.domain.FieldError;

/**
 * Consulta de auditoría (CU-14, RF-051): historial de decisiones, cambios de categoría, conceptos, correcciones y cambios
 * de estado, filtrable por acción, entidad, actor, resultado y fechas.
 *
 * <p>La propia consulta queda auditada ({@code AUDIT_QUERIED}, DDS 12.2 "consultas administrativas de alta sensibilidad")
 * con los filtros usados y sin el contenido devuelto. Se registra una vez por búsqueda, en la primera página: pedir las
 * siguientes con el cursor continúa la misma consulta.
 */
@Service
public class AuditQueryService {

    public static final String QUERIED = "AUDIT_QUERIED";

    private final AuditQueryStore store;
    private final AuditWriter audit;

    public AuditQueryService(AuditQueryStore store, AuditWriter audit) {
        this.store = store;
        this.audit = audit;
    }

    @Transactional
    public AuditPage search(AuditActor actor, AuditSearch search) {
        String action = blankToNull(search.action());
        String entityType = blankToNull(search.entityType());
        String outcome = blankToNull(search.outcome());
        outcome = outcome == null ? null : outcome.toUpperCase(Locale.ROOT);

        List<FieldError> errors = new ArrayList<>(AuditQueryRules.violations(action, entityType, outcome, search.from(),
                search.to(), search.limit()));
        AuditCursor after = null;
        String cursor = blankToNull(search.cursor());
        if (cursor != null) {
            try {
                after = AuditCursor.parse(cursor);
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("cursor", "El cursor no es válido."));
            }
        }
        ValidationFailure.throwIfAny(errors);

        int limit = search.limit() == null ? AuditQueryRules.DEFAULT_LIMIT : search.limit();
        AuditFilter filter = new AuditFilter(action, entityType, search.entityId(), search.actorId(), outcome, search.from(),
                search.to());
        List<AuditEntry> rows = store.search(filter, after, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AuditEntry> items = hasMore ? List.copyOf(rows.subList(0, limit)) : List.copyOf(rows);
        String next = null;
        if (hasMore) {
            AuditEntry last = items.get(items.size() - 1);
            next = new AuditCursor(last.occurredAt(), last.id()).encode();
        }

        if (after == null) {
            audit.write(AuditRecord.builder(QUERIED, "audit")
                    .actor(actor.id(), AuditActor.ROLE)
                    .after(Json.of("action", action, "entityType", entityType, "entityId", search.entityId(),
                            "actorId", search.actorId(), "outcome", outcome, "from", search.from(), "to", search.to()))
                    .origin(actor.ip(), actor.userAgent())
                    .build());
        }
        return new AuditPage(items, next);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
