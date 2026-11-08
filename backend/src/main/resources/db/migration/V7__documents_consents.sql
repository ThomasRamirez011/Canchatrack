-- V7: documentos versionados y consentimientos (DDS secc. 7 y 10.1; módulo documents).
-- Una versión nunca se reemplaza ni se borra (RN-05, P-04): cada carga es una fila y un objeto nuevos.

-- Catálogo configurable (D-04 pendiente: documentos obligatorios, vigencias, formatos y tamaños).
CREATE TABLE document_types
(
    id                   uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    code                 varchar(40)  NOT NULL UNIQUE,
    name                 varchar(120) NOT NULL,
    mandatory            boolean      NOT NULL DEFAULT true,
    requires_expiry      boolean      NOT NULL DEFAULT false,
    validity_days        integer,
    contains_health_data boolean      NOT NULL DEFAULT false,   -- restringe acceso y audita consultas (P-06, RN-17)
    max_size_bytes       bigint,
    allowed_content_types text[],
    active               boolean      NOT NULL DEFAULT true,
    created_at           timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_document_types_validity CHECK (validity_days IS NULL OR validity_days > 0),
    CONSTRAINT ck_document_types_size CHECK (max_size_bytes IS NULL OR max_size_bytes > 0)
);

-- Único tipo que el ERS nombra explícitamente (RN-06). Los demás los configura el club (D-04).
INSERT INTO document_types (code, name, mandatory, requires_expiry, contains_health_data)
VALUES ('MEDICAL_CERTIFICATE', 'Certificado médico', true, true, true);

-- Ventana habilitada para cargar documentos (RN-05). player_id NULL = ventana general.
CREATE TABLE document_windows
(
    id               uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id        uuid REFERENCES players (id),
    document_type_id uuid        NOT NULL REFERENCES document_types (id),
    opens_at         timestamptz NOT NULL,
    closes_at        timestamptz NOT NULL,
    reason           text,
    opened_by        uuid        NOT NULL REFERENCES users (id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_document_windows_range CHECK (opens_at < closes_at)
);
CREATE INDEX ix_document_windows_lookup ON document_windows (document_type_id, player_id, opens_at, closes_at);

CREATE TABLE document_versions
(
    id               uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id        uuid        NOT NULL REFERENCES players (id),
    document_type_id uuid        NOT NULL REFERENCES document_types (id),
    version_number   integer     NOT NULL,
    file_id          uuid        NOT NULL REFERENCES stored_files (id),
    issued_on        date,
    expires_on       date,                                   -- la vigencia se calcula contra esta fecha
    notes            text,
    uploaded_by      uuid        NOT NULL REFERENCES users (id),
    uploaded_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_document_versions UNIQUE (player_id, document_type_id, version_number),
    CONSTRAINT ck_document_versions_number CHECK (version_number > 0),
    CONSTRAINT ck_document_versions_dates CHECK (issued_on IS NULL OR expires_on IS NULL OR expires_on >= issued_on)
);
CREATE INDEX ix_document_versions_expiry ON document_versions (expires_on) WHERE expires_on IS NOT NULL;
CREATE INDEX ix_document_versions_file ON document_versions (file_id);
CREATE TRIGGER trg_document_versions_immutable
    BEFORE UPDATE OR DELETE
    ON document_versions
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_document_versions_no_truncate
    BEFORE TRUNCATE
    ON document_versions
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- La versión vigente es la de mayor número por jugador y tipo. La tabla es append-only, por eso se deriva.
CREATE VIEW current_document_versions AS
SELECT DISTINCT ON (player_id, document_type_id) id,
                                                 player_id,
                                                 document_type_id,
                                                 version_number,
                                                 file_id,
                                                 issued_on,
                                                 expires_on,
                                                 uploaded_by,
                                                 uploaded_at
FROM document_versions
ORDER BY player_id, document_type_id, version_number DESC;

-- Consentimientos y autorizaciones del acudiente (RN-02, SUP-03). Mecanismo y validez pendientes (DP-02).
-- Autorización por partido: kind = MATCH_AUTHORIZATION con match_id (DDS 4.1: "autorización vigente por partido").
CREATE TABLE consents
(
    id               uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    guardian_id      uuid        NOT NULL REFERENCES guardians (id),
    player_id        uuid        NOT NULL REFERENCES players (id),
    kind             varchar(25) NOT NULL,
    match_id         uuid REFERENCES matches (id),
    version_text     varchar(50) NOT NULL,                   -- identificador de la versión del texto aceptado
    method           varchar(25) NOT NULL,
    evidence_file_id uuid REFERENCES stored_files (id),
    accepted_at      timestamptz NOT NULL DEFAULT now(),
    valid_until      timestamptz,
    revoked_at       timestamptz,
    revoked_reason   text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid REFERENCES users (id),
    CONSTRAINT ck_consents_kind CHECK (kind IN ('DATA_PROCESSING', 'PARTICIPATION', 'MATCH_AUTHORIZATION')),
    CONSTRAINT ck_consents_method CHECK (method IN ('AUTHENTICATED_ACCEPTANCE', 'UPLOADED_EVIDENCE')),
    CONSTRAINT ck_consents_match CHECK ((kind = 'MATCH_AUTHORIZATION') = (match_id IS NOT NULL)),
    CONSTRAINT ck_consents_evidence CHECK (method <> 'UPLOADED_EVIDENCE' OR evidence_file_id IS NOT NULL),
    CONSTRAINT ck_consents_revocation CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
CREATE INDEX ix_consents_player_kind ON consents (player_id, kind);
CREATE INDEX ix_consents_guardian ON consents (guardian_id);
CREATE UNIQUE INDEX ux_consents_match_authorization ON consents (match_id, player_id)
    WHERE kind = 'MATCH_AUTHORIZATION' AND revoked_at IS NULL;
