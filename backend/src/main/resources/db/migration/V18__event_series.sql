-- Sesiones recurrentes (RF-014). Una serie guarda la regla con la que se generaron sus sesiones; cada sesión sigue siendo un
-- evento deportivo independiente (se cancela, se reprograma, tiene asistencia y recordatorios por su cuenta) y apunta a su serie.
CREATE TABLE event_series
(
    id               uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    event_type       varchar(20) NOT NULL,
    category_id      uuid        NOT NULL REFERENCES categories (id),
    venue_id         uuid        NOT NULL REFERENCES venues (id),
    head_coach_id    uuid        NOT NULL REFERENCES users (id),
    weekdays         smallint[]  NOT NULL,                      -- días ISO: 1 = lunes ... 7 = domingo
    start_date       date        NOT NULL,
    end_date         date        NOT NULL,
    start_time       time        NOT NULL,                      -- hora local del club
    end_time         time        NOT NULL,
    time_zone        varchar(60) NOT NULL,                      -- zona con la que se convirtieron las horas locales
    meeting_point    varchar(200),
    instructions     text,
    occurrence_count smallint    NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    created_by       uuid REFERENCES users (id),
    CONSTRAINT ck_event_series_type CHECK (event_type IN ('TRAINING', 'EVALUATION')),
    CONSTRAINT ck_event_series_weekdays CHECK (cardinality(weekdays) BETWEEN 1 AND 7
        AND weekdays <@ ARRAY [1, 2, 3, 4, 5, 6, 7]::smallint[]),
    CONSTRAINT ck_event_series_dates CHECK (start_date <= end_date AND end_date - start_date <= 366),
    CONSTRAINT ck_event_series_times CHECK (start_time < end_time),
    CONSTRAINT ck_event_series_count CHECK (occurrence_count BETWEEN 1 AND 60)
);
CREATE INDEX ix_event_series_coach ON event_series (head_coach_id);

ALTER TABLE sports_events
    ADD COLUMN series_id       uuid REFERENCES event_series (id),
    ADD COLUMN series_position smallint,
    ADD CONSTRAINT ck_sports_events_series CHECK ((series_id IS NULL) = (series_position IS NULL)),
    ADD CONSTRAINT ux_sports_events_series_position UNIQUE (series_id, series_position);
CREATE INDEX ix_sports_events_series ON sports_events (series_id, start_at) WHERE series_id IS NOT NULL;
-- Filtros del calendario (RF-013): escenario y tipo con el rango de fechas.
CREATE INDEX ix_sports_events_type_start ON sports_events (event_type, start_at);
