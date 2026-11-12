-- V15: detalle de torneos y goles por jugador (RF-031, RF-036; módulo competitions).
-- Aditivo: todas las columnas son opcionales, no afecta datos existentes.

-- RF-031: el torneo declara su categoría, sede y reglamento. Todos opcionales: un torneo puede abarcar varias categorías.
ALTER TABLE tournaments
    ADD COLUMN category_id uuid REFERENCES categories (id),
    ADD COLUMN venue_id    uuid REFERENCES venues (id),
    ADD COLUMN rules       text;

-- RF-036: goles por jugador (el marcador del equipo ya está en matches.score_for).
ALTER TABLE callup_entries
    ADD COLUMN goals integer,
    ADD CONSTRAINT ck_callup_entries_goals CHECK (goals IS NULL OR goals >= 0);

-- Apoya el historial del jugador (RF-037) y la consulta de convocados por partido.
CREATE INDEX ix_callup_entries_match_status ON callup_entries (match_id, status);
