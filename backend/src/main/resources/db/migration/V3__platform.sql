-- V3: tablas transversales (DDS secc. 7 y 12): archivos, auditoría, historial de estados,
-- outbox, idempotencia y parámetros versionados.

-- Metadatos de archivos; el binario vive en el almacenamiento de objetos (DT-04, DDS 7.1).
CREATE TABLE stored_files
(
    id            uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    original_name varchar(255) NOT NULL,
    content_type  varchar(150) NOT NULL,
    size_bytes    bigint       NOT NULL,
    storage_key   varchar(500) NOT NULL UNIQUE,   -- clave no predecible / no enumerable
    sha256        varchar(64)  NOT NULL,
    uploaded_by   uuid REFERENCES users (id),
    uploaded_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_stored_files_size CHECK (size_bytes > 0),
    CONSTRAINT ck_stored_files_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$')
);
CREATE TRIGGER trg_stored_files_immutable
    BEFORE UPDATE OR DELETE
    ON stored_files
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_stored_files_no_truncate
    BEFORE TRUNCATE
    ON stored_files
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Bitácora inmutable (DDS 12.3, DAD 9.2). before/after = resumen JSON seguro, sin binarios ni secretos.
CREATE TABLE audit_events
(
    id             uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    actor_id       uuid REFERENCES users (id),       -- NULL: sistema o intento anónimo (login fallido)
    actor_role     varchar(30),
    action         varchar(80) NOT NULL,             -- código estable de operación
    entity_type    varchar(60) NOT NULL,
    entity_id      uuid,
    outcome        varchar(10) NOT NULL DEFAULT 'SUCCESS',
    before_state   jsonb,
    after_state    jsonb,
    reason         text,
    ip             inet,
    user_agent     varchar(500),
    correlation_id varchar(64),
    CONSTRAINT ck_audit_events_outcome CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE'))
);
CREATE INDEX ix_audit_events_entity ON audit_events (entity_type, entity_id, occurred_at DESC);
CREATE INDEX ix_audit_events_actor ON audit_events (actor_id, occurred_at DESC);
CREATE INDEX ix_audit_events_action ON audit_events (action, occurred_at DESC);
CREATE INDEX ix_audit_events_correlation ON audit_events (correlation_id);
CREATE TRIGGER trg_audit_events_immutable
    BEFORE UPDATE OR DELETE
    ON audit_events
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_audit_events_no_truncate
    BEFORE TRUNCATE
    ON audit_events
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Transiciones de estado (DDS secc. 5). transition_code = T-01..T-12 cuando aplique.
CREATE TABLE state_history
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    entity_type     varchar(60) NOT NULL,
    entity_id       uuid        NOT NULL,
    transition_code varchar(10),
    from_state      varchar(40),                      -- NULL en el estado inicial
    to_state        varchar(40) NOT NULL,
    reason          text,
    actor_id        uuid REFERENCES users (id),       -- NULL cuando la transición la ejecuta el sistema
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    correlation_id  varchar(64)
);
CREATE INDEX ix_state_history_entity ON state_history (entity_type, entity_id, occurred_at);
CREATE TRIGGER trg_state_history_immutable
    BEFORE UPDATE OR DELETE
    ON state_history
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_state_history_no_truncate
    BEFORE TRUNCATE
    ON state_history
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Patrón outbox (DDS 3.1 y 11.1): se escribe en la misma transacción de negocio.
-- El procesador toma filas pendientes con SELECT ... FOR UPDATE SKIP LOCKED.
CREATE TABLE outbox_events
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    aggregate_type  varchar(60) NOT NULL,
    aggregate_id    uuid,
    event_type      varchar(80) NOT NULL,
    payload         jsonb       NOT NULL,
    correlation_id  varchar(64),
    occurred_at     timestamptz NOT NULL DEFAULT now(),
    published_at    timestamptz,
    attempt_count   integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    CONSTRAINT ck_outbox_attempts CHECK (attempt_count >= 0)
);
CREATE INDEX ix_outbox_pending ON outbox_events (next_attempt_at, occurred_at) WHERE published_at IS NULL;

-- Idempotency-Key de comandos reintentables (DDS 8.1 y 13.1; regla crítica 2 de CLAUDE.md).
CREATE TABLE idempotency_keys
(
    id              uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    user_id         uuid         NOT NULL REFERENCES users (id),
    idempotency_key varchar(100) NOT NULL,
    http_method     varchar(10)  NOT NULL,
    request_path    varchar(300) NOT NULL,
    request_hash    varchar(64)  NOT NULL,          -- detecta reutilizar la clave con otro cuerpo
    status          varchar(12)  NOT NULL,
    response_status smallint,
    response_body   jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    completed_at    timestamptz,
    expires_at      timestamptz  NOT NULL,
    CONSTRAINT ux_idempotency_keys UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);
CREATE INDEX ix_idempotency_keys_expires ON idempotency_keys (expires_at);

-- Parámetros del club versionados (DAD P-07, DDS 10.2): umbrales, ponderaciones, recordatorios, mora, etc.
-- Cada cambio es una versión nueva; no se edita ni se borra.
CREATE TABLE club_parameters
(
    id          uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    param_key   varchar(100) NOT NULL,
    version     integer      NOT NULL,
    value       jsonb        NOT NULL,
    description text,
    valid_from  timestamptz  NOT NULL DEFAULT now(),
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  uuid REFERENCES users (id),
    CONSTRAINT ux_club_parameters UNIQUE (param_key, version),
    CONSTRAINT ck_club_parameters_version CHECK (version > 0)
);
CREATE TRIGGER trg_club_parameters_immutable
    BEFORE UPDATE OR DELETE
    ON club_parameters
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();

-- Solo se siembran los valores que el DDS fija explícitamente (10.1 y secc. 11).
-- Ponderación de tardes/excusas, umbral de asistencia, mora y vigencias siguen pendientes del cliente (DP-04, DP-05).
INSERT INTO club_parameters (param_key, version, value, description)
VALUES ('documents.expiry_alert_days', 1, '[30, 7]', 'Días antes del vencimiento en que se alerta (DDS 10.1).'),
       ('notifications.event_reminder_hours', 1, '[24, 2]', 'Horas antes del evento en que se recuerda (DDS secc. 11).');
