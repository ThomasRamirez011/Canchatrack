-- V5: expediente del jugador, inscripción, comité y asignaciones (DDS secc. 4, 5 y 7; módulo players).
-- Un único expediente por jugador a lo largo de categorías y temporadas (RN-20).

CREATE SEQUENCE player_code_seq START 1;
CREATE SEQUENCE filing_code_seq START 1;

CREATE TABLE players
(
    id                 uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    -- Formato provisional del código; el club puede definir otro (se cambia el DEFAULT con una migración nueva).
    code               varchar(20)  NOT NULL DEFAULT ('JG-' || lpad(nextval('player_code_seq')::text, 6, '0')),
    first_name         varchar(100) NOT NULL,
    last_name          varchar(100) NOT NULL,
    document_type      varchar(10)  NOT NULL,
    document_number    varchar(30)  NOT NULL,
    birth_date         date         NOT NULL,
    status             varchar(30)  NOT NULL DEFAULT 'SUBMITTED',
    optimistic_version bigint       NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid REFERENCES users (id),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ux_players_code UNIQUE (code),
    CONSTRAINT ux_players_document UNIQUE (document_type, document_number),
    -- Estados del DAD 3.2 (E02..E12). COMMITTEE_PENDING (E03) figura en el DAD pero no en el DDS secc. 5 (ver README).
    CONSTRAINT ck_players_status CHECK (status IN (
                                                   'SUBMITTED',            -- E02 inscripción radicada
                                                   'COMMITTEE_PENDING',    -- E03 pendiente de comité técnico
                                                   'CATEGORY_ASSIGNED',    -- E04
                                                   'PENDING_REGISTRATION', -- E05 pendiente de matrícula
                                                   'ACTIVE',               -- E06 jugador activo
                                                   'IN_EVALUATION',        -- E07
                                                   'PROMOTION_APPROVED',   -- E08 ascenso aprobado
                                                   'SUSPENDED',            -- E09
                                                   'WITHDRAWN',            -- E10 retirado
                                                   'SEASON_CLOSED',        -- E11 temporada cerrada
                                                   'REJECTED'              -- E12 inscripción no aceptada
        ))
);
CREATE INDEX ix_players_status ON players (status);
CREATE INDEX ix_players_birth_date ON players (birth_date);
CREATE TRIGGER trg_players_updated_at
    BEFORE UPDATE
    ON players
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Vínculo acudiente-jugador. "Al menos un acudiente vigente" lo valida el dominio (DDS 4.1).
CREATE TABLE player_guardians
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id    uuid        NOT NULL REFERENCES players (id),
    guardian_id  uuid        NOT NULL REFERENCES guardians (id),
    relationship varchar(40),
    is_primary   boolean     NOT NULL DEFAULT false,
    valid_from   timestamptz NOT NULL DEFAULT now(),
    valid_to     timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid REFERENCES users (id),
    CONSTRAINT ux_player_guardians UNIQUE (player_id, guardian_id),
    CONSTRAINT ck_player_guardians_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE UNIQUE INDEX ux_player_guardians_primary ON player_guardians (player_id) WHERE is_primary AND valid_to IS NULL;
CREATE INDEX ix_player_guardians_guardian ON player_guardians (guardian_id);

-- Inscripción de un jugador en una convocatoria (DAD 7.1, UC-01). Un jugador se inscribe una vez por convocatoria.
CREATE TABLE enrollments
(
    id                uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id         uuid        NOT NULL REFERENCES players (id),
    call_id           uuid        NOT NULL REFERENCES calls (id),
    guardian_id       uuid        NOT NULL REFERENCES guardians (id), -- quien radica
    filing_code       varchar(30) NOT NULL DEFAULT ('RAD-' || lpad(nextval('filing_code_seq')::text, 6, '0')),
    call_exception_id uuid REFERENCES call_exceptions (id),           -- si se radicó fuera de convocatoria
    submitted_at      timestamptz NOT NULL DEFAULT now(),
    created_at        timestamptz NOT NULL DEFAULT now(),
    created_by        uuid REFERENCES users (id),
    CONSTRAINT ux_enrollments_player_call UNIQUE (player_id, call_id),
    CONSTRAINT ux_enrollments_filing_code UNIQUE (filing_code)
);
CREATE INDEX ix_enrollments_call ON enrollments (call_id);
CREATE INDEX ix_enrollments_guardian ON enrollments (guardian_id);

-- Decisión del Comité Técnico respaldada por acta (RN-03, RF-045). Un acta puede cubrir varios jugadores.
CREATE TABLE committee_minutes
(
    id              uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    minute_number   varchar(30) NOT NULL,
    meeting_date    date        NOT NULL,
    player_id       uuid        NOT NULL REFERENCES players (id),
    decision        varchar(10) NOT NULL,
    decision_reason text,
    attendees       jsonb       NOT NULL DEFAULT '[]',
    file_id         uuid        NOT NULL REFERENCES stored_files (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    created_by      uuid REFERENCES users (id),
    CONSTRAINT ux_committee_minutes UNIQUE (minute_number, player_id),
    CONSTRAINT ck_committee_minutes_decision CHECK (decision IN ('ASSIGNED', 'REJECTED')),
    CONSTRAINT ck_committee_minutes_reason CHECK (decision <> 'REJECTED' OR length(btrim(coalesce(decision_reason, ''))) > 0)
);
CREATE INDEX ix_committee_minutes_player ON committee_minutes (player_id);

-- Categoría y entrenador titular vigentes (una vigente por jugador). El historial se conserva (RN-20).
-- promotion_round_id referencia evaluation_rounds; la FK se agrega en V9.
CREATE TABLE category_assignments
(
    id                        uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id                 uuid        NOT NULL REFERENCES players (id),
    category_id               uuid        NOT NULL REFERENCES categories (id),
    head_coach_id             uuid        NOT NULL REFERENCES users (id),
    kind                      varchar(10) NOT NULL,
    minute_id                 uuid REFERENCES committee_minutes (id),
    promotion_round_id        uuid,
    capacity_exception_reason text,
    valid_from                timestamptz NOT NULL DEFAULT now(),
    valid_to                  timestamptz,
    created_at                timestamptz NOT NULL DEFAULT now(),
    created_by                uuid REFERENCES users (id),
    CONSTRAINT ck_category_assignments_kind CHECK (
        (kind = 'COMMITTEE' AND minute_id IS NOT NULL) OR
        (kind = 'PROMOTION' AND promotion_round_id IS NOT NULL)),
    CONSTRAINT ck_category_assignments_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE UNIQUE INDEX ux_category_assignments_current ON category_assignments (player_id) WHERE valid_to IS NULL;
CREATE INDEX ix_category_assignments_category ON category_assignments (category_id) WHERE valid_to IS NULL;
CREATE INDEX ix_category_assignments_coach ON category_assignments (head_coach_id) WHERE valid_to IS NULL;

-- Panel de evaluadores asignado por el comité (DAD SUP-04: exactamente tres al abrir una ronda; lo valida el dominio).
CREATE TABLE evaluator_assignments
(
    id           uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id    uuid        NOT NULL REFERENCES players (id),
    evaluator_id uuid        NOT NULL REFERENCES users (id),
    minute_id    uuid        NOT NULL REFERENCES committee_minutes (id),
    valid_from   timestamptz NOT NULL DEFAULT now(),
    valid_to     timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   uuid REFERENCES users (id),
    CONSTRAINT ck_evaluator_assignments_validity CHECK (valid_to IS NULL OR valid_to > valid_from)
);
CREATE UNIQUE INDEX ux_evaluator_assignments_current ON evaluator_assignments (player_id, evaluator_id) WHERE valid_to IS NULL;
CREATE INDEX ix_evaluator_assignments_evaluator ON evaluator_assignments (evaluator_id) WHERE valid_to IS NULL;
