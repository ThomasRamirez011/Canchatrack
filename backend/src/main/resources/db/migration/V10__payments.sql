-- V10: tarifas, cargos, pagos inmutables y ajustes compensatorios (DDS 4.1, 7 y 7.2; RN-16; módulo payments).
-- Reglas: un pago NUNCA se edita ni se borra; las correcciones son asientos compensatorios en 'adjustments'.
-- El saldo se deriva (vista player_balances); no es un dato editable.
-- Tarifas, calendario de pagos y política de mora siguen pendientes del cliente (DP-05).

-- Tarifa vigente por concepto y temporada.
CREATE TABLE fees
(
    id         uuid PRIMARY KEY       DEFAULT gen_random_uuid(),
    season_id  uuid          NOT NULL REFERENCES seasons (id),
    concept    varchar(20)   NOT NULL,
    name       varchar(120)  NOT NULL,
    amount     numeric(12, 2) NOT NULL,
    currency   char(3)       NOT NULL DEFAULT 'COP',
    valid_from date          NOT NULL,
    valid_to   date,
    active     boolean       NOT NULL DEFAULT true,
    created_at timestamptz   NOT NULL DEFAULT now(),
    created_by uuid REFERENCES users (id),
    updated_at timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_fees_amount CHECK (amount > 0),
    CONSTRAINT ck_fees_concept CHECK (concept IN ('ENROLLMENT', 'MONTHLY', 'OTHER')),
    CONSTRAINT ck_fees_validity CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX ix_fees_season_concept ON fees (season_id, concept);
CREATE TRIGGER trg_fees_updated_at
    BEFORE UPDATE
    ON fees
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Obligación de pago de un jugador (matrícula o mensualidad). Necesaria para derivar saldo y mora (ERS 7.3 "Cartera").
-- period = primer día del mes para mensualidades; NULL para cargos únicos.
CREATE TABLE charges
(
    id          uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    player_id   uuid           NOT NULL REFERENCES players (id),
    fee_id      uuid           NOT NULL REFERENCES fees (id),
    period      date,
    amount      numeric(12, 2) NOT NULL,
    due_date    date           NOT NULL,
    voided_at   timestamptz,
    void_reason text,
    voided_by   uuid REFERENCES users (id),
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  uuid REFERENCES users (id),
    CONSTRAINT ux_charges_player_fee_period UNIQUE NULLS NOT DISTINCT (player_id, fee_id, period),
    CONSTRAINT ck_charges_amount CHECK (amount > 0),
    CONSTRAINT ck_charges_void CHECK ((voided_at IS NULL) = (void_reason IS NULL) AND (voided_at IS NULL) = (voided_by IS NULL))
);
CREATE INDEX ix_charges_player_due ON charges (player_id, due_date) WHERE voided_at IS NULL;

CREATE TABLE payments
(
    id              uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    player_id       uuid           NOT NULL REFERENCES players (id),
    fee_id          uuid           NOT NULL REFERENCES fees (id),
    charge_id       uuid REFERENCES charges (id),
    amount          numeric(12, 2) NOT NULL,
    method          varchar(30)    NOT NULL,
    reference       varchar(100),
    receipt_file_id uuid REFERENCES stored_files (id),
    paid_at         timestamptz    NOT NULL,
    recorded_by     uuid           NOT NULL REFERENCES users (id),
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ck_payments_amount CHECK (amount > 0)
);
CREATE INDEX ix_payments_player_paid ON payments (player_id, paid_at);
CREATE INDEX ix_payments_charge ON payments (charge_id) WHERE charge_id IS NOT NULL;
CREATE TRIGGER trg_payments_immutable
    BEFORE UPDATE OR DELETE
    ON payments
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_payments_no_truncate
    BEFORE TRUNCATE
    ON payments
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Asiento compensatorio sobre un pago. amount con signo: negativo reduce lo pagado, positivo lo aumenta.
-- VOID (anulación) revierte el neto restante del pago; solo puede haber una por pago (PA-12).
CREATE TABLE adjustments
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    payment_id uuid           NOT NULL REFERENCES payments (id),
    kind       varchar(10)    NOT NULL,
    amount     numeric(12, 2) NOT NULL,
    reason     text           NOT NULL,
    created_by uuid           NOT NULL REFERENCES users (id),
    created_at timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ck_adjustments_kind CHECK (kind IN ('VOID', 'ADJUSTMENT')),
    CONSTRAINT ck_adjustments_amount CHECK (amount <> 0),
    CONSTRAINT ck_adjustments_void_negative CHECK (kind <> 'VOID' OR amount < 0),
    CONSTRAINT ck_adjustments_reason CHECK (length(btrim(reason)) > 0)
);
CREATE UNIQUE INDEX ux_adjustments_one_void_per_payment ON adjustments (payment_id) WHERE kind = 'VOID';
CREATE INDEX ix_adjustments_payment ON adjustments (payment_id);
CREATE TRIGGER trg_adjustments_immutable
    BEFORE UPDATE OR DELETE
    ON adjustments
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_adjustments_no_truncate
    BEFORE TRUNCATE
    ON adjustments
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Coherencia de ajustes: bloquea el pago (serializa ajustes concurrentes), impide ajustar un pago ya anulado,
-- exige que la anulación revierta exactamente el neto restante y que el neto nunca quede negativo.
CREATE FUNCTION validate_adjustment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_paid     numeric(12, 2);
    v_adjusted numeric(12, 2);
BEGIN
    SELECT amount INTO v_paid FROM payments WHERE id = NEW.payment_id FOR UPDATE;
    SELECT coalesce(sum(amount), 0) INTO v_adjusted FROM adjustments WHERE payment_id = NEW.payment_id;

    IF EXISTS (SELECT 1 FROM adjustments WHERE payment_id = NEW.payment_id AND kind = 'VOID') THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: payment % is already voided', NEW.payment_id
            USING ERRCODE = 'CT004';
    END IF;
    IF NEW.kind = 'VOID' AND NEW.amount <> -(v_paid + v_adjusted) THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: a VOID must reverse the remaining net amount of payment %', NEW.payment_id
            USING ERRCODE = 'CT004';
    END IF;
    IF v_paid + v_adjusted + NEW.amount < 0 THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: adjustments exceed the amount of payment %', NEW.payment_id
            USING ERRCODE = 'CT004';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_adjustments_validate
    BEFORE INSERT
    ON adjustments
    FOR EACH ROW
EXECUTE FUNCTION validate_adjustment();

-- Saldo derivado por jugador: cargos vigentes menos pagos netos de anulaciones y ajustes (DAD 7.2).
CREATE VIEW player_balances AS
SELECT p.id                                      AS player_id,
       coalesce(c.total, 0)                      AS charged,
       coalesce(pay.total, 0)                    AS paid,
       coalesce(c.total, 0) - coalesce(pay.total, 0) AS balance
FROM players p
         LEFT JOIN (SELECT player_id, sum(amount) AS total
                    FROM charges
                    WHERE voided_at IS NULL
                    GROUP BY player_id) c ON c.player_id = p.id
         LEFT JOIN (SELECT pm.player_id, sum(pm.amount + coalesce(a.adjusted, 0)) AS total
                    FROM payments pm
                             LEFT JOIN (SELECT payment_id, sum(amount) AS adjusted
                                        FROM adjustments
                                        GROUP BY payment_id) a ON a.payment_id = pm.id
                    GROUP BY pm.player_id) pay ON pay.player_id = p.id;
