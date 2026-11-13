-- V20: segundo factor de autenticación (MFA, DDS 12.1) para coordinador y comité.
-- 1) Factor TOTP por usuario (RFC 6238); el secreto se guarda cifrado (AES-GCM), nunca en claro.
-- 2) Códigos de respaldo de un solo uso (solo su huella HMAC).
-- 3) Desafíos de corta vida que enlazan el inicio de sesión con la verificación del código.

CREATE TABLE mfa_factors
(
    user_id        uuid PRIMARY KEY REFERENCES users (id),
    secret_sealed  bytea       NOT NULL,           -- IV || texto cifrado || etiqueta GCM
    status         varchar(10) NOT NULL,           -- PENDING (sin confirmar) | ACTIVE
    last_used_step bigint,                         -- último intervalo de 30 s aceptado: impide reutilizar un código
    created_at     timestamptz NOT NULL,
    confirmed_at   timestamptz,
    CONSTRAINT ck_mfa_factors_status CHECK (status IN ('PENDING', 'ACTIVE')),
    CONSTRAINT ck_mfa_factors_confirmed CHECK ((status = 'ACTIVE') = (confirmed_at IS NOT NULL)),
    CONSTRAINT ck_mfa_factors_secret CHECK (octet_length(secret_sealed) > 12)
);

CREATE TABLE mfa_backup_codes
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    code_hash  char(64)    NOT NULL,               -- HMAC-SHA256 en hexadecimal
    created_at timestamptz NOT NULL,
    used_at    timestamptz,
    CONSTRAINT ux_mfa_backup_codes UNIQUE (user_id, code_hash),
    CONSTRAINT ck_mfa_backup_codes_hash CHECK (code_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_mfa_backup_codes_open ON mfa_backup_codes (user_id) WHERE used_at IS NULL;

CREATE TABLE mfa_challenges
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id     uuid        NOT NULL REFERENCES users (id),
    token_hash  char(64)    NOT NULL,              -- SHA-256 del desafío opaco entregado al cliente
    purpose     varchar(10) NOT NULL,              -- VERIFY (ya tiene factor) | ENROLL (debe configurarlo)
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz NOT NULL,
    attempts    integer     NOT NULL DEFAULT 0,
    consumed_at timestamptz,
    ip          inet,
    CONSTRAINT ux_mfa_challenges_hash UNIQUE (token_hash),
    CONSTRAINT ck_mfa_challenges_purpose CHECK (purpose IN ('VERIFY', 'ENROLL')),
    CONSTRAINT ck_mfa_challenges_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mfa_challenges_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_mfa_challenges_attempts CHECK (attempts >= 0)
);
CREATE INDEX ix_mfa_challenges_open ON mfa_challenges (user_id) WHERE consumed_at IS NULL;
CREATE INDEX ix_mfa_challenges_expiry ON mfa_challenges (expires_at);
