-- V8: asistencia con sincronización offline idempotente (DDS 4.1, 7, 10.2 y 13.1; RN-15; módulo attendance).
-- El porcentaje NO se almacena: se deriva (DDS 10.2).

CREATE TABLE attendance_sessions
(
    event_id   uuid PRIMARY KEY REFERENCES sports_events (id),
    closed_at  timestamptz,
    closed_by  uuid REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_attendance_sessions_closed CHECK ((closed_at IS NULL) = (closed_by IS NULL))
);

-- Lote sincronizado desde el dispositivo (Idempotency-Key). Un reintento con la misma clave no duplica.
CREATE TABLE sync_batches
(
    id               uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    event_id         uuid         NOT NULL REFERENCES sports_events (id),
    idempotency_key  varchar(100) NOT NULL,
    received_by      uuid         NOT NULL REFERENCES users (id),
    received_at      timestamptz  NOT NULL DEFAULT now(),
    record_count     integer      NOT NULL DEFAULT 0,
    accepted_count   integer      NOT NULL DEFAULT 0,
    duplicate_count  integer      NOT NULL DEFAULT 0,
    rejected_count   integer      NOT NULL DEFAULT 0,
    CONSTRAINT ux_sync_batches UNIQUE (received_by, idempotency_key),
    CONSTRAINT ck_sync_batches_counts CHECK (record_count >= 0 AND accepted_count >= 0 AND duplicate_count >= 0 AND rejected_count >= 0)
);
CREATE INDEX ix_sync_batches_event ON sync_batches (event_id);

CREATE TABLE attendance_records
(
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    event_id           uuid        NOT NULL REFERENCES sports_events (id),
    player_id          uuid        NOT NULL REFERENCES players (id),
    status             varchar(10) NOT NULL,
    excuse_accepted    boolean     NOT NULL DEFAULT false,
    client_uuid        uuid        NOT NULL,                 -- identificador generado en el dispositivo
    client_recorded_at timestamptz,                          -- marca de tiempo local
    recorded_by        uuid        NOT NULL REFERENCES users (id),
    recorded_at        timestamptz NOT NULL DEFAULT now(),
    sync_batch_id      uuid REFERENCES sync_batches (id),
    CONSTRAINT ux_attendance_records_session_player UNIQUE (event_id, player_id),
    CONSTRAINT ux_attendance_records_client_uuid UNIQUE (client_uuid),
    CONSTRAINT ck_attendance_records_status CHECK (status IN ('PRESENT', 'LATE', 'EXCUSED', 'ABSENT')),
    CONSTRAINT ck_attendance_records_excuse CHECK (NOT excuse_accepted OR status = 'EXCUSED')
);
CREATE INDEX ix_attendance_records_player ON attendance_records (player_id, event_id);

-- Correcciones posteriores al cierre: siempre con motivo y rastro (RN-15). Append-only.
CREATE TABLE attendance_corrections
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    record_id       uuid        NOT NULL REFERENCES attendance_records (id),
    previous_status varchar(10) NOT NULL,
    new_status      varchar(10) NOT NULL,
    reason          text        NOT NULL,
    corrected_by    uuid        NOT NULL REFERENCES users (id),
    corrected_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_attendance_corrections_status CHECK (
        previous_status IN ('PRESENT', 'LATE', 'EXCUSED', 'ABSENT') AND new_status IN ('PRESENT', 'LATE', 'EXCUSED', 'ABSENT')),
    CONSTRAINT ck_attendance_corrections_reason CHECK (length(btrim(reason)) > 0)
);
CREATE INDEX ix_attendance_corrections_record ON attendance_corrections (record_id);
CREATE TRIGGER trg_attendance_corrections_immutable
    BEFORE UPDATE OR DELETE
    ON attendance_corrections
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();

-- Sesión cerrada => los registros no se tocan (RN-15). Una corrección autorizada ejecuta en su transacción:
--     SELECT set_config('canchatrack.attendance_correction', 'on', true);
-- (alcance de transacción) e inserta la fila en attendance_corrections.
CREATE FUNCTION enforce_attendance_session_open() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_closed_at timestamptz;
BEGIN
    SELECT closed_at INTO v_closed_at FROM attendance_sessions WHERE event_id = NEW.event_id;
    IF v_closed_at IS NOT NULL
        AND coalesce(current_setting('canchatrack.attendance_correction', true), 'off') <> 'on' THEN
        RAISE EXCEPTION 'ATTENDANCE_SESSION_CLOSED: session % is closed', NEW.event_id
            USING ERRCODE = 'CT002';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_attendance_records_session_open
    BEFORE INSERT OR UPDATE
    ON attendance_records
    FOR EACH ROW
EXECUTE FUNCTION enforce_attendance_session_open();
CREATE TRIGGER trg_attendance_records_no_delete
    BEFORE DELETE
    ON attendance_records
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
