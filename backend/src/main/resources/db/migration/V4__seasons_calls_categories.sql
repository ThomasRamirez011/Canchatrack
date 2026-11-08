-- V4: temporadas, convocatorias y categorías (DDS secc. 7; módulo players).

CREATE TABLE seasons
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    name       varchar(100) NOT NULL UNIQUE,
    start_at   timestamptz  NOT NULL,
    end_at     timestamptz  NOT NULL,
    status     varchar(10)  NOT NULL DEFAULT 'PLANNED',
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid REFERENCES users (id),
    updated_at timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_seasons_range CHECK (start_at < end_at),
    CONSTRAINT ck_seasons_status CHECK (status IN ('PLANNED', 'ACTIVE', 'CLOSED'))
);
CREATE TRIGGER trg_seasons_updated_at
    BEFORE UPDATE
    ON seasons
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Convocatoria de inscripciones. "Abierta" se deriva de start_at/end_at (RN-01); status solo gobierna publicación.
CREATE TABLE calls
(
    id           uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    season_id    uuid         NOT NULL REFERENCES seasons (id),
    name         varchar(150) NOT NULL,
    start_at     timestamptz  NOT NULL,
    end_at       timestamptz  NOT NULL,
    instructions text,
    status       varchar(10)  NOT NULL DEFAULT 'DRAFT',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   uuid REFERENCES users (id),
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_calls_range CHECK (start_at < end_at),
    CONSTRAINT ck_calls_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CANCELLED'))
);
CREATE INDEX ix_calls_season ON calls (season_id);
CREATE TRIGGER trg_calls_updated_at
    BEFORE UPDATE
    ON calls
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Habilitación excepcional fuera de convocatoria: autorizada y justificada por el coordinador (RN-01, RF-044).
CREATE TABLE call_exceptions
(
    id          uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    call_id     uuid        NOT NULL REFERENCES calls (id),
    guardian_id uuid        NOT NULL REFERENCES guardians (id),
    reason      text        NOT NULL,
    granted_by  uuid        NOT NULL REFERENCES users (id),
    granted_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    CONSTRAINT ck_call_exceptions_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_call_exceptions_expiry CHECK (expires_at > granted_at)
);
CREATE INDEX ix_call_exceptions_call_guardian ON call_exceptions (call_id, guardian_id);

-- Categorías por rango de año de nacimiento (RN-04). Rangos y cupos pendientes del cliente (DP-01): se parametrizan.
CREATE TABLE categories
(
    id               uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    name             varchar(80) NOT NULL UNIQUE,
    birth_year_min   smallint    NOT NULL,
    birth_year_max   smallint    NOT NULL,
    capacity         integer     NOT NULL,
    next_category_id uuid REFERENCES categories (id), -- destino del ascenso (T-08)
    active           boolean     NOT NULL DEFAULT true,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid REFERENCES users (id),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_categories_capacity CHECK (capacity > 0),
    CONSTRAINT ck_categories_years CHECK (birth_year_min <= birth_year_max)
);
CREATE TRIGGER trg_categories_updated_at
    BEFORE UPDATE
    ON categories
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
