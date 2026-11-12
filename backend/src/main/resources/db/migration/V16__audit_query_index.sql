-- V16: índice para la consulta de auditoría (CU-14, RF-051).
-- La consulta sin filtro de acción, entidad ni actor recorre la bitácora de la más reciente a la más antigua con paginación
-- por cursor (occurred_at, id); sin este índice se ordenaría toda la tabla en cada página.
CREATE INDEX ix_audit_events_occurred ON audit_events (occurred_at DESC, id DESC);
