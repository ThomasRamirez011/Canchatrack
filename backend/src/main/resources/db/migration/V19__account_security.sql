-- V19: seguridad de cuentas (módulo identity; DDS 12.1).
-- 1) Verificación de correo del autorregistro de acudientes.
-- 2) Tokens de un solo uso para recuperar la contraseña y verificar el correo (solo se guarda su hash SHA-256).
-- 3) Eventos del límite de tasa (ventana deslizante por IP y por correo; solo huellas, nunca el valor).

ALTER TABLE users
    ADD COLUMN email_verified_at timestamptz;
-- Las cuentas existentes se dan por verificadas: las creó la coordinación o se registraron antes de esta exigencia.
UPDATE users
SET email_verified_at = created_at
WHERE email_verified_at IS NULL;

CREATE TABLE account_tokens
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    purpose    varchar(20) NOT NULL,
    token_hash char(64)    NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,                     -- canjeado: no se puede usar otra vez
    revoked_at timestamptz,                     -- reemplazado por uno más reciente o invalidado
    ip         inet,
    CONSTRAINT ux_account_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_account_tokens_purpose CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')),
    CONSTRAINT ck_account_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_account_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_account_tokens_final CHECK (used_at IS NULL OR revoked_at IS NULL)
);
-- Tokens aún utilizables de un usuario y propósito (para invalidarlos al emitir uno nuevo).
CREATE INDEX ix_account_tokens_open ON account_tokens (user_id, purpose)
    WHERE used_at IS NULL AND revoked_at IS NULL;
-- Para la limpieza periódica.
CREATE INDEX ix_account_tokens_expiry ON account_tokens (expires_at);

CREATE TABLE rate_limit_events
(
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bucket       varchar(30) NOT NULL,
    subject_hash char(64)    NOT NULL,          -- SHA-256 de la IP o del correo normalizado
    occurred_at  timestamptz NOT NULL,
    CONSTRAINT ck_rate_limit_hash CHECK (subject_hash ~ '^[0-9a-f]{64}$')
);
CREATE INDEX ix_rate_limit_lookup ON rate_limit_events (bucket, subject_hash, occurred_at);
CREATE INDEX ix_rate_limit_occurred ON rate_limit_events (occurred_at);

-- Limpieza de refresh tokens ya inservibles (revocados). El índice de vencimiento por familia ya existe (V13).
CREATE INDEX ix_refresh_tokens_revoked ON refresh_tokens (revoked_at) WHERE revoked_at IS NOT NULL;
