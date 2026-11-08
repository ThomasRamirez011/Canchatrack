-- V6: agenda y competencias (DDS secc. 4, 7 y 11; módulos scheduling y competitions).
-- Los cruces de agenda (RN-14) NO se imponen con EXCLUDE: el DDS 11.2 admite "exigir resolución explícita
-- según política", por lo que ScheduleConflictPolicy decide en el dominio. Los índices apoyan esa consulta.

CREATE TABLE venues
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    name       varchar(120) NOT NULL UNIQUE,
    address    varchar(250),
    reference  text,
    active     boolean      NOT NULL DEFAULT true,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid REFERENCES users (id),
    updated_at timestamptz  NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_venues_updated_at
    BEFORE UPDATE
    ON venues
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TABLE sports_events
(
    id                  uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    event_type          varchar(20) NOT NULL,
    category_id         uuid        NOT NULL REFERENCES categories (id),
    venue_id            uuid        NOT NULL REFERENCES venues (id),   -- escenario obligatorio (DDS 11.2)
    start_at            timestamptz NOT NULL,
    end_at              timestamptz NOT NULL,
    meeting_at          timestamptz,                                  -- hora de concentración
    meeting_point       varchar(200),
    instructions        text,
    status              varchar(10) NOT NULL DEFAULT 'CONFIRMED',
    cancellation_reason text,
    optimistic_version  bigint      NOT NULL DEFAULT 0,
    created_at          timestamptz NOT NULL DEFAULT now(),
    created_by          uuid REFERENCES users (id),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_sports_events_range CHECK (start_at < end_at),
    CONSTRAINT ck_sports_events_type CHECK (event_type IN ('TRAINING', 'EVALUATION', 'FRIENDLY_MATCH', 'TOURNAMENT_MATCH')),
    CONSTRAINT ck_sports_events_status CHECK (status IN ('CONFIRMED', 'CANCELLED', 'COMPLETED')),
    CONSTRAINT ck_sports_events_cancellation CHECK (status <> 'CANCELLED' OR length(btrim(coalesce(cancellation_reason, ''))) > 0)
);
CREATE INDEX ix_sports_events_category_start ON sports_events (category_id, start_at);
CREATE INDEX ix_sports_events_venue_range ON sports_events (venue_id, start_at, end_at) WHERE status <> 'CANCELLED';
CREATE INDEX ix_sports_events_start ON sports_events (start_at);
CREATE TRIGGER trg_sports_events_updated_at
    BEFORE UPDATE
    ON sports_events
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Participantes: entrenadores/evaluadores son usuarios; los jugadores (menores) no tienen cuenta, por eso player_id.
-- El DDS fija UNIQUE(event_id, user_id, role); se conserva y se añade UNIQUE(event_id, player_id).
CREATE TABLE event_participants
(
    id         uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    event_id   uuid        NOT NULL REFERENCES sports_events (id),
    user_id    uuid REFERENCES users (id),
    player_id  uuid REFERENCES players (id),
    role       varchar(20) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid REFERENCES users (id),
    CONSTRAINT ck_event_participants_role CHECK (role IN ('HEAD_COACH', 'ASSISTANT_COACH', 'EVALUATOR', 'PLAYER')),
    CONSTRAINT ck_event_participants_subject CHECK (
        (role = 'PLAYER' AND player_id IS NOT NULL AND user_id IS NULL) OR
        (role <> 'PLAYER' AND user_id IS NOT NULL AND player_id IS NULL)),
    CONSTRAINT ux_event_participants_user UNIQUE (event_id, user_id, role),
    CONSTRAINT ux_event_participants_player UNIQUE (event_id, player_id)
);
CREATE INDEX ix_event_participants_user ON event_participants (user_id) WHERE user_id IS NOT NULL;
CREATE INDEX ix_event_participants_player ON event_participants (player_id) WHERE player_id IS NOT NULL;

CREATE TABLE tournaments
(
    id         uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    season_id  uuid         NOT NULL REFERENCES seasons (id),
    name       varchar(150) NOT NULL,
    start_date date,
    end_date   date,
    created_at timestamptz  NOT NULL DEFAULT now(),
    created_by uuid REFERENCES users (id),
    CONSTRAINT ux_tournaments_season_name UNIQUE (season_id, name),
    CONSTRAINT ck_tournaments_dates CHECK (start_date IS NULL OR end_date IS NULL OR start_date <= end_date)
);

-- Partido = evento deportivo + rival/torneo/resultado. "Partido sin rival o torneo" se rechaza en el dominio (DDS 11.2).
CREATE TABLE matches
(
    id                 uuid PRIMARY KEY      DEFAULT gen_random_uuid(),
    event_id           uuid         NOT NULL UNIQUE REFERENCES sports_events (id),
    tournament_id      uuid REFERENCES tournaments (id),
    opponent           varchar(150) NOT NULL,
    score_for          smallint,
    score_against      smallint,
    result_notes       text,
    result_recorded_by uuid REFERENCES users (id),
    result_recorded_at timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         uuid REFERENCES users (id),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_matches_score_pair CHECK ((score_for IS NULL) = (score_against IS NULL)),
    CONSTRAINT ck_matches_score_range CHECK (coalesce(score_for, 0) >= 0 AND coalesce(score_against, 0) >= 0)
);
CREATE INDEX ix_matches_tournament ON matches (tournament_id);
CREATE TRIGGER trg_matches_updated_at
    BEFORE UPDATE
    ON matches
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Convocatoria a partido. eligibility guarda la evaluación de requisitos en el momento (RN-06, RN-07).
CREATE TABLE callup_entries
(
    id                 uuid PRIMARY KEY     DEFAULT gen_random_uuid(),
    match_id           uuid        NOT NULL REFERENCES matches (id),
    player_id          uuid        NOT NULL REFERENCES players (id),
    status             varchar(12) NOT NULL DEFAULT 'PROPOSED',
    eligibility        jsonb       NOT NULL DEFAULT '{}',
    ineligibility_code varchar(40),
    proposed_by        uuid        NOT NULL REFERENCES users (id),
    proposed_at        timestamptz NOT NULL DEFAULT now(),
    confirmed_by       uuid REFERENCES users (id),
    confirmed_at       timestamptz,
    minutes_played     smallint,
    observations       text,
    CONSTRAINT ux_callup_entries UNIQUE (match_id, player_id),
    CONSTRAINT ck_callup_entries_status CHECK (status IN ('PROPOSED', 'CONFIRMED', 'NOT_ELIGIBLE', 'REMOVED')),
    CONSTRAINT ck_callup_entries_confirmed CHECK (status <> 'CONFIRMED' OR (confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_callup_entries_ineligible CHECK (status <> 'NOT_ELIGIBLE' OR ineligibility_code IS NOT NULL),
    CONSTRAINT ck_callup_entries_minutes CHECK (minutes_played IS NULL OR minutes_played >= 0)
);
CREATE INDEX ix_callup_entries_player ON callup_entries (player_id);
