-- V13: sesiones de larga duración (módulo identity; DDS 12.1; DT-06).
-- El access token es un JWT corto y sin estado; la sesión se renueva con un refresh token OPACO que solo se guarda
-- como hash SHA-256 (nunca el valor). Cada uso ROTA el token dentro de una misma "familia": reutilizar un token ya
-- rotado indica robo y revoca toda la familia. Hay además un vencimiento absoluto por familia.

CREATE TABLE refresh_tokens
(
    id                uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id           uuid        NOT NULL REFERENCES users (id),
    family_id         uuid        NOT NULL,
    token_hash        char(64)    NOT NULL,
    issued_at         timestamptz NOT NULL,
    expires_at        timestamptz NOT NULL,
    family_expires_at timestamptz NOT NULL,           -- tope absoluto de la sesión, no se extiende al rotar
    used_at           timestamptz,                    -- momento en que se canjeó por uno nuevo
    revoked_at        timestamptz,
    revoked_reason    varchar(30),
    ip                inet,
    user_agent        varchar(500),
    CONSTRAINT ux_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > issued_at AND expires_at <= family_expires_at),
    CONSTRAINT ck_refresh_tokens_revocation CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL))
);
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
-- Para la limpieza periódica de tokens vencidos.
CREATE INDEX ix_refresh_tokens_expiry ON refresh_tokens (family_expires_at);
