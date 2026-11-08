-- V2: módulo identity (DDS secc. 3 y 7): users, roles, user_roles, guardians.

CREATE TABLE users
(
    id                    uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    email                 varchar(254) NOT NULL,
    full_name             varchar(200) NOT NULL,
    password_hash         varchar(255),                       -- NULL si la cuenta usa solo OIDC (DT-06)
    oidc_subject          varchar(255),
    status                varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    failed_login_attempts smallint     NOT NULL DEFAULT 0,
    locked_until          timestamptz,
    last_login_at         timestamptz,
    mfa_enabled           boolean      NOT NULL DEFAULT false, -- MFA opcional para coordinador (DDS 12.1)
    created_at            timestamptz  NOT NULL DEFAULT now(),
    created_by            uuid REFERENCES users (id),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'LOCKED', 'DISABLED')),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0)
);
CREATE UNIQUE INDEX ux_users_email ON users (lower(email));
CREATE UNIQUE INDEX ux_users_oidc_subject ON users (oidc_subject) WHERE oidc_subject IS NOT NULL;
CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE
    ON users
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TABLE roles
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    code        varchar(30) NOT NULL UNIQUE,
    name        varchar(80) NOT NULL,
    description text
);

-- Roles de la matriz de capacidades del DAD 4.1. La cuenta de consulta del jugador está pendiente (D-02).
INSERT INTO roles (code, name, description)
VALUES ('GUARDIAN', 'Acudiente', 'Representante legal del menor: inscribe, carga documentos y autoriza participación.'),
       ('COORDINATOR', 'Coordinador', 'Administra el proceso: agenda, pagos, activación, cierre y reapertura.'),
       ('COACH', 'Entrenador', 'Registra asistencia, propone convocatorias y resultados de su categoría.'),
       ('EVALUATOR', 'Evaluador', 'Emite su concepto individual como integrante del panel de tres.'),
       ('COMMITTEE', 'Comité Técnico', 'Aprueba categoría, entrenador y panel; consulta reportes.');

CREATE TABLE user_roles
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id    uuid        NOT NULL REFERENCES users (id),
    role_id    uuid        NOT NULL REFERENCES roles (id),
    valid_from timestamptz NOT NULL DEFAULT now(),
    valid_to   timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid REFERENCES users (id),
    CONSTRAINT ux_user_roles UNIQUE (user_id, role_id),
    CONSTRAINT ck_user_roles_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE INDEX ix_user_roles_role ON user_roles (role_id);

CREATE TABLE guardians
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    user_id         uuid        NOT NULL UNIQUE REFERENCES users (id),
    document_type   varchar(10) NOT NULL,
    document_number varchar(30) NOT NULL,
    phone           varchar(30),
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid REFERENCES users (id),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_guardians_document UNIQUE (document_type, document_number)
);
CREATE TRIGGER trg_guardians_updated_at
    BEFORE UPDATE
    ON guardians
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
