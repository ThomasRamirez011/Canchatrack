-- V11: informes de temporada, soportes y notificaciones (DDS 4.1, 7, 10.4 y 11; módulos reports y notifications).

CREATE SEQUENCE season_report_consecutive_seq START 1;   -- el consecutivo no se reutiliza (DDS 10.4)

-- Informe versionado e inmutable. Regenerar crea una versión nueva que referencia la anterior y explica el motivo.
CREATE TABLE season_reports
(
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id          uuid        NOT NULL REFERENCES players (id),
    season_id          uuid        NOT NULL REFERENCES seasons (id),
    consecutive        bigint      NOT NULL DEFAULT nextval('season_report_consecutive_seq'),
    version            integer     NOT NULL DEFAULT 1,
    previous_report_id uuid REFERENCES season_reports (id),
    reason             text,
    verification_code  varchar(40) NOT NULL,
    template_version   varchar(30) NOT NULL,
    snapshot           jsonb       NOT NULL,             -- datos congelados (SeasonSnapshotFactory)
    file_id            uuid        NOT NULL REFERENCES stored_files (id),
    file_hash          varchar(64) NOT NULL,
    generated_at       timestamptz NOT NULL DEFAULT now(),
    generated_by       uuid REFERENCES users (id),       -- NULL: generación automática del sistema
    CONSTRAINT ux_season_reports_consecutive UNIQUE (consecutive),
    CONSTRAINT ux_season_reports_verification UNIQUE (verification_code),
    CONSTRAINT ux_season_reports_version UNIQUE (player_id, season_id, version),
    CONSTRAINT ck_season_reports_hash CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_season_reports_version CHECK (
        (version = 1 AND previous_report_id IS NULL) OR
        (version > 1 AND previous_report_id IS NOT NULL AND length(btrim(coalesce(reason, ''))) > 0))
);
CREATE INDEX ix_season_reports_file ON season_reports (file_id);
CREATE TRIGGER trg_season_reports_immutable
    BEFORE UPDATE OR DELETE
    ON season_reports
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_season_reports_no_truncate
    BEFORE TRUNCATE
    ON season_reports
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Actas y soportes cargados por coordinación, entrenadores, evaluadores o comité (DAD secc. 4.1).
CREATE TABLE support_documents
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    doc_type    varchar(40) NOT NULL,
    file_id     uuid        NOT NULL REFERENCES stored_files (id),
    player_id   uuid REFERENCES players (id),
    event_id    uuid REFERENCES sports_events (id),
    description text,
    uploaded_by uuid        NOT NULL REFERENCES users (id),
    uploaded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_support_documents_player ON support_documents (player_id) WHERE player_id IS NOT NULL;
CREATE INDEX ix_support_documents_event ON support_documents (event_id) WHERE event_id IS NOT NULL;
CREATE TRIGGER trg_support_documents_immutable
    BEFORE UPDATE OR DELETE
    ON support_documents
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();

-- Notificaciones por correo. payload es solo datos de plantilla: NUNCA datos de salud ni adjuntos (RN-17).
-- dedup_key evita duplicar el mismo aviso ante reintentos o doble procesamiento (PA-10).
CREATE TABLE notifications
(
    id                  uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    notification_type   varchar(40)  NOT NULL,
    recipient_user_id   uuid REFERENCES users (id),
    recipient_email     varchar(254) NOT NULL,
    subject             varchar(250),
    payload             jsonb        NOT NULL DEFAULT '{}',
    status              varchar(10)  NOT NULL DEFAULT 'PENDING',
    attempt_count       integer      NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz  NOT NULL DEFAULT now(),
    last_error          text,
    sent_at             timestamptz,
    outbox_event_id     uuid REFERENCES outbox_events (id),
    related_entity_type varchar(60),
    related_entity_id   uuid,
    dedup_key           varchar(150),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_notifications_dedup UNIQUE (dedup_key),
    CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'RETRYING', 'SENT', 'FAILED')),
    CONSTRAINT ck_notifications_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_notifications_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
CREATE INDEX ix_notifications_due ON notifications (next_attempt_at) WHERE status IN ('PENDING', 'RETRYING');
CREATE INDEX ix_notifications_failed ON notifications (updated_at) WHERE status = 'FAILED';
CREATE INDEX ix_notifications_entity ON notifications (related_entity_type, related_entity_id);
CREATE TRIGGER trg_notifications_updated_at
    BEFORE UPDATE
    ON notifications
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Evidencia técnica de cada intento de envío (DAD 8.2).
CREATE TABLE delivery_attempts
(
    id                uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    notification_id   uuid        NOT NULL REFERENCES notifications (id),
    attempt_number    integer     NOT NULL,
    attempted_at      timestamptz NOT NULL DEFAULT now(),
    succeeded         boolean     NOT NULL,
    provider_response text,
    error             text,
    CONSTRAINT ux_delivery_attempts UNIQUE (notification_id, attempt_number),
    CONSTRAINT ck_delivery_attempts_number CHECK (attempt_number > 0)
);
CREATE TRIGGER trg_delivery_attempts_immutable
    BEFORE UPDATE OR DELETE
    ON delivery_attempts
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
