-- V1: funciones de soporte compartidas por todas las migraciones.
--
-- Códigos SQLSTATE propios (clase 'CT') que la capa de aplicación debe traducir a ErrorCode (DDS secc. 13):
--   CT001  IMMUTABLE_RECORD            registro append-only (pagos, auditoría, versiones, historial)   -> PAYMENT_IMMUTABLE u otro según la tabla
--   CT002  ATTENDANCE_SESSION_CLOSED   asistencia de una sesión cerrada (RN-15)                         -> ATTENDANCE_SESSION_CLOSED
--   CT003  ROUND_CLOSED                decisión sobre una ronda de evaluación cerrada (RN-08..RN-10)    -> ROUND_CLOSED
--   CT004  ADJUSTMENT_INVALID          ajuste/anulación de pago incoherente (RN-16)                     -> PAYMENT_IMMUTABLE / validación
-- Son la última línea de defensa: el dominio debe validar antes y devolver el error de negocio correspondiente.

CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- Bloquea UPDATE / DELETE / TRUNCATE en tablas append-only.
CREATE FUNCTION forbid_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'IMMUTABLE_RECORD: % is not allowed on table %', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'CT001';
END;
$$;
