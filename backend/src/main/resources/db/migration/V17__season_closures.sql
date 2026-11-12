-- V17: cierre y reapertura del expediente por temporada (CU-11, RN-18, transiciones T-11 y T-12; módulo reports).
-- El informe versionado (season_reports) ya existe desde V11; aquí se registra qué informe respaldó cada cierre y, si
-- el coordinador reabre, quién, cuándo y por qué. Nunca se borra: reabrir marca la fila, no la elimina (RN-18).

-- Permite que el cierre referencie un informe y garantice en la propia base que ese informe es del mismo jugador y la
-- misma temporada (clave compuesta). No modifica filas existentes.
ALTER TABLE season_reports
    ADD CONSTRAINT ux_season_reports_identity UNIQUE (id, player_id, season_id);

CREATE TABLE season_closures
(
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id     uuid        NOT NULL REFERENCES players (id),
    season_id     uuid        NOT NULL REFERENCES seasons (id),
    report_id     uuid        NOT NULL,
    closed_by     uuid        NOT NULL REFERENCES users (id),
    closed_at     timestamptz NOT NULL DEFAULT now(),
    reopened_by   uuid REFERENCES users (id),
    reopened_at   timestamptz,
    reopen_reason text,
    reopen_detail text,
    CONSTRAINT fk_season_closures_report FOREIGN KEY (report_id, player_id, season_id)
        REFERENCES season_reports (id, player_id, season_id),
    -- La reapertura es todo o nada y exige motivo y detalle no vacíos (RN-18).
    CONSTRAINT ck_season_closures_reopen CHECK (
        (reopened_at IS NULL AND reopened_by IS NULL AND reopen_reason IS NULL AND reopen_detail IS NULL) OR
        (reopened_at IS NOT NULL AND reopened_by IS NOT NULL
            AND length(btrim(coalesce(reopen_reason, ''))) > 0 AND length(btrim(coalesce(reopen_detail, ''))) > 0))
);
-- Un jugador tiene a lo sumo un cierre vigente por temporada.
CREATE UNIQUE INDEX ux_season_closures_open ON season_closures (player_id, season_id) WHERE reopened_at IS NULL;
CREATE INDEX ix_season_closures_player ON season_closures (player_id, closed_at DESC);
CREATE INDEX ix_season_closures_report ON season_closures (report_id);

-- Solo se puede registrar la reapertura, una vez. Todo lo demás es inmutable (CT001).
CREATE FUNCTION season_closures_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE'
        OR OLD.reopened_at IS NOT NULL
        OR NEW.player_id IS DISTINCT FROM OLD.player_id
        OR NEW.season_id IS DISTINCT FROM OLD.season_id
        OR NEW.report_id IS DISTINCT FROM OLD.report_id
        OR NEW.closed_by IS DISTINCT FROM OLD.closed_by
        OR NEW.closed_at IS DISTINCT FROM OLD.closed_at THEN
        RAISE EXCEPTION 'IMMUTABLE_RECORD: % is not allowed on table % in this state', TG_OP, TG_TABLE_NAME
            USING ERRCODE = 'CT001';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_season_closures_guard
    BEFORE UPDATE OR DELETE
    ON season_closures
    FOR EACH ROW
EXECUTE FUNCTION season_closures_guard();
CREATE TRIGGER trg_season_closures_no_truncate
    BEFORE TRUNCATE
    ON season_closures
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();
