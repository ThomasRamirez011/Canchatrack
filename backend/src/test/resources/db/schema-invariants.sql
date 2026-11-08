-- Invariantes del esquema (reglas críticas de CLAUDE.md y DDS) verificadas sobre PostgreSQL real.
--
-- Uso:
--   * DatabaseSchemaIT lo ejecuta dentro de una transacción de prueba que se revierte.
--   * scripts/check-schema.sh lo ejecuta entre BEGIN y ROLLBACK (sin Docker ni Maven).
-- NO debe contener BEGIN/COMMIT/ROLLBACK: la transacción la aporta quien lo invoca.

CREATE FUNCTION pg_temp.assert_true(p_condition boolean, p_message text) RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    IF p_condition IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'ASSERTION FAILED: %', p_message;
    END IF;
END;
$$;

-- Ejecuta p_sql y exige que falle con exactamente p_sqlstate.
CREATE FUNCTION pg_temp.expect_error(p_sql text, p_sqlstate text) RETURNS void
    LANGUAGE plpgsql AS
$$
BEGIN
    BEGIN
        EXECUTE p_sql;
    EXCEPTION
        WHEN OTHERS THEN
            IF SQLSTATE = p_sqlstate THEN
                RETURN;
            END IF;
            RAISE EXCEPTION 'Expected SQLSTATE % but got % (%) for: %', p_sqlstate, SQLSTATE, SQLERRM, p_sql;
    END;
    RAISE EXCEPTION 'Expected SQLSTATE % but the statement succeeded: %', p_sqlstate, p_sql;
END;
$$;

DO
$test$
    DECLARE
        v_coord   uuid;
        v_coach   uuid;
        v_ev1     uuid;
        v_ev2     uuid;
        v_gu      uuid; -- usuario del acudiente
        v_guard   uuid;
        v_season  uuid;
        v_call    uuid;
        v_cat     uuid;
        v_p1      uuid;
        v_p2      uuid;
        v_file    uuid;
        v_venue   uuid;
        v_event   uuid;
        v_event2  uuid;
        v_match   uuid;
        v_minute  uuid;
        v_assign  uuid;
        v_rec1    uuid;
        v_round   uuid;
        v_dec     uuid;
        v_fee     uuid;
        v_charge  uuid;
        v_charge2 uuid;
        v_pay     uuid;
        v_pay2    uuid;
        v_doctype uuid;
        v_report  uuid;
        v_uuid    uuid;
        v_notif   uuid;
        v_count   integer;
        v_text    text;
        v_num     numeric;
        v_hash    constant text := repeat('a', 64);
    BEGIN
        ----------------------------------------------------------------------------------------------------------
        -- Datos de referencia sembrados
        ----------------------------------------------------------------------------------------------------------
        SELECT count(*) INTO v_count FROM roles WHERE code IN ('GUARDIAN', 'COORDINATOR', 'COACH', 'EVALUATOR', 'COMMITTEE');
        PERFORM pg_temp.assert_true(v_count = 5, 'cinco roles sembrados');
        SELECT id INTO v_doctype FROM document_types WHERE code = 'MEDICAL_CERTIFICATE';
        PERFORM pg_temp.assert_true(v_doctype IS NOT NULL, 'tipo de documento MEDICAL_CERTIFICATE sembrado');
        SELECT count(*) INTO v_count FROM club_parameters;
        PERFORM pg_temp.assert_true(v_count = 2, 'parámetros documentados sembrados');

        ----------------------------------------------------------------------------------------------------------
        -- Base: usuarios, acudiente, temporada, convocatoria, categoría, jugadores, archivo, escenario
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO users (email, full_name, password_hash) VALUES ('coord@test.local', 'Coordinador', 'x') RETURNING id INTO v_coord;
        INSERT INTO users (email, full_name, password_hash) VALUES ('coach@test.local', 'Entrenador', 'x') RETURNING id INTO v_coach;
        INSERT INTO users (email, full_name, password_hash) VALUES ('ev1@test.local', 'Evaluador 1', 'x') RETURNING id INTO v_ev1;
        INSERT INTO users (email, full_name, password_hash) VALUES ('ev2@test.local', 'Evaluador 2', 'x') RETURNING id INTO v_ev2;
        INSERT INTO users (email, full_name, password_hash) VALUES ('guardian@test.local', 'Acudiente', 'x') RETURNING id INTO v_gu;
        PERFORM pg_temp.expect_error($q$INSERT INTO users (email, full_name) VALUES ('COORD@test.local', 'Duplicado')$q$, '23505');

        INSERT INTO guardians (user_id, document_type, document_number) VALUES (v_gu, 'CC', '100') RETURNING id INTO v_guard;
        INSERT INTO seasons (name, start_at, end_at) VALUES ('2026', '2026-01-01', '2026-12-31') RETURNING id INTO v_season;
        PERFORM pg_temp.expect_error($q$INSERT INTO seasons (name, start_at, end_at) VALUES ('mala', '2026-12-31', '2026-01-01')$q$, '23514');
        INSERT INTO calls (season_id, name, start_at, end_at) VALUES (v_season, 'Convocatoria 2026', '2026-01-01', '2026-02-01') RETURNING id INTO v_call;
        INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES ('Sub-12', 2014, 2015, 20) RETURNING id INTO v_cat;
        PERFORM pg_temp.expect_error($q$INSERT INTO categories (name, birth_year_min, birth_year_max, capacity) VALUES ('Sin cupo', 2014, 2015, 0)$q$, '23514');

        INSERT INTO players (first_name, last_name, document_type, document_number, birth_date)
        VALUES ('Ana', 'Pérez', 'TI', '1001', '2014-05-01') RETURNING id INTO v_p1;
        INSERT INTO players (first_name, last_name, document_type, document_number, birth_date)
        VALUES ('Beto', 'Gómez', 'TI', '1002', '2015-03-02') RETURNING id INTO v_p2;
        SELECT code INTO v_text FROM players WHERE id = v_p1;
        PERFORM pg_temp.assert_true(v_text ~ '^JG-[0-9]{6}$', 'código de jugador generado');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO players (first_name, last_name, document_type, document_number, birth_date) VALUES ('Dup', 'Dup', 'TI', '1001', '2014-01-01')$q$),
                '23505'); -- RN-20: un único expediente por documento
        PERFORM pg_temp.expect_error(format($q$UPDATE players SET status = 'NOPE' WHERE id = %L$q$, v_p1), '23514');

        INSERT INTO player_guardians (player_id, guardian_id, is_primary) VALUES (v_p1, v_guard, true);
        INSERT INTO enrollments (player_id, call_id, guardian_id) VALUES (v_p1, v_call, v_guard);
        PERFORM pg_temp.expect_error(format($q$INSERT INTO enrollments (player_id, call_id, guardian_id) VALUES (%L, %L, %L)$q$, v_p1, v_call, v_guard), '23505');
        SELECT filing_code INTO v_text FROM enrollments WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_text ~ '^RAD-[0-9]{6}$', 'código de radicación generado');

        INSERT INTO stored_files (original_name, content_type, size_bytes, storage_key, sha256)
        VALUES ('acta.pdf', 'application/pdf', 1024, 'k/1', v_hash) RETURNING id INTO v_file;
        PERFORM pg_temp.expect_error($q$INSERT INTO stored_files (original_name, content_type, size_bytes, storage_key, sha256) VALUES ('x', 'x', 1, 'k/2', 'nohex')$q$, '23514');
        PERFORM pg_temp.expect_error(format($q$UPDATE stored_files SET original_name = 'otro' WHERE id = %L$q$, v_file), 'CT001');

        INSERT INTO venues (name) VALUES ('Coliseo') RETURNING id INTO v_venue;

        ----------------------------------------------------------------------------------------------------------
        -- Comité y asignaciones (RN-03): una categoría vigente por jugador
        ----------------------------------------------------------------------------------------------------------
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id) VALUES ('A-1', '2026-02-02', %L, 'REJECTED', %L)$q$, v_p1, v_file),
                '23514'); -- T-02: el rechazo exige motivo
        INSERT INTO committee_minutes (minute_number, meeting_date, player_id, decision, file_id)
        VALUES ('A-1', '2026-02-02', v_p1, 'ASSIGNED', v_file) RETURNING id INTO v_minute;
        INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id)
        VALUES (v_p1, v_cat, v_coach, 'COMMITTEE', v_minute) RETURNING id INTO v_assign;
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id) VALUES (%L, %L, %L, 'COMMITTEE', %L)$q$, v_p1, v_cat, v_coach, v_minute),
                '23505'); -- solo una vigente
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind) VALUES (%L, %L, %L, 'COMMITTEE')$q$, v_p2, v_cat, v_coach),
                '23514'); -- RN-03: sin acta no hay asignación
        UPDATE category_assignments SET valid_to = now() + interval '1 second' WHERE id = v_assign;
        INSERT INTO category_assignments (player_id, category_id, head_coach_id, kind, minute_id)
        VALUES (v_p1, v_cat, v_coach, 'COMMITTEE', v_minute); -- tras cerrar la anterior sí se permite
        INSERT INTO evaluator_assignments (player_id, evaluator_id, minute_id) VALUES (v_p1, v_ev1, v_minute);
        PERFORM pg_temp.expect_error(format($q$INSERT INTO evaluator_assignments (player_id, evaluator_id, minute_id) VALUES (%L, %L, %L)$q$, v_p1, v_ev1, v_minute), '23505');

        ----------------------------------------------------------------------------------------------------------
        -- Agenda y partidos
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at)
        VALUES ('TRAINING', v_cat, v_venue, '2026-03-01 10:00+00', '2026-03-01 11:00+00') RETURNING id INTO v_event;
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at) VALUES ('TRAINING', %L, %L, '2026-03-01 11:00+00', '2026-03-01 10:00+00')$q$, v_cat, v_venue),
                '23514');
        PERFORM pg_temp.expect_error(format($q$UPDATE sports_events SET status = 'CANCELLED' WHERE id = %L$q$, v_event), '23514'); -- cancelar exige motivo
        INSERT INTO event_participants (event_id, user_id, role) VALUES (v_event, v_coach, 'HEAD_COACH');
        INSERT INTO event_participants (event_id, player_id, role) VALUES (v_event, v_p1, 'PLAYER');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO event_participants (event_id, player_id, role) VALUES (%L, %L, 'PLAYER')$q$, v_event, v_p1), '23505');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO event_participants (event_id, user_id, role) VALUES (%L, %L, 'HEAD_COACH')$q$, v_event, v_coach), '23505');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO event_participants (event_id, user_id, role) VALUES (%L, %L, 'PLAYER')$q$, v_event, v_coach), '23514');

        INSERT INTO sports_events (event_type, category_id, venue_id, start_at, end_at)
        VALUES ('FRIENDLY_MATCH', v_cat, v_venue, '2026-03-08 10:00+00', '2026-03-08 12:00+00') RETURNING id INTO v_event2;
        INSERT INTO matches (event_id, opponent) VALUES (v_event2, 'Club Rival') RETURNING id INTO v_match;
        PERFORM pg_temp.expect_error(format($q$UPDATE matches SET score_for = 10 WHERE id = %L$q$, v_match), '23514'); -- marcador incompleto
        INSERT INTO callup_entries (match_id, player_id, proposed_by) VALUES (v_match, v_p1, v_coach);
        PERFORM pg_temp.expect_error(format($q$INSERT INTO callup_entries (match_id, player_id, proposed_by) VALUES (%L, %L, %L)$q$, v_match, v_p1, v_coach), '23505');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO callup_entries (match_id, player_id, proposed_by, status) VALUES (%L, %L, %L, 'CONFIRMED')$q$, v_match, v_p2, v_coach), '23514');

        ----------------------------------------------------------------------------------------------------------
        -- Documentos versionados e inmutables (RN-05) y consentimientos (RN-02)
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO document_versions (player_id, document_type_id, version_number, file_id, issued_on, expires_on, uploaded_by)
        VALUES (v_p1, v_doctype, 1, v_file, '2026-01-10', '2026-07-10', v_gu);
        INSERT INTO document_versions (player_id, document_type_id, version_number, file_id, issued_on, expires_on, uploaded_by)
        VALUES (v_p1, v_doctype, 2, v_file, '2026-07-01', '2027-01-01', v_gu);
        SELECT version_number INTO v_count FROM current_document_versions WHERE player_id = v_p1 AND document_type_id = v_doctype;
        PERFORM pg_temp.assert_true(v_count = 2, 'la versión vigente es la de mayor número');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO document_versions (player_id, document_type_id, version_number, file_id, uploaded_by) VALUES (%L, %L, 1, %L, %L)$q$, v_p1, v_doctype, v_file, v_gu),
                '23505');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO document_versions (player_id, document_type_id, version_number, file_id, issued_on, expires_on, uploaded_by) VALUES (%L, %L, 3, %L, '2026-05-01', '2026-04-01', %L)$q$, v_p1, v_doctype, v_file, v_gu),
                '23514');
        PERFORM pg_temp.expect_error(format($q$UPDATE document_versions SET notes = 'editado' WHERE player_id = %L$q$, v_p1), 'CT001');
        PERFORM pg_temp.expect_error(format($q$DELETE FROM document_versions WHERE player_id = %L$q$, v_p1), 'CT001');

        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO consents (guardian_id, player_id, kind, version_text, method) VALUES (%L, %L, 'MATCH_AUTHORIZATION', 'v1', 'AUTHENTICATED_ACCEPTANCE')$q$, v_guard, v_p1),
                '23514'); -- autorización por partido exige match_id
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO consents (guardian_id, player_id, kind, match_id, version_text, method) VALUES (%L, %L, 'PARTICIPATION', %L, 'v1', 'AUTHENTICATED_ACCEPTANCE')$q$, v_guard, v_p1, v_match),
                '23514');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO consents (guardian_id, player_id, kind, version_text, method) VALUES (%L, %L, 'DATA_PROCESSING', 'v1', 'UPLOADED_EVIDENCE')$q$, v_guard, v_p1),
                '23514'); -- evidencia cargada exige archivo
        INSERT INTO consents (guardian_id, player_id, kind, match_id, version_text, method)
        VALUES (v_guard, v_p1, 'MATCH_AUTHORIZATION', v_match, 'v1', 'AUTHENTICATED_ACCEPTANCE');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO consents (guardian_id, player_id, kind, match_id, version_text, method) VALUES (%L, %L, 'MATCH_AUTHORIZATION', %L, 'v1', 'AUTHENTICATED_ACCEPTANCE')$q$, v_guard, v_p1, v_match),
                '23505'); -- una autorización vigente por partido y jugador

        ----------------------------------------------------------------------------------------------------------
        -- Asistencia offline idempotente (regla crítica 2) y sesión cerrada (RN-15)
        ----------------------------------------------------------------------------------------------------------
        v_uuid := gen_random_uuid();
        INSERT INTO attendance_records (event_id, player_id, status, client_uuid, recorded_by)
        VALUES (v_event, v_p1, 'PRESENT', v_uuid, v_coach) RETURNING id INTO v_rec1;
        PERFORM pg_temp.expect_error( -- reenviar el mismo jugador/sesión con otro client_uuid no duplica
                format($q$INSERT INTO attendance_records (event_id, player_id, status, client_uuid, recorded_by) VALUES (%L, %L, 'PRESENT', gen_random_uuid(), %L)$q$, v_event, v_p1, v_coach),
                '23505');
        PERFORM pg_temp.expect_error( -- reenviar el mismo client_uuid no duplica
                format($q$INSERT INTO attendance_records (event_id, player_id, status, client_uuid, recorded_by) VALUES (%L, %L, 'LATE', %L, %L)$q$, v_event, v_p2, v_uuid, v_coach),
                '23505');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO attendance_records (event_id, player_id, status, excuse_accepted, client_uuid, recorded_by) VALUES (%L, %L, 'PRESENT', true, gen_random_uuid(), %L)$q$, v_event, v_p2, v_coach),
                '23514'); -- solo una excusa puede ser "aceptada"
        INSERT INTO sync_batches (event_id, idempotency_key, received_by) VALUES (v_event, 'batch-1', v_coach);
        PERFORM pg_temp.expect_error(format($q$INSERT INTO sync_batches (event_id, idempotency_key, received_by) VALUES (%L, 'batch-1', %L)$q$, v_event, v_coach), '23505');

        PERFORM pg_temp.expect_error(format($q$INSERT INTO attendance_sessions (event_id, closed_at) VALUES (%L, now())$q$, v_event2), '23514');
        INSERT INTO attendance_sessions (event_id) VALUES (v_event);
        UPDATE attendance_sessions SET closed_at = now(), closed_by = v_coach WHERE event_id = v_event;
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO attendance_records (event_id, player_id, status, client_uuid, recorded_by) VALUES (%L, %L, 'PRESENT', gen_random_uuid(), %L)$q$, v_event, v_p2, v_coach),
                'CT002');
        PERFORM pg_temp.expect_error(format($q$UPDATE attendance_records SET status = 'ABSENT' WHERE id = %L$q$, v_rec1), 'CT002');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO attendance_corrections (record_id, previous_status, new_status, reason, corrected_by) VALUES (%L, 'PRESENT', 'ABSENT', '   ', %L)$q$, v_rec1, v_coord),
                '23514'); -- corrección sin motivo
        PERFORM set_config('canchatrack.attendance_correction', 'on', true); -- corrección autorizada y auditada
        UPDATE attendance_records SET status = 'ABSENT' WHERE id = v_rec1;
        INSERT INTO attendance_corrections (record_id, previous_status, new_status, reason, corrected_by)
        VALUES (v_rec1, 'PRESENT', 'ABSENT', 'Error de marcación', v_coord);
        PERFORM set_config('canchatrack.attendance_correction', 'off', true);
        PERFORM pg_temp.expect_error(format($q$UPDATE attendance_records SET status = 'LATE' WHERE id = %L$q$, v_rec1), 'CT002');
        PERFORM pg_temp.expect_error(format($q$DELETE FROM attendance_records WHERE id = %L$q$, v_rec1), 'CT001');
        PERFORM pg_temp.expect_error(format($q$UPDATE attendance_corrections SET reason = 'otro' WHERE record_id = %L$q$, v_rec1), 'CT001');

        ----------------------------------------------------------------------------------------------------------
        -- Evaluación: concepto individual, justificación y ronda cerrada (RN-08..RN-11; PA-03, PA-04, PA-11)
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO evaluation_rounds (player_id, period_start, period_end, opened_by)
        VALUES (v_p1, '2026-01-01', '2026-06-30', v_coord) RETURNING id INTO v_round;
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO evaluation_rounds (player_id, period_start, period_end, opened_by) VALUES (%L, '2026-01-01', '2026-06-30', %L)$q$, v_p1, v_coord),
                '23505'); -- una sola ronda abierta por jugador
        PERFORM pg_temp.expect_error(format($q$UPDATE evaluation_rounds SET status = 'CLOSED' WHERE id = %L$q$, v_round), '23514');
        INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, period_start, period_end)
        VALUES (v_round, v_ev1, 'FAVORABLE', '2026-01-01', '2026-06-30') RETURNING id INTO v_dec;
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, period_start, period_end) VALUES (%L, %L, 'UNFAVORABLE', '2026-01-01', '2026-06-30')$q$, v_round, v_ev2),
                '23514'); -- PA-03
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, justification, period_start, period_end) VALUES (%L, %L, 'FOLLOW_UP_REQUIRED', '   ', '2026-01-01', '2026-06-30')$q$, v_round, v_ev2),
                '23514');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, period_start, period_end) VALUES (%L, %L, 'FAVORABLE', '2026-01-01', '2026-06-30')$q$, v_round, v_ev1),
                '23505'); -- una decisión por evaluador y ronda
        UPDATE evaluator_decisions SET verdict = 'FOLLOW_UP_REQUIRED', justification = 'Debe mejorar la defensa' WHERE id = v_dec;
        SELECT count(*) INTO v_count FROM evaluator_decision_history WHERE decision_id = v_dec;
        PERFORM pg_temp.assert_true(v_count = 1, 'el cambio de concepto conserva el historial');
        PERFORM pg_temp.expect_error(format($q$UPDATE evaluator_decisions SET evaluator_id = %L WHERE id = %L$q$, v_ev2, v_dec), 'CT001'); -- RN-10
        PERFORM pg_temp.expect_error(format($q$DELETE FROM evaluator_decisions WHERE id = %L$q$, v_dec), 'CT001');
        PERFORM pg_temp.expect_error(format($q$UPDATE evaluator_decision_history SET verdict = 'FAVORABLE' WHERE decision_id = %L$q$, v_dec), 'CT001');
        UPDATE evaluation_rounds SET status = 'CLOSED', result = 'FOLLOW_UP', closed_at = now() WHERE id = v_round;
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO evaluator_decisions (round_id, evaluator_id, verdict, period_start, period_end) VALUES (%L, %L, 'FAVORABLE', '2026-01-01', '2026-06-30')$q$, v_round, v_ev2),
                'CT003');
        PERFORM pg_temp.expect_error(format($q$UPDATE evaluator_decisions SET verdict = 'FAVORABLE' WHERE id = %L$q$, v_dec), 'CT003');
        INSERT INTO evaluation_rounds (player_id, period_start, period_end, opened_by)
        VALUES (v_p1, '2026-07-01', '2026-12-31', v_coord); -- cerrada la anterior se puede abrir otra

        ----------------------------------------------------------------------------------------------------------
        -- Pagos inmutables y asientos compensatorios (regla crítica 1; RN-16; PA-12)
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO fees (season_id, concept, name, amount, valid_from)
        VALUES (v_season, 'MONTHLY', 'Mensualidad', 100000, '2026-01-01') RETURNING id INTO v_fee;
        PERFORM pg_temp.expect_error(format($q$INSERT INTO fees (season_id, concept, name, amount, valid_from) VALUES (%L, 'MONTHLY', 'Cero', 0, '2026-01-01')$q$, v_season), '23514');
        INSERT INTO charges (player_id, fee_id, period, amount, due_date)
        VALUES (v_p1, v_fee, '2026-03-01', 100000, '2026-03-05') RETURNING id INTO v_charge;
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charges (player_id, fee_id, period, amount, due_date) VALUES (%L, %L, '2026-03-01', 100000, '2026-03-05')$q$, v_p1, v_fee), '23505');
        INSERT INTO charges (player_id, fee_id, amount, due_date) VALUES (v_p2, v_fee, 50000, '2026-03-05'); -- cargo único (period NULL)
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charges (player_id, fee_id, amount, due_date) VALUES (%L, %L, 50000, '2026-03-05')$q$, v_p2, v_fee), '23505');

        SELECT balance INTO v_num FROM player_balances WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_num = 100000, 'saldo inicial = cargos');
        INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by)
        VALUES (v_p1, v_fee, v_charge, 100000, 'TRANSFER', '2026-03-02 10:00+00', v_coord) RETURNING id INTO v_pay;
        SELECT balance INTO v_num FROM player_balances WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_num = 0, 'el pago reduce el saldo derivado');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO payments (player_id, fee_id, amount, method, paid_at, recorded_by) VALUES (%L, %L, 0, 'CASH', now(), %L)$q$, v_p1, v_fee, v_coord), '23514');
        PERFORM pg_temp.expect_error(format($q$UPDATE payments SET amount = 1 WHERE id = %L$q$, v_pay), 'CT001');
        PERFORM pg_temp.expect_error(format($q$DELETE FROM payments WHERE id = %L$q$, v_pay), 'CT001');

        INSERT INTO adjustments (payment_id, kind, amount, reason, created_by)
        VALUES (v_pay, 'ADJUSTMENT', -30000, 'Valor digitado de más', v_coord);
        SELECT balance INTO v_num FROM player_balances WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_num = 30000, 'el ajuste compensa sin tocar el pago original');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO adjustments (payment_id, kind, amount, reason, created_by) VALUES (%L, 'VOID', -100000, 'anular', %L)$q$, v_pay, v_coord), 'CT004'); -- debe revertir el neto restante
        PERFORM pg_temp.expect_error(format($q$INSERT INTO adjustments (payment_id, kind, amount, reason, created_by) VALUES (%L, 'ADJUSTMENT', -80000, 'excede', %L)$q$, v_pay, v_coord), 'CT004');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO adjustments (payment_id, kind, amount, reason, created_by) VALUES (%L, 'ADJUSTMENT', -1, '   ', %L)$q$, v_pay, v_coord), '23514');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO adjustments (payment_id, kind, amount, reason, created_by) VALUES (%L, 'ADJUSTMENT', 0, 'cero', %L)$q$, v_pay, v_coord), '23514');
        INSERT INTO adjustments (payment_id, kind, amount, reason, created_by)
        VALUES (v_pay, 'VOID', -70000, 'Pago registrado por error', v_coord);
        SELECT balance INTO v_num FROM player_balances WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_num = 100000, 'la anulación restituye el saldo');
        SELECT count(*) INTO v_count FROM payments WHERE id = v_pay AND amount = 100000;
        PERFORM pg_temp.assert_true(v_count = 1, 'el pago original se conserva');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO adjustments (payment_id, kind, amount, reason, created_by) VALUES (%L, 'VOID', -1, 'otra', %L)$q$, v_pay, v_coord), 'CT004');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO adjustments (payment_id, kind, amount, reason, created_by) VALUES (%L, 'ADJUSTMENT', 5, 'tras anular', %L)$q$, v_pay, v_coord), 'CT004');
        PERFORM pg_temp.expect_error(format($q$UPDATE adjustments SET reason = 'x' WHERE payment_id = %L$q$, v_pay), 'CT001');

        INSERT INTO payments (player_id, fee_id, amount, method, paid_at, recorded_by)
        VALUES (v_p2, v_fee, 50000, 'CASH', now(), v_coord) RETURNING id INTO v_pay2;
        PERFORM pg_temp.expect_error(format($q$INSERT INTO adjustments (payment_id, kind, amount, reason, created_by) VALUES (%L, 'VOID', 50000, 'signo', %L)$q$, v_pay2, v_coord), 'CT004');

        ----------------------------------------------------------------------------------------------------------
        -- Ciclo de vida de los cargos (V12): inmutables, descuentos/exoneraciones como asientos, coherencia con pagos
        ----------------------------------------------------------------------------------------------------------
        PERFORM pg_temp.expect_error(format($q$UPDATE charges SET amount = 1 WHERE id = %L$q$, v_charge), 'CT001');
        PERFORM pg_temp.expect_error(format($q$UPDATE charges SET due_date = '2027-01-01' WHERE id = %L$q$, v_charge), 'CT001');
        PERFORM pg_temp.expect_error(format($q$DELETE FROM charges WHERE id = %L$q$, v_charge), 'CT001');

        -- Descuento: reduce lo adeudado sin tocar el cargo original
        INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by)
        VALUES (v_charge, 'DISCOUNT', -20000, 'Beca parcial', v_coord);
        SELECT balance INTO v_num FROM player_balances WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_num = 80000, 'el descuento reduce el saldo derivado');
        SELECT count(*) INTO v_count FROM charges WHERE id = v_charge AND amount = 100000;
        PERFORM pg_temp.assert_true(v_count = 1, 'el cargo original se conserva');
        SELECT count(*) INTO v_count FROM charge_balances
        WHERE charge_id = v_charge AND net_amount = 80000 AND outstanding = 80000 AND status = 'PENDING';
        PERFORM pg_temp.assert_true(v_count = 1, 'charge_balances: pendiente con el neto tras el descuento');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by) VALUES (%L, 'DISCOUNT', 5, 'signo', %L)$q$, v_charge, v_coord), '23514');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by) VALUES (%L, 'DISCOUNT', -1, '  ', %L)$q$, v_charge, v_coord), '23514');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by) VALUES (%L, 'DISCOUNT', -90000, 'excede', %L)$q$, v_charge, v_coord), 'CT004');
        PERFORM pg_temp.expect_error(format($q$UPDATE charge_adjustments SET reason = 'x' WHERE charge_id = %L$q$, v_charge), 'CT001');
        PERFORM pg_temp.expect_error(format($q$DELETE FROM charge_adjustments WHERE charge_id = %L$q$, v_charge), 'CT001');

        -- Pago parcial aplicado al cargo
        INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by)
        VALUES (v_p1, v_fee, v_charge, 30000, 'CASH', now(), v_coord);
        SELECT count(*) INTO v_count FROM charge_balances
        WHERE charge_id = v_charge AND applied = 30000 AND outstanding = 50000 AND status = 'PARTIAL';
        PERFORM pg_temp.assert_true(v_count = 1, 'charge_balances: parcial con saldo pendiente exacto');

        -- Exoneración: perdona exactamente lo que aún se debe (no lo ya pagado), una sola vez
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by) VALUES (%L, 'DISCOUNT', -60000, 'excede lo pendiente', %L)$q$, v_charge, v_coord), 'CT004'); -- neto 80000, pendiente 50000
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by) VALUES (%L, 'EXEMPTION', -1, 'parcial', %L)$q$, v_charge, v_coord), 'CT004');
        INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by)
        VALUES (v_charge, 'EXEMPTION', -50000, 'Exoneración aprobada por el comité', v_coord);
        SELECT count(*) INTO v_count FROM charge_balances WHERE charge_id = v_charge AND outstanding = 0 AND status = 'SETTLED';
        PERFORM pg_temp.assert_true(v_count = 1, 'charge_balances: la exoneración deja el cargo saldado');
        SELECT balance INTO v_num FROM player_balances WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_num = 0, 'saldo del jugador = 0 tras pago parcial + exoneración');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by) VALUES (%L, 'DISCOUNT', -1, 'otra', %L)$q$, v_charge, v_coord), 'CT004');

        -- Coherencia entre pagos y cargos
        PERFORM pg_temp.expect_error(format($q$INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by) VALUES (%L, %L, %L, 1000, 'CASH', now(), %L)$q$, v_p2, v_fee, v_charge, v_coord), '23514'); -- otro jugador

        -- Anulación única de un cargo (el cargo anulado no cuenta, no recibe pagos ni ajustes y no se vuelve a modificar)
        INSERT INTO charges (player_id, fee_id, period, amount, due_date)
        VALUES (v_p1, v_fee, '2026-04-01', 100000, '2026-04-05') RETURNING id INTO v_charge2;
        PERFORM pg_temp.expect_error(format($q$UPDATE charges SET voided_at = now() WHERE id = %L$q$, v_charge2), '23514'); -- faltan motivo y autor
        UPDATE charges SET voided_at = now(), void_reason = 'Cargo generado por error', voided_by = v_coord WHERE id = v_charge2;
        SELECT balance INTO v_num FROM player_balances WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_num = 0, 'un cargo anulado no cuenta en el saldo');
        SELECT count(*) INTO v_count FROM charge_balances WHERE charge_id = v_charge2 AND status = 'VOIDED';
        PERFORM pg_temp.assert_true(v_count = 1, 'charge_balances: estado VOIDED');
        PERFORM pg_temp.expect_error(format($q$UPDATE charges SET void_reason = 'otro motivo' WHERE id = %L$q$, v_charge2), 'CT001');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO charge_adjustments (charge_id, kind, amount, reason, created_by) VALUES (%L, 'DISCOUNT', -1, 'x', %L)$q$, v_charge2, v_coord), 'CT004');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO payments (player_id, fee_id, charge_id, amount, method, paid_at, recorded_by) VALUES (%L, %L, %L, 1000, 'CASH', now(), %L)$q$, v_p1, v_fee, v_charge2, v_coord), '23514');

        ----------------------------------------------------------------------------------------------------------
        -- Refresh tokens (V13): solo hash, vencimientos coherentes, revocación con motivo
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, family_expires_at)
        VALUES (v_coord, gen_random_uuid(), repeat('a', 64), now(), now() + interval '7 days', now() + interval '30 days');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, family_expires_at) VALUES (%L, gen_random_uuid(), %L, now(), now() + interval '1 day', now() + interval '2 days')$q$, v_coord, repeat('a', 64)), '23505'); -- hash único
        PERFORM pg_temp.expect_error(format($q$INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, family_expires_at) VALUES (%L, gen_random_uuid(), 'no-es-un-sha256', now(), now() + interval '1 day', now() + interval '2 days')$q$, v_coord), '23514'); -- solo hash hexadecimal de 64
        PERFORM pg_temp.expect_error(format($q$INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, family_expires_at) VALUES (%L, gen_random_uuid(), repeat('b', 64), now(), now() + interval '3 days', now() + interval '2 days')$q$, v_coord), '23514'); -- no supera el tope de la sesión
        PERFORM pg_temp.expect_error(format($q$INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, family_expires_at) VALUES (%L, gen_random_uuid(), repeat('c', 64), now(), now() - interval '1 day', now() + interval '2 days')$q$, v_coord), '23514'); -- vence después de emitirse
        PERFORM pg_temp.expect_error(format($q$INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, family_expires_at, revoked_at) VALUES (%L, gen_random_uuid(), repeat('d', 64), now(), now() + interval '1 day', now() + interval '2 days', now())$q$, v_coord), '23514'); -- revocar exige motivo

        ----------------------------------------------------------------------------------------------------------
        -- Auditoría e historial inmutables (regla crítica 3; RN-19)
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO audit_events (actor_id, actor_role, action, entity_type, entity_id, reason)
        VALUES (v_coord, 'COORDINATOR', 'PAYMENT_VOIDED', 'payment', v_pay, 'prueba');
        PERFORM pg_temp.expect_error($q$UPDATE audit_events SET action = 'OTRA'$q$, 'CT001');
        PERFORM pg_temp.expect_error($q$DELETE FROM audit_events$q$, 'CT001');
        PERFORM pg_temp.expect_error($q$TRUNCATE audit_events$q$, 'CT001');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO audit_events (action, entity_type, outcome) VALUES ('X', 'y', 'MAYBE')$q$), '23514');
        INSERT INTO state_history (entity_type, entity_id, transition_code, from_state, to_state, reason, actor_id)
        VALUES ('player', v_p1, 'T-09', 'ACTIVE', 'SUSPENDED', 'prueba', v_coord);
        PERFORM pg_temp.expect_error($q$UPDATE state_history SET to_state = 'ACTIVE'$q$, 'CT001');
        PERFORM pg_temp.expect_error($q$TRUNCATE state_history$q$, 'CT001');
        PERFORM pg_temp.expect_error($q$UPDATE club_parameters SET value = '[]'$q$, 'CT001');
        PERFORM pg_temp.expect_error($q$INSERT INTO club_parameters (param_key, version, value) VALUES ('documents.expiry_alert_days', 1, '[1]')$q$, '23505');

        ----------------------------------------------------------------------------------------------------------
        -- Idempotencia, outbox, informes y notificaciones
        ----------------------------------------------------------------------------------------------------------
        INSERT INTO idempotency_keys (user_id, idempotency_key, http_method, request_path, request_hash, status, expires_at)
        VALUES (v_coach, 'key-1', 'PUT', '/api/v1/sessions/x/attendance', v_hash, 'IN_PROGRESS', now() + interval '1 day');
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO idempotency_keys (user_id, idempotency_key, http_method, request_path, request_hash, status, expires_at) VALUES (%L, 'key-1', 'PUT', '/x', %L, 'IN_PROGRESS', now())$q$, v_coach, v_hash),
                '23505');
        INSERT INTO idempotency_keys (user_id, idempotency_key, http_method, request_path, request_hash, status, expires_at)
        VALUES (v_coord, 'key-1', 'PUT', '/x', v_hash, 'IN_PROGRESS', now() + interval '1 day'); -- misma clave, otro usuario

        INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
        VALUES ('payment', v_pay, 'PaymentRecorded', '{}');

        INSERT INTO season_reports (player_id, season_id, verification_code, template_version, snapshot, file_id, file_hash)
        VALUES (v_p1, v_season, 'VER-0001', 'plantilla-1', '{}', v_file, v_hash) RETURNING id INTO v_report;
        PERFORM pg_temp.expect_error(
                format($q$INSERT INTO season_reports (player_id, season_id, version, verification_code, template_version, snapshot, file_id, file_hash) VALUES (%L, %L, 2, 'VER-0002', 'p', '{}', %L, %L)$q$, v_p1, v_season, v_file, v_hash),
                '23514'); -- una versión 2 exige informe previo y motivo
        INSERT INTO season_reports (player_id, season_id, version, previous_report_id, reason, verification_code, template_version, snapshot, file_id, file_hash)
        VALUES (v_p1, v_season, 2, v_report, 'Corrección autorizada', 'VER-0002', 'plantilla-1', '{}', v_file, v_hash);
        SELECT count(DISTINCT consecutive) INTO v_count FROM season_reports WHERE player_id = v_p1;
        PERFORM pg_temp.assert_true(v_count = 2, 'cada versión recibe un consecutivo distinto');
        PERFORM pg_temp.expect_error(format($q$UPDATE season_reports SET reason = 'x' WHERE id = %L$q$, v_report), 'CT001');

        INSERT INTO notifications (notification_type, recipient_email, dedup_key)
        VALUES ('EVENT_SCHEDULED', 'a@test.local', 'evt:1:a@test.local') RETURNING id INTO v_notif;
        PERFORM pg_temp.expect_error($q$INSERT INTO notifications (notification_type, recipient_email, dedup_key) VALUES ('EVENT_SCHEDULED', 'a@test.local', 'evt:1:a@test.local')$q$, '23505'); -- PA-10: sin duplicar
        PERFORM pg_temp.expect_error(format($q$UPDATE notifications SET status = 'SENT' WHERE id = %L$q$, v_notif), '23514');
        INSERT INTO delivery_attempts (notification_id, attempt_number, succeeded, error) VALUES (v_notif, 1, false, 'timeout SMTP');
        PERFORM pg_temp.expect_error(format($q$INSERT INTO delivery_attempts (notification_id, attempt_number, succeeded) VALUES (%L, 1, true)$q$, v_notif), '23505');
        PERFORM pg_temp.expect_error(format($q$UPDATE delivery_attempts SET succeeded = true WHERE notification_id = %L$q$, v_notif), 'CT001');

        RAISE NOTICE 'schema invariants OK';
    END
$test$;
