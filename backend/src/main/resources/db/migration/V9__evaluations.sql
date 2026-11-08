-- V9: evaluación y unanimidad de ascenso (DDS 4.1, 5.1, 7 y 13.1; RN-08..RN-12; módulo evaluations).
-- La unanimidad (UnanimityPolicy) se calcula en el dominio; aquí se imponen las invariantes de datos.

-- Rúbrica versionada y parametrizable (DP-06). La definición de criterios es JSON del club.
CREATE TABLE evaluation_rubrics
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    name       varchar(100) NOT NULL,
    version    integer      NOT NULL,
    definition jsonb        NOT NULL DEFAULT '{}',
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid REFERENCES users (id),
    CONSTRAINT ux_evaluation_rubrics UNIQUE (name, version),
    CONSTRAINT ck_evaluation_rubrics_version CHECK (version > 0)
);

CREATE TABLE evaluation_rounds
(
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    player_id          uuid        NOT NULL REFERENCES players (id),
    rubric_id          uuid REFERENCES evaluation_rubrics (id),
    period_start       date        NOT NULL,
    period_end         date        NOT NULL,
    due_at             timestamptz,                               -- vencimiento del periodo (RN-12)
    status             varchar(10) NOT NULL DEFAULT 'OPEN',
    result             varchar(10),                               -- APPROVED | FOLLOW_UP al cerrar
    follow_up_plan     text,
    closed_at          timestamptz,
    opened_by          uuid        NOT NULL REFERENCES users (id),
    optimistic_version bigint      NOT NULL DEFAULT 0,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_evaluation_rounds_period CHECK (period_start <= period_end),
    CONSTRAINT ck_evaluation_rounds_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_evaluation_rounds_result CHECK (result IS NULL OR result IN ('APPROVED', 'FOLLOW_UP')),
    CONSTRAINT ck_evaluation_rounds_closure CHECK (
        (status = 'OPEN' AND closed_at IS NULL AND result IS NULL) OR
        (status = 'CLOSED' AND closed_at IS NOT NULL AND result IS NOT NULL))
);
-- Un jugador solo está "En evaluación" en una ronda a la vez (DAD E07).
CREATE UNIQUE INDEX ux_evaluation_rounds_open_per_player ON evaluation_rounds (player_id) WHERE status = 'OPEN';
CREATE INDEX ix_evaluation_rounds_player ON evaluation_rounds (player_id, period_start DESC);
CREATE TRIGGER trg_evaluation_rounds_updated_at
    BEFORE UPDATE
    ON evaluation_rounds
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

ALTER TABLE category_assignments
    ADD CONSTRAINT fk_category_assignments_round FOREIGN KEY (promotion_round_id) REFERENCES evaluation_rounds (id);

-- Concepto individual de cada evaluador. Editable solo por su autor y solo mientras la ronda está abierta.
-- 'actions' (acciones concretas, RN-11) lo exige el dominio; la justificación sí se impone aquí (DDS PA-03).
CREATE TABLE evaluator_decisions
(
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    round_id      uuid        NOT NULL REFERENCES evaluation_rounds (id),
    evaluator_id  uuid        NOT NULL REFERENCES users (id),
    verdict       varchar(20) NOT NULL,
    justification text,
    actions       text,
    criteria      jsonb       NOT NULL DEFAULT '{}',
    period_start  date        NOT NULL,                       -- periodo sobre el que se emitió (RN-09)
    period_end    date        NOT NULL,
    decided_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ux_evaluator_decisions UNIQUE (round_id, evaluator_id),
    CONSTRAINT ck_evaluator_decisions_verdict CHECK (verdict IN ('FAVORABLE', 'FOLLOW_UP_REQUIRED', 'UNFAVORABLE')),
    CONSTRAINT ck_evaluator_decisions_justification CHECK (
        verdict = 'FAVORABLE' OR length(btrim(coalesce(justification, ''))) > 0),
    CONSTRAINT ck_evaluator_decisions_period CHECK (period_start <= period_end)
);
CREATE INDEX ix_evaluator_decisions_evaluator ON evaluator_decisions (evaluator_id);

-- Historial de cambios del concepto propio mientras la ronda está abierta (DAD CU-07 y secc. 4.1).
CREATE TABLE evaluator_decision_history
(
    id            uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    decision_id   uuid        NOT NULL REFERENCES evaluator_decisions (id),
    verdict       varchar(20) NOT NULL,
    justification text,
    actions       text,
    criteria      jsonb       NOT NULL,
    valid_from    timestamptz NOT NULL,                       -- cuándo se emitió esta versión
    superseded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_evaluator_decision_history_decision ON evaluator_decision_history (decision_id, superseded_at);
CREATE TRIGGER trg_evaluator_decision_history_immutable
    BEFORE UPDATE OR DELETE
    ON evaluator_decision_history
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();

-- Ronda cerrada => sin altas ni cambios de decisiones; la autoría no cambia nunca (RN-08, RN-10).
-- FOR SHARE sobre la ronda: el cierre (UPDATE de la ronda) espera a las decisiones en curso y viceversa (DDS 13.1).
CREATE FUNCTION guard_evaluator_decision() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_status varchar(10);
BEGIN
    SELECT status INTO v_status FROM evaluation_rounds WHERE id = NEW.round_id FOR SHARE;
    IF v_status IS DISTINCT FROM 'OPEN' THEN
        RAISE EXCEPTION 'ROUND_CLOSED: round % does not accept decisions', NEW.round_id
            USING ERRCODE = 'CT003';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.round_id <> OLD.round_id OR NEW.evaluator_id <> OLD.evaluator_id) THEN
        RAISE EXCEPTION 'IMMUTABLE_RECORD: the authorship of a decision cannot change'
            USING ERRCODE = 'CT001';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_evaluator_decisions_guard
    BEFORE INSERT OR UPDATE
    ON evaluator_decisions
    FOR EACH ROW
EXECUTE FUNCTION guard_evaluator_decision();
CREATE TRIGGER trg_evaluator_decisions_updated_at
    BEFORE UPDATE
    ON evaluator_decisions
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_evaluator_decisions_no_delete
    BEFORE DELETE
    ON evaluator_decisions
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();

CREATE FUNCTION record_evaluator_decision_history() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    INSERT INTO evaluator_decision_history (decision_id, verdict, justification, actions, criteria, valid_from)
    VALUES (OLD.id, OLD.verdict, OLD.justification, OLD.actions, OLD.criteria, OLD.updated_at);
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_evaluator_decisions_history
    AFTER UPDATE
    ON evaluator_decisions
    FOR EACH ROW
    WHEN (OLD.verdict IS DISTINCT FROM NEW.verdict
        OR OLD.justification IS DISTINCT FROM NEW.justification
        OR OLD.actions IS DISTINCT FROM NEW.actions
        OR OLD.criteria IS DISTINCT FROM NEW.criteria)
EXECUTE FUNCTION record_evaluator_decision_history();
