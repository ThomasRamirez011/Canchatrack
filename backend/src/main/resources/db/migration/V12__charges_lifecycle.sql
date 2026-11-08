-- V12: ciclo de vida de los cargos (módulo payments; ERS RF-038..RF-040; DAD 7.2 "Tarifa / Pago").
-- Decisiones (confirmadas por el cliente del proyecto):
--   1. Los cargos se PERSISTEN (no se calculan al vuelo): si la tarifa cambia a mitad de temporada, la deuda histórica no cambia.
--      Matrícula: un cargo único al aprobarse la inscripción (period NULL). Mensualidad: un cargo por mes (period = día 1).
--      La generación es responsabilidad de la capa de aplicación; la restricción UNIQUE NULLS NOT DISTINCT de V10 la hace
--      idempotente (INSERT ... ON CONFLICT DO NOTHING).
--   2. Descuentos y exoneraciones son asientos de ajuste con motivo (append-only), igual que las correcciones de pagos.
--      Un cargo nunca se edita ni se borra: solo puede anularse una vez (voided_*).
--   3. La mora queda fuera: solo se conserva due_date. La política (días de gracia, recargos) es un parámetro pendiente (DP-05).

-- Un cargo es inmutable salvo su anulación única (voided_at / void_reason / voided_by).
CREATE FUNCTION guard_charge_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.player_id IS DISTINCT FROM OLD.player_id
        OR NEW.fee_id IS DISTINCT FROM OLD.fee_id
        OR NEW.period IS DISTINCT FROM OLD.period
        OR NEW.amount IS DISTINCT FROM OLD.amount
        OR NEW.due_date IS DISTINCT FROM OLD.due_date
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
        OR NEW.created_by IS DISTINCT FROM OLD.created_by THEN
        RAISE EXCEPTION 'CHARGE_IMMUTABLE: charge % can only be voided; use charge_adjustments to change what is owed', OLD.id
            USING ERRCODE = 'CT001';
    END IF;
    IF OLD.voided_at IS NOT NULL THEN
        RAISE EXCEPTION 'CHARGE_IMMUTABLE: charge % is already voided', OLD.id
            USING ERRCODE = 'CT001';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_charges_guard_update
    BEFORE UPDATE
    ON charges
    FOR EACH ROW
EXECUTE FUNCTION guard_charge_update();
CREATE TRIGGER trg_charges_no_delete
    BEFORE DELETE
    ON charges
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_charges_no_truncate
    BEFORE TRUNCATE
    ON charges
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Ajuste sobre un cargo. amount con signo: negativo reduce lo adeudado (DISCOUNT, EXEMPTION), CORRECTION admite ambos signos.
-- EXEMPTION perdona exactamente lo que aún se debe (neto menos lo ya pagado) y solo puede haber una por cargo.
CREATE TABLE charge_adjustments
(
    id         uuid PRIMARY KEY        DEFAULT gen_random_uuid(),
    charge_id  uuid           NOT NULL REFERENCES charges (id),
    kind       varchar(12)    NOT NULL,
    amount     numeric(12, 2) NOT NULL,
    reason     text           NOT NULL,
    created_by uuid           NOT NULL REFERENCES users (id),
    created_at timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT ck_charge_adjustments_kind CHECK (kind IN ('DISCOUNT', 'EXEMPTION', 'CORRECTION')),
    CONSTRAINT ck_charge_adjustments_amount CHECK (amount <> 0),
    CONSTRAINT ck_charge_adjustments_sign CHECK (kind = 'CORRECTION' OR amount < 0),
    CONSTRAINT ck_charge_adjustments_reason CHECK (length(btrim(reason)) > 0)
);
CREATE UNIQUE INDEX ux_charge_adjustments_one_exemption ON charge_adjustments (charge_id) WHERE kind = 'EXEMPTION';
CREATE INDEX ix_charge_adjustments_charge ON charge_adjustments (charge_id);
CREATE TRIGGER trg_charge_adjustments_immutable
    BEFORE UPDATE OR DELETE
    ON charge_adjustments
    FOR EACH ROW
EXECUTE FUNCTION forbid_modification();
CREATE TRIGGER trg_charge_adjustments_no_truncate
    BEFORE TRUNCATE
    ON charge_adjustments
    FOR EACH STATEMENT
EXECUTE FUNCTION forbid_modification();

-- Coherencia: bloquea el cargo (serializa ajustes y pagos concurrentes), impide ajustar un cargo anulado o ya exonerado,
-- exige que la exoneración perdone exactamente lo pendiente y que una reducción nunca deje el cargo con saldo a favor
-- (lo ya pagado no se perdona: eso se corrige anulando o ajustando el pago).
CREATE FUNCTION validate_charge_adjustment() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_amount      numeric(12, 2);
    v_voided      timestamptz;
    v_adjusted    numeric(12, 2);
    v_applied     numeric(12, 2);
    v_outstanding numeric(12, 2);
BEGIN
    SELECT amount, voided_at INTO v_amount, v_voided FROM charges WHERE id = NEW.charge_id FOR UPDATE;
    SELECT coalesce(sum(amount), 0) INTO v_adjusted FROM charge_adjustments WHERE charge_id = NEW.charge_id;
    SELECT coalesce(sum(pm.amount + coalesce(a.adjusted, 0)), 0)
    INTO v_applied
    FROM payments pm
             LEFT JOIN (SELECT payment_id, sum(amount) AS adjusted FROM adjustments GROUP BY payment_id) a
                       ON a.payment_id = pm.id
    WHERE pm.charge_id = NEW.charge_id;
    v_outstanding := v_amount + v_adjusted - v_applied;

    IF v_voided IS NOT NULL THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: charge % is voided', NEW.charge_id
            USING ERRCODE = 'CT004';
    END IF;
    IF EXISTS (SELECT 1 FROM charge_adjustments WHERE charge_id = NEW.charge_id AND kind = 'EXEMPTION') THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: charge % is already exempted', NEW.charge_id
            USING ERRCODE = 'CT004';
    END IF;
    IF NEW.kind = 'EXEMPTION' AND NEW.amount <> -v_outstanding THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: an EXEMPTION must forgive exactly what is still owed on charge %', NEW.charge_id
            USING ERRCODE = 'CT004';
    END IF;
    IF v_amount + v_adjusted + NEW.amount < 0 THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: adjustments exceed the amount of charge %', NEW.charge_id
            USING ERRCODE = 'CT004';
    END IF;
    IF NEW.amount < 0 AND v_outstanding + NEW.amount < 0 THEN
        RAISE EXCEPTION 'ADJUSTMENT_INVALID: reduction exceeds what is still owed on charge %', NEW.charge_id
            USING ERRCODE = 'CT004';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_charge_adjustments_validate
    BEFORE INSERT
    ON charge_adjustments
    FOR EACH ROW
EXECUTE FUNCTION validate_charge_adjustment();

-- Un pago aplicado a un cargo debe ser del mismo jugador y de la misma tarifa, y el cargo no puede estar anulado.
CREATE FUNCTION validate_payment_charge() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_player uuid;
    v_fee    uuid;
    v_voided timestamptz;
BEGIN
    IF NEW.charge_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT player_id, fee_id, voided_at INTO v_player, v_fee, v_voided FROM charges WHERE id = NEW.charge_id FOR SHARE;
    IF v_player IS DISTINCT FROM NEW.player_id OR v_fee IS DISTINCT FROM NEW.fee_id THEN
        RAISE EXCEPTION 'PAYMENT_CHARGE_MISMATCH: payment player/fee do not match charge %', NEW.charge_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF v_voided IS NOT NULL THEN
        RAISE EXCEPTION 'PAYMENT_CHARGE_VOIDED: charge % is voided', NEW.charge_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_payments_validate_charge
    BEFORE INSERT
    ON payments
    FOR EACH ROW
EXECUTE FUNCTION validate_payment_charge();

-- Saldo derivado por jugador: cargos vigentes netos de ajustes menos pagos netos de anulaciones y ajustes (DAD 7.2).
-- Mismas columnas que en V10.
CREATE OR REPLACE VIEW player_balances AS
SELECT p.id                                      AS player_id,
       coalesce(c.total, 0)                      AS charged,
       coalesce(pay.total, 0)                    AS paid,
       coalesce(c.total, 0) - coalesce(pay.total, 0) AS balance
FROM players p
         LEFT JOIN (SELECT ch.player_id, sum(ch.amount + coalesce(ca.adjusted, 0)) AS total
                    FROM charges ch
                             LEFT JOIN (SELECT charge_id, sum(amount) AS adjusted
                                        FROM charge_adjustments
                                        GROUP BY charge_id) ca ON ca.charge_id = ch.id
                    WHERE ch.voided_at IS NULL
                    GROUP BY ch.player_id) c ON c.player_id = p.id
         LEFT JOIN (SELECT pm.player_id, sum(pm.amount + coalesce(a.adjusted, 0)) AS total
                    FROM payments pm
                             LEFT JOIN (SELECT payment_id, sum(amount) AS adjusted
                                        FROM adjustments
                                        GROUP BY payment_id) a ON a.payment_id = pm.id
                    GROUP BY pm.player_id) pay ON pay.player_id = p.id;

-- Estado de cada cargo (estado de cuenta, cartera). La mora se calcula fuera, con due_date y el parámetro del club.
-- status: VOIDED | SETTLED (nada pendiente: pagado o exonerado) | PARTIAL | PENDING
CREATE VIEW charge_balances AS
SELECT ch.id                                                              AS charge_id,
       ch.player_id,
       ch.fee_id,
       ch.period,
       ch.due_date,
       ch.amount,
       coalesce(ca.adjusted, 0)                                           AS adjusted,
       ch.amount + coalesce(ca.adjusted, 0)                               AS net_amount,
       coalesce(pa.applied, 0)                                            AS applied,
       ch.amount + coalesce(ca.adjusted, 0) - coalesce(pa.applied, 0)     AS outstanding,
       CASE
           WHEN ch.voided_at IS NOT NULL THEN 'VOIDED'
           WHEN ch.amount + coalesce(ca.adjusted, 0) - coalesce(pa.applied, 0) <= 0 THEN 'SETTLED'
           WHEN coalesce(pa.applied, 0) > 0 THEN 'PARTIAL'
           ELSE 'PENDING'
           END                                                            AS status
FROM charges ch
         LEFT JOIN (SELECT charge_id, sum(amount) AS adjusted
                    FROM charge_adjustments
                    GROUP BY charge_id) ca ON ca.charge_id = ch.id
         LEFT JOIN (SELECT pm.charge_id, sum(pm.amount + coalesce(a.adjusted, 0)) AS applied
                    FROM payments pm
                             LEFT JOIN (SELECT payment_id, sum(amount) AS adjusted
                                        FROM adjustments
                                        GROUP BY payment_id) a ON a.payment_id = pm.id
                    WHERE pm.charge_id IS NOT NULL
                    GROUP BY pm.charge_id) pa ON pa.charge_id = ch.id;
